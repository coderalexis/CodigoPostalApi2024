package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.config.MetricsConfiguration;
import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.AdvancedSearchRequest;
import com.coderalexis.CodigoPostalApi.model.FederalEntity;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.util.PaginationSupport;
import com.coderalexis.CodigoPostalApi.util.Util;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Lógica de consulta de códigos postales sobre los índices en memoria. La carga
 * y el parseo del catálogo se delegan en {@link SepomexZipCodeLoader} (#10) y la
 * mecánica de paginación en {@link PaginationSupport}, dejando esta clase enfocada
 * en las búsquedas y sus métricas/caché.
 */
@Service
@Slf4j
public class ZipCodeService {

    private static final int PARTIAL_SEARCH_MAX_LIMIT = 50;
    // Pre-compiled pattern for validating digit-only input (avoids recompiling on every partial search)
    private static final Pattern DIGITS_PATTERN = Pattern.compile("^\\d+$");

    // Índices cargados una vez al arranque y tratados como solo-lectura. Se publican
    // como volatile: la escritura de la referencia (en loadZipCodes) establece un
    // happens-before con cualquier lectura posterior desde los hilos de request.
    private volatile Map<String, ZipCode> zipCodesByCode = Map.of();
    private volatile NavigableMap<String, ZipCode> zipCodesSorted = Collections.emptyNavigableMap();
    private volatile Map<String, Set<ZipCode>> zipCodesByNormalizedEntity = Map.of();
    private volatile Map<String, Set<ZipCode>> zipCodesByNormalizedMunicipality = Map.of();

    // Pre-computed statistics (immutable after load)
    private volatile ZipCodeStats cachedStats;
    // Pre-computed federal entities list (immutable after load)
    private volatile List<FederalEntity> cachedFederalEntities;
    // Pre-computed map: normalized entity key -> sorted set of municipalities (original case)
    // Avoids re-iterating zipcode -> distinct municipality each call.
    private volatile Map<String, Set<String>> municipalitiesByNormalizedEntity;

    /**
     * Cache directo para búsquedas por prefijo. Se usa Caffeine en vez de
     * {@code @Cacheable} para evitar la trampa de self-invocation (Spring sólo
     * intercepta las llamadas entre beans, no entre métodos de la misma clase).
     * El cache se llava sólo por prefijo y guarda hasta
     * {@link #PARTIAL_SEARCH_MAX_LIMIT} ZipCodes; el {@code limit} del request
     * se aplica al leer. Fix #5.
     */
    private final Cache<String, List<ZipCode>> partialPrefixCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private volatile boolean dataLoaded = false;

    private final MetricsConfiguration metricsConfiguration;
    private final SepomexZipCodeLoader loader;

    public ZipCodeService(MetricsConfiguration metricsConfiguration, SepomexZipCodeLoader loader) {
        this.metricsConfiguration = metricsConfiguration;
        this.loader = loader;
    }

    public boolean isDataLoaded() {
        return dataLoaded;
    }

    public int getZipCodeCount() {
        return zipCodesByCode.size();
    }

    // No @Cacheable needed: Map.get() is already O(1).
    // Caching would add serialization overhead without latency benefit.
    public ZipCode getZipCode(String zipcode) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("direct");
            ZipCode zipCode = zipCodesByCode.get(zipcode);
            if (zipCode == null) {
                metricsConfiguration.recordSearchError("direct", "not_found");
                throw new ZipCodeNotFoundException("Codigo postal no encontrado: " + zipcode);
            }
            return zipCode;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "direct");
        }
    }

    @PostConstruct
    public void loadZipCodes() {
        // Fail-fast: si el catálogo no puede cargarse, abortamos el arranque en vez
        // de dejar el proceso vivo sirviendo 500 (NPE en los getters precomputados)
        // y 200 con cuerpos nulos. Para una API cuyo único propósito son estos datos,
        // es preferible que el orquestador (Railway/k8s) reinicie el contenedor.
        ZipCodeData data;
        try {
            data = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Error al cargar los codigos postales", e);
        }

        if (data == null || data.byCode().isEmpty()) {
            throw new IllegalStateException(
                    "No se pudo cargar el catalogo de codigos postales o quedo vacio; abortando el arranque");
        }

        this.zipCodesByCode = data.byCode();
        this.zipCodesSorted = data.sorted();
        this.zipCodesByNormalizedEntity = data.byNormalizedEntity();
        this.zipCodesByNormalizedMunicipality = data.byNormalizedMunicipality();

        buildPreComputedData();

        // Fix #2: marcamos dataLoaded sólo DESPUÉS de que las estructuras
        // precomputadas (cachedStats, cachedFederalEntities,
        // municipalitiesByNormalizedEntity) estén completas.
        dataLoaded = true;
    }

    private void buildPreComputedData() {
        // Single pass (#10): durante esta pasada acumulamos totalSettlements y la
        // lista de FederalEntity para evitar recorrer el mapa varias veces.
        long totalSettlements = 0L;
        Map<String, List<ZipCode>> zipCodesByEntity = new HashMap<>();
        Map<String, Set<String>> municipalitiesByEntity = new HashMap<>();

        for (ZipCode zc : zipCodesByCode.values()) {
            totalSettlements += zc.getSettlements().size();

            zipCodesByEntity
                    .computeIfAbsent(zc.getFederalEntity(), k -> new ArrayList<>())
                    .add(zc);

            municipalitiesByEntity
                    .computeIfAbsent(zc.getNormalizedFederalEntity(), k -> new TreeSet<>())
                    .add(zc.getMunicipality());
        }

        cachedStats = ZipCodeStats.builder()
                .totalZipCodes(zipCodesByCode.size())
                .totalFederalEntities(zipCodesByNormalizedEntity.size())
                .totalMunicipalities(zipCodesByNormalizedMunicipality.size())
                .totalSettlements(totalSettlements)
                .build();

        cachedFederalEntities = zipCodesByEntity.entrySet().stream()
                .map(entry -> {
                    String entityName = entry.getKey();
                    List<ZipCode> zipCodes = entry.getValue();

                    long municipalitiesCount = zipCodes.stream()
                            .map(ZipCode::getMunicipality)
                            .distinct()
                            .count();

                    return FederalEntity.builder()
                            .name(entityName)
                            .zipCodesCount(zipCodes.size())
                            .municipalitiesCount((int) municipalitiesCount)
                            .build();
                })
                .sorted(Comparator.comparing(FederalEntity::getName))
                .toList();

        // Snapshot inmutable para lecturas concurrentes (#9).
        Map<String, Set<String>> immutable = new HashMap<>(municipalitiesByEntity.size());
        for (Map.Entry<String, Set<String>> e : municipalitiesByEntity.entrySet()) {
            immutable.put(e.getKey(), Collections.unmodifiableSortedSet((TreeSet<String>) e.getValue()));
        }
        this.municipalitiesByNormalizedEntity = Collections.unmodifiableMap(immutable);

        log.info("  - Estadisticas, entidades federativas y municipios precomputados");
    }

    /**
     * Búsqueda paginada por entidad federativa. Fix #8: ordena el conjunto
     * (vía índice invertido) y materializa sólo la página solicitada en vez
     * de la lista completa.
     */
    @Cacheable(value = "federalEntitySearchPaged", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm) + '_' + #page + '_' + #size")
    public PagedResponse<ZipCode> searchByFederalEntity(String searchTerm, int page, int size) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("federal_entity");
            PaginationSupport.validatePagination(page, size);
            String normalizedSearchTerm = validateSearchTerm(searchTerm, "federal_entity");

            Set<ZipCode> matches = findCandidatesInIndex(zipCodesByNormalizedEntity, normalizedSearchTerm);
            if (matches.isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para la entidad federativa: " + searchTerm
                );
            }

            PagedResponse<ZipCode> response = PaginationSupport.paginateSorted(
                    matches,
                    Comparator.comparing(ZipCode::getZipCode),
                    page,
                    size);

            metricsConfiguration.recordResultSize("federal_entity", (int) response.getTotalElements());
            return response;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "federal_entity");
        }
    }

    /**
     * Búsqueda paginada por municipio. Fix #8: idem entidad federativa.
     */
    @Cacheable(value = "municipalitySearchPaged", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm) + '_' + #page + '_' + #size")
    public PagedResponse<ZipCode> searchByMunicipality(String searchTerm, int page, int size) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("municipality");
            PaginationSupport.validatePagination(page, size);
            String normalizedSearchTerm = validateSearchTerm(searchTerm, "municipality");

            Set<ZipCode> matches = findCandidatesInIndex(zipCodesByNormalizedMunicipality, normalizedSearchTerm);
            if (matches.isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para el municipio: " + searchTerm
                );
            }

            PagedResponse<ZipCode> response = PaginationSupport.paginateSorted(
                    matches,
                    Comparator.comparing(ZipCode::getZipCode),
                    page,
                    size);

            metricsConfiguration.recordResultSize("municipality", (int) response.getTotalElements());
            return response;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "municipality");
        }
    }

    /**
     * Returns pre-computed statistics (calculated once at startup).
     */
    public ZipCodeStats getStatistics() {
        return cachedStats;
    }

    /**
     * Prefix search using the sorted {@link NavigableMap#subMap} for O(log n + k)
     * performance.
     *
     * <p>Fix #5: la cache ahora se llava SÓLO por el prefijo normalizado. Para
     * cada prefijo cacheamos hasta {@link #PARTIAL_SEARCH_MAX_LIMIT} resultados
     * y aplicamos el {@code limit} solicitado tras leer del cache; esto evita
     * que un cliente que pida limit=1,2,...,50 con el mismo prefijo agote la
     * cache con 50 entradas equivalentes.</p>
     */
    public List<ZipCode> searchByPartialCode(String partialCode, int limit) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("partial");

            if (partialCode == null || partialCode.trim().isEmpty()) {
                metricsConfiguration.recordSearchError("partial", "empty_search");
                throw new IllegalArgumentException("El codigo postal no puede estar vacio");
            }

            String cleanCode = partialCode.trim();

            if (!DIGITS_PATTERN.matcher(cleanCode).matches() || cleanCode.length() > 5) {
                metricsConfiguration.recordSearchError("partial", "invalid_format");
                throw new IllegalArgumentException("El codigo postal debe contener entre 1 y 5 digitos");
            }

            int effectiveLimit = Math.min(Math.max(limit, 1), PARTIAL_SEARCH_MAX_LIMIT);
            List<ZipCode> cached = partialPrefixCache.get(cleanCode, this::computePartialPrefix);

            if (cached.isEmpty()) {
                metricsConfiguration.recordSearchError("partial", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales que inicien con: " + partialCode
                );
            }

            List<ZipCode> limited = cached.size() <= effectiveLimit
                    ? cached
                    : cached.subList(0, effectiveLimit);

            metricsConfiguration.recordResultSize("partial", limited.size());
            return limited;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "partial");
        }
    }

    /**
     * Calcula los resultados para un prefijo concreto, limitando a
     * {@link #PARTIAL_SEARCH_MAX_LIMIT} para que el cache tenga un tamaño
     * predecible por entrada.
     */
    private List<ZipCode> computePartialPrefix(String prefix) {
        // El exclusive upper bound se queda dentro del prefijo solicitado para que
        // una búsqueda como "0199" no incluya "020xx".
        String toKey = computeUpperBound(prefix);
        return zipCodesSorted.subMap(prefix, true, toKey, false)
                .values().stream()
                .limit(PARTIAL_SEARCH_MAX_LIMIT)
                .toList();
    }

    /**
     * Computes the exclusive upper bound for prefix-based range queries.
     *
     * Appending the maximum Unicode character creates a key that is greater than
     * every digit-only zip code that starts with the requested prefix, without
     * including the next numeric range. For example, range ["0199", "0199￿")
     * includes "01990" through "01999" but excludes "02000".
     */
    private String computeUpperBound(String prefix) {
        return prefix + Character.MAX_VALUE;
    }

    /**
     * Returns pre-computed federal entities list (calculated once at startup).
     * No usa {@code @Cacheable}: el resultado ya es un campo inmutable en memoria,
     * así que el proxy + lookup de caché sólo añadiría overhead y, además,
     * impediría registrar métricas en los hits de caché.
     */
    public List<FederalEntity> getAllFederalEntities() {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordResultSize("federal_entities", cachedFederalEntities.size());
            return cachedFederalEntities;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "federal_entities");
        }
    }

    /**
     * Fix #9: utiliza el índice precomputado {@code municipalitiesByNormalizedEntity}.
     * Para "mex" matchea ~3 entidades grandes y une sus sets ya ordenados, sin
     * recorrer decenas de miles de ZipCodes para hacer {@code distinct()}.
     */
    @Cacheable(value = "municipalitiesByEntity", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#federalEntity)")
    public List<String> getMunicipalitiesByFederalEntity(String federalEntity) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            if (federalEntity == null || federalEntity.trim().isEmpty()) {
                throw new IllegalArgumentException("La entidad federativa no puede estar vacia");
            }

            String normalizedSearchTerm = Util.normalizeSearchTerm(federalEntity);

            // TreeSet para mantener orden alfabético al unir municipios de varias entidades.
            Set<String> municipalities = new TreeSet<>();
            for (Map.Entry<String, Set<String>> entry : municipalitiesByNormalizedEntity.entrySet()) {
                if (entry.getKey().contains(normalizedSearchTerm)) {
                    municipalities.addAll(entry.getValue());
                }
            }

            if (municipalities.isEmpty()) {
                throw new ZipCodeNotFoundException(
                        "No se encontraron municipios para la entidad federativa: " + federalEntity
                );
            }

            metricsConfiguration.recordResultSize("municipalities_by_entity", municipalities.size());
            return List.copyOf(municipalities);
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "municipalities_by_entity");
        }
    }

    public List<Settlements> getSettlementsByZipCode(String zipcode) {
        ZipCode zipCode = getZipCode(zipcode);
        return zipCode.getSettlements();
    }

    /**
     * Paginated advanced search that materializes only the requested page.
     * This keeps broad advanced searches from allocating all matching ZipCode
     * objects when clients only need one page.
     */
    @Cacheable(
            value = "advancedSearchPaged",
            key = "#request.normalizedFilterCacheKey() + '_' + #page + '_' + #size",
            condition = "#request != null && #request.hasAnyFilter() && #page >= 0 && #size > 0")
    public PagedResponse<ZipCode> advancedSearch(AdvancedSearchRequest request, int page, int size) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            PaginationSupport.validatePagination(page, size);
            AdvancedSearchCriteria criteria = validateAndNormalizeAdvancedSearchRequest(request);
            Collection<ZipCode> candidates = resolveOrderedSearchCandidates(criteria);

            PagedResponse<ZipCode> response = PaginationSupport.paginateFiltered(
                    candidates,
                    zipCode -> matchesAdvancedCriteria(zipCode, criteria),
                    page,
                    size);

            if (response.getTotalElements() == 0) {
                metricsConfiguration.recordSearchError("advanced", "not_found");
                throw new ZipCodeNotFoundException("No se encontraron codigos postales con los criterios especificados");
            }

            metricsConfiguration.recordResultSize("advanced", (int) response.getTotalElements());
            return response;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "advanced");
        }
    }

    private AdvancedSearchCriteria validateAndNormalizeAdvancedSearchRequest(AdvancedSearchRequest request) {
        if (request == null) {
            metricsConfiguration.recordSearchError("advanced", "empty_search");
            throw new IllegalArgumentException("Debe proporcionar al menos un criterio de busqueda");
        }

        if ((request.getFederalEntity() == null || request.getFederalEntity().isBlank()) &&
            (request.getMunicipality() == null || request.getMunicipality().isBlank()) &&
            (request.getSettlement() == null || request.getSettlement().isBlank()) &&
            (request.getSettlementType() == null || request.getSettlementType().isBlank()) &&
            (request.getZoneType() == null || request.getZoneType().isBlank())) {
            metricsConfiguration.recordSearchError("advanced", "empty_search");
            throw new IllegalArgumentException("Debe proporcionar al menos un criterio de busqueda");
        }

        return new AdvancedSearchCriteria(
                request.getFederalEntity() != null ? Util.normalizeSearchTerm(request.getFederalEntity()) : null,
                request.getMunicipality() != null ? Util.normalizeSearchTerm(request.getMunicipality()) : null,
                request.getSettlement() != null ? Util.normalizeSearchTerm(request.getSettlement()) : null,
                request.getSettlementType() != null ? Util.normalizeSearchTerm(request.getSettlementType()) : null,
                request.getZoneType() != null ? Util.normalizeSearchTerm(request.getZoneType()) : null);
    }

    private Collection<ZipCode> resolveOrderedSearchCandidates(AdvancedSearchCriteria criteria) {
        Collection<ZipCode> candidates = resolveSearchCandidates(
                criteria.normalizedEntity(),
                criteria.normalizedMunicipality());

        if (candidates.isEmpty()) {
            return List.of();
        }

        if (candidates instanceof Set<?>) {
            return candidates.stream()
                    .sorted(Comparator.comparing(ZipCode::getZipCode))
                    .toList();
        }

        return candidates;
    }

    /**
     * Fix #25: tras precomputar normalizedName/Type/Zone al cargar, los
     * fallbacks {@code getX() != null ? ... : Util.normalizeString(...)} eran
     * código muerto. Simplificamos al acceso directo: si en el futuro alguien
     * mutiera Settlements en runtime, prefiero un NPE visible que un fallback
     * silencioso que oculte el bug.
     */
    private boolean matchesAdvancedCriteria(ZipCode zipCode, AdvancedSearchCriteria criteria) {
        if (isFilterPresent(criteria.normalizedEntity()) &&
                !zipCode.getNormalizedFederalEntity().contains(criteria.normalizedEntity())) {
            return false;
        }

        if (isFilterPresent(criteria.normalizedMunicipality()) &&
                !zipCode.getNormalizedMunicipality().contains(criteria.normalizedMunicipality())) {
            return false;
        }

        if (isFilterPresent(criteria.normalizedSettlement()) ||
            isFilterPresent(criteria.normalizedSettlementType()) ||
            isFilterPresent(criteria.normalizedZoneType())) {
            return zipCode.getSettlements().stream()
                    .anyMatch(settlement -> matchesSettlementCriteria(settlement, criteria));
        }

        return true;
    }

    private boolean matchesSettlementCriteria(Settlements settlement, AdvancedSearchCriteria criteria) {
        if (isFilterPresent(criteria.normalizedSettlement()) &&
                !settlement.normalizedName().contains(criteria.normalizedSettlement())) {
            return false;
        }
        if (isFilterPresent(criteria.normalizedSettlementType()) &&
                !settlement.normalizedSettlementType().contains(criteria.normalizedSettlementType())) {
            return false;
        }
        if (isFilterPresent(criteria.normalizedZoneType())) {
            return settlement.normalizedZoneType().contains(criteria.normalizedZoneType());
        }
        return true;
    }

    private record AdvancedSearchCriteria(
            String normalizedEntity,
            String normalizedMunicipality,
            String normalizedSettlement,
            String normalizedSettlementType,
            String normalizedZoneType) {
    }

    private String validateSearchTerm(String searchTerm, String searchType) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            metricsConfiguration.recordSearchError(searchType, "empty_search");
            throw new IllegalArgumentException("El termino de busqueda no puede estar vacio");
        }

        return Util.normalizeSearchTerm(searchTerm);
    }

    private Collection<ZipCode> resolveSearchCandidates(String normalizedEntity, String normalizedMunicipality) {
        Set<ZipCode> entityCandidates = findCandidatesInIndex(zipCodesByNormalizedEntity, normalizedEntity);
        if (isFilterPresent(normalizedEntity) && entityCandidates.isEmpty()) {
            return List.of();
        }

        Set<ZipCode> municipalityCandidates = findCandidatesInIndex(
                zipCodesByNormalizedMunicipality, normalizedMunicipality);
        if (isFilterPresent(normalizedMunicipality) && municipalityCandidates.isEmpty()) {
            return List.of();
        }

        if (!entityCandidates.isEmpty() && !municipalityCandidates.isEmpty()) {
            return entityCandidates.size() <= municipalityCandidates.size()
                    ? entityCandidates
                    : municipalityCandidates;
        }

        if (!entityCandidates.isEmpty()) {
            return entityCandidates;
        }

        if (!municipalityCandidates.isEmpty()) {
            return municipalityCandidates;
        }

        // Fallback: full scan in deterministic zip-code order only when filtering by settlement/type/zone.
        return zipCodesSorted.values();
    }

    private Set<ZipCode> findCandidatesInIndex(Map<String, Set<ZipCode>> index, String normalizedSearchTerm) {
        if (!isFilterPresent(normalizedSearchTerm)) {
            return Set.of();
        }

        Set<ZipCode> candidates = new HashSet<>();
        index.entrySet().stream()
                .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                .forEach(entry -> candidates.addAll(entry.getValue()));
        return candidates;
    }

    private boolean isFilterPresent(String normalizedSearchTerm) {
        return normalizedSearchTerm != null && !normalizedSearchTerm.isEmpty();
    }
}
