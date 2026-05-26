package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.config.MetricsConfiguration;
import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.AdvancedSearchRequest;
import com.coderalexis.CodigoPostalApi.model.FederalEntity;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.util.Util;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ZipCodeService {
    // Pre-compiled Pattern for splitting lines (avoids recompiling on every split call)
    private static final Pattern PIPE_PATTERN = Pattern.compile("\\|");

    // Column indices based on CPdescarga.txt structure
    private static final int COL_ZIP_CODE = 0;
    private static final int COL_SETTLEMENT_NAME = 1;
    private static final int COL_SETTLEMENT_TYPE = 2;
    private static final int COL_MUNICIPALITY = 3;
    private static final int COL_FEDERAL_ENTITY = 4;
    private static final int COL_LOCALITY = 5;
    private static final int COL_ZONE_TYPE_INDEX = 13;
    private static final int MIN_COLUMNS = 6;
    private static final int MAX_ERRORS_THRESHOLD = 100;
    private static final int PARTIAL_SEARCH_MAX_LIMIT = 50;

    private static final Pattern ZIP_CODE_PATTERN =
        Pattern.compile("^\\d{5}$");
    // Pre-compiled pattern for validating digit-only input (avoids recompiling on every partial search)
    private static final Pattern DIGITS_PATTERN = Pattern.compile("^\\d+$");

    // Data is loaded once at startup and then treated as read-only. Non-concurrent
    // collections avoid unnecessary synchronization overhead on the hot read path.
    private final Map<String, ZipCode> zipCodesByCode = new HashMap<>();
    // Sorted map for O(log n) prefix searches instead of O(n) full scan
    private final NavigableMap<String, ZipCode> zipCodesSorted = new TreeMap<>();
    // Inverted indices for fast searches by entity and municipality
    private final Map<String, Set<ZipCode>> zipCodesByNormalizedEntity = new HashMap<>();
    private final Map<String, Set<ZipCode>> zipCodesByNormalizedMunicipality = new HashMap<>();

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
    private int errorCount = 0;

    private final MetricsConfiguration metricsConfiguration;

    @Value("${zipcode.file.path}")
    private String filePath;

    private static final String RESOURCE_FILE = "CPdescarga.txt";

    public ZipCodeService(MetricsConfiguration metricsConfiguration) {
        this.metricsConfiguration = metricsConfiguration;
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
        try (InputStream stream = getInputStream()) {
            if (stream == null) {
                log.error("No se pudo cargar ningun archivo de codigos postales");
                return;
            }

            processZipCodeFile(stream);
            buildPreComputedData();
            // Fix #2: marcamos dataLoaded sólo DESPUÉS de que las estructuras
            // precomputadas (cachedStats, cachedFederalEntities,
            // municipalitiesByNormalizedEntity) estén completas. De lo contrario
            // un caller temprano podría ver dataLoaded=true pero campos null.
            dataLoaded = true;
        } catch (IOException e) {
            log.error("Error al cargar los codigos postales", e);
        }
    }

    private void buildPreComputedData() {
        // Make all settlement lists immutable to prevent accidental mutation of internal state.
        // Single pass (#10): durante esta pasada también acumulamos totalSettlements
        // y la lista de FederalEntity para evitar recorrer el mapa varias veces.
        long totalSettlements = 0L;
        Map<String, List<ZipCode>> zipCodesByEntity = new HashMap<>();
        Map<String, Set<String>> municipalitiesByEntity = new HashMap<>();

        for (ZipCode zc : zipCodesByCode.values()) {
            if (zc.getSettlements() != null) {
                zc.setSettlements(List.copyOf(zc.getSettlements()));
                totalSettlements += zc.getSettlements().size();
            }

            zipCodesByEntity
                    .computeIfAbsent(zc.getFederalEntity(), k -> new ArrayList<>())
                    .add(zc);

            municipalitiesByEntity
                    .computeIfAbsent(zc.getNormalizedFederalEntity(), k -> new TreeSet<>())
                    .add(zc.getMunicipality());
        }
        log.info("  - Listas de asentamientos convertidas a inmutables");

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
     * Resuelve el InputStream del catálogo SEPOMEX.
     *
     * <p>Fix #20: si el usuario configura un path explícito (vía
     * {@code zipcode.file.path}) y éste no existe, fallamos rápido en vez de
     * caer silenciosamente al recurso interno, que enmascaraba problemas de
     * despliegue (config incorrecta sin error visible).</p>
     */
    private InputStream getInputStream() throws IOException {
        boolean userConfiguredPath = filePath != null && !filePath.isBlank();

        if (userConfiguredPath && filePath.startsWith("classpath:")) {
            String resourcePath = filePath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                log.info("Cargando codigos postales desde classpath: {}", resourcePath);
                return resource.getInputStream();
            }
            throw new IOException(
                "Recurso configurado en 'zipcode.file.path' no encontrado en classpath: " + resourcePath);
        }

        if (userConfiguredPath) {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                log.info("Cargando codigos postales desde {}", filePath);
                return Files.newInputStream(path);
            }
            throw new IOException(
                "Archivo configurado en 'zipcode.file.path' no encontrado: " + filePath);
        }

        ClassPathResource resource = new ClassPathResource(RESOURCE_FILE);
        if (resource.exists()) {
            log.info("Cargando codigos postales desde recurso interno {}", RESOURCE_FILE);
            return resource.getInputStream();
        }

        return null;
    }

    private void processZipCodeFile(InputStream stream) throws IOException {
        long startTime = System.currentTimeMillis();
        errorCount = 0;

        BufferedInputStream bufferedStream = new BufferedInputStream(stream);
        Charset charset = detectCharset(bufferedStream);
        log.info("Encoding detectado: {}", charset.name());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bufferedStream, charset))) {

            // Skip first two lines (metadata and header)
            reader.readLine();
            reader.readLine();

            long linesProcessed = reader.lines()
                    .filter(this::processLine)
                    .count();

            long duration = System.currentTimeMillis() - startTime;

            log.info("Datos cargados exitosamente en {}ms", duration);
            log.info("  - Codigos postales unicos: {}", zipCodesByCode.size());
            log.info("  - Entidades federativas: {}", zipCodesByNormalizedEntity.size());
            log.info("  - Municipios: {}", zipCodesByNormalizedMunicipality.size());
            log.info("  - Lineas procesadas: {}", linesProcessed);
            if (errorCount > 0) {
                log.warn("  - Lineas con errores: {}", errorCount);
            }
        }
    }

    private Charset detectCharset(BufferedInputStream stream) throws IOException {
        stream.mark(4096);
        byte[] sample = new byte[4096];
        int bytesRead = stream.read(sample);
        stream.reset();

        if (bytesRead <= 0) {
            return StandardCharsets.UTF_8;
        }

        boolean hasHighBytes = false;
        boolean hasUtf8Sequences = false;

        for (int i = 0; i < bytesRead; i++) {
            int b = sample[i] & 0xFF;
            if (b >= 0x80) {
                hasHighBytes = true;
                if ((b & 0xE0) == 0xC0 || (b & 0xF0) == 0xE0) {
                    if (i + 1 < bytesRead && (sample[i + 1] & 0xC0) == 0x80) {
                        hasUtf8Sequences = true;
                    }
                }
            }
        }

        if (hasHighBytes && !hasUtf8Sequences) {
            log.debug("Detectado encoding ISO-8859-1 (bytes altos sin secuencias UTF-8)");
            return StandardCharsets.ISO_8859_1;
        }

        if (hasUtf8Sequences) {
            log.debug("Detectado encoding UTF-8 (secuencias UTF-8 validas encontradas)");
            return StandardCharsets.UTF_8;
        }

        log.debug("Usando encoding por defecto ISO-8859-1 para archivo SEPOMEX");
        return StandardCharsets.ISO_8859_1;
    }

    private boolean processLine(String line) {
        try {
            String[] words = PIPE_PATTERN.split(line);

            if (words.length < MIN_COLUMNS) {
                handleError("Linea con formato incorrecto (columnas insuficientes): {}",
                    line.substring(0, Math.min(100, line.length())));
                return false;
            }

            String zipCodeKey = words[COL_ZIP_CODE].trim();

            if (!isValidZipCode(zipCodeKey)) {
                handleError("Codigo postal invalido '{}' en linea: {}",
                    zipCodeKey, line.substring(0, Math.min(100, line.length())));
                return false;
            }

            if (words[COL_FEDERAL_ENTITY].trim().isEmpty() ||
                words[COL_MUNICIPALITY].trim().isEmpty()) {
                handleError("Campos requeridos vacios en codigo postal: {}", zipCodeKey);
                return false;
            }

            String federalEntity = words[COL_FEDERAL_ENTITY].trim();
            String municipality = words[COL_MUNICIPALITY].trim();
            String normalizedEntity = Util.normalizeString(federalEntity);
            String normalizedMunicipality = Util.normalizeString(municipality);

            ZipCode zipCode = zipCodesByCode.computeIfAbsent(zipCodeKey, k -> {
                ZipCode z = new ZipCode();
                z.setZipCode(k);
                z.setLocality(words[COL_LOCALITY].trim());
                z.setFederalEntity(federalEntity);
                z.setMunicipality(municipality);
                z.setNormalizedFederalEntity(normalizedEntity);
                z.setNormalizedMunicipality(normalizedMunicipality);
                z.setSettlements(new ArrayList<>());

                // Add to sorted map for prefix searches
                zipCodesSorted.put(k, z);
                return z;
            });

            String settlementName = words[COL_SETTLEMENT_NAME].trim();
            String settlementTypeVal = words[COL_SETTLEMENT_TYPE].trim();
            String zoneTypeVal = words.length > COL_ZONE_TYPE_INDEX ?
                words[COL_ZONE_TYPE_INDEX].trim() : "";

            // Fix #18: Settlements ahora es un record inmutable, construido en una
            // sola expresión con los campos normalizados precomputados.
            Settlements settlement = new Settlements(
                    settlementName,
                    zoneTypeVal,
                    settlementTypeVal,
                    Util.normalizeString(settlementName),
                    Util.normalizeString(settlementTypeVal),
                    Util.normalizeString(zoneTypeVal));

            zipCode.getSettlements().add(settlement);

            zipCodesByNormalizedEntity
                    .computeIfAbsent(normalizedEntity, k -> new HashSet<>())
                    .add(zipCode);

            zipCodesByNormalizedMunicipality
                    .computeIfAbsent(normalizedMunicipality, k -> new HashSet<>())
                    .add(zipCode);

            return true;

        } catch (Exception e) {
            handleError("Error procesando la linea: {}",
                line.substring(0, Math.min(100, line.length())));
            log.debug("Detalle del error:", e);
            return false;
        }
    }

    private void handleError(String message, Object... args) {
        errorCount++;
        if (errorCount <= MAX_ERRORS_THRESHOLD) {
            log.warn(message, args);
        } else if (errorCount == MAX_ERRORS_THRESHOLD + 1) {
            log.warn("Se alcanzo el limite de {} errores. Los siguientes errores no se mostraran.", MAX_ERRORS_THRESHOLD);
        }
    }

    private boolean isValidZipCode(String zipCode) {
        return zipCode != null && ZIP_CODE_PATTERN.matcher(zipCode).matches();
    }

    /**
     * @deprecated Usar la variante paginada {@link #searchByFederalEntity(String, int, int)}.
     * Este método materializa todos los ZipCodes coincidentes (que para una entidad como
     * "Ciudad de México" pueden ser ≥25 000) y los retiene en cache; el controlador
     * siempre llama a la versión paginada. Se mantiene sólo por compatibilidad de tests.
     */
    @Deprecated(forRemoval = false)
    @Cacheable(value = "federalEntitySearch", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm)")
    public List<ZipCode> searchByFederalEntity(String searchTerm) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("federal_entity");
            String normalizedSearchTerm = validateSearchTerm(searchTerm, "federal_entity");

            List<ZipCode> results = findOrderedCandidatesInIndex(
                    zipCodesByNormalizedEntity,
                    normalizedSearchTerm);

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para la entidad federativa: " + searchTerm
                );
            }

            metricsConfiguration.recordResultSize("federal_entity", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "federal_entity");
        }
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
            validatePagination(page, size);
            String normalizedSearchTerm = validateSearchTerm(searchTerm, "federal_entity");

            Set<ZipCode> matches = findCandidatesInIndex(zipCodesByNormalizedEntity, normalizedSearchTerm);
            if (matches.isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para la entidad federativa: " + searchTerm
                );
            }

            PagedResponse<ZipCode> response = paginateSorted(
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
     * @deprecated Usar la variante paginada {@link #searchByMunicipality(String, int, int)}.
     */
    @Deprecated(forRemoval = false)
    @Cacheable(value = "municipalitySearch", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm)")
    public List<ZipCode> searchByMunicipality(String searchTerm) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("municipality");
            String normalizedSearchTerm = validateSearchTerm(searchTerm, "municipality");

            List<ZipCode> results = findOrderedCandidatesInIndex(
                    zipCodesByNormalizedMunicipality,
                    normalizedSearchTerm);

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para el municipio: " + searchTerm
                );
            }

            metricsConfiguration.recordResultSize("municipality", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "municipality");
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
            validatePagination(page, size);
            String normalizedSearchTerm = validateSearchTerm(searchTerm, "municipality");

            Set<ZipCode> matches = findCandidatesInIndex(zipCodesByNormalizedMunicipality, normalizedSearchTerm);
            if (matches.isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para el municipio: " + searchTerm
                );
            }

            PagedResponse<ZipCode> response = paginateSorted(
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
     * Prefix search using ConcurrentSkipListMap.subMap() for O(log n + k) performance.
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
     */
    @Cacheable(value = "federalEntities")
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
     * @deprecated Usar la variante paginada {@link #advancedSearch(AdvancedSearchRequest, int, int)}.
     * Materializar toda la lista en cache puede tener un costo de memoria significativo.
     */
    @Deprecated(forRemoval = false)
    @Cacheable(
            value = "advancedSearch",
            key = "#request.normalizedFilterCacheKey()",
            // Fix #19: evita ocupar slot de cache con requests inválidos (null o
            // todos los filtros vacíos) que terminarán en IllegalArgumentException.
            condition = "#request != null && #request.hasAnyFilter()")
    public List<ZipCode> advancedSearch(AdvancedSearchRequest request) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            AdvancedSearchCriteria criteria = validateAndNormalizeAdvancedSearchRequest(request);
            Collection<ZipCode> candidates = resolveOrderedSearchCandidates(criteria);
            Predicate<ZipCode> filter = zipCode -> matchesAdvancedCriteria(zipCode, criteria);

            List<ZipCode> results = candidates.stream()
                    .filter(filter)
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("advanced", "not_found");
                throw new ZipCodeNotFoundException("No se encontraron codigos postales con los criterios especificados");
            }

            metricsConfiguration.recordResultSize("advanced", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "advanced");
        }
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
            validatePagination(page, size);
            AdvancedSearchCriteria criteria = validateAndNormalizeAdvancedSearchRequest(request);
            Collection<ZipCode> candidates = resolveOrderedSearchCandidates(criteria);

            PagedResponse<ZipCode> response = createPagedResponse(
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

    /**
     * Creates a paginated response from a list of results.
     * Uses long arithmetic to prevent overflow when page * size exceeds Integer.MAX_VALUE.
     */
    public static <T> PagedResponse<T> createPagedResponse(List<T> allResults, int page, int size) {
        validatePagination(page, size);

        int totalElements = allResults.size();
        int totalPages = calculateTotalPages(totalElements, size);
        long offset = (long) page * size;

        if (offset >= totalElements) {
            return buildPagedResponse(List.of(), page, size, totalElements, totalPages);
        }

        int start = (int) offset; // Safe: offset < totalElements which is an int
        int end = Math.min(start + size, totalElements);
        return buildPagedResponse(allResults.subList(start, end), page, size, totalElements, totalPages);
    }

    /**
     * Streaming pagination que ordena el conjunto candidato y materializa
     * únicamente la página solicitada. Fix #8.
     */
    private static <T> PagedResponse<T> paginateSorted(
            Set<T> candidates,
            Comparator<T> comparator,
            int page,
            int size) {
        validatePagination(page, size);

        int totalElements = candidates.size();
        int totalPages = calculateTotalPages(totalElements, size);
        long offset = (long) page * size;

        if (offset >= totalElements) {
            return buildPagedResponse(List.of(), page, size, totalElements, totalPages);
        }

        int start = (int) offset;
        int limit = Math.min(size, totalElements - start);
        List<T> pageContent = candidates.stream()
                .sorted(comparator)
                .skip(start)
                .limit(limit)
                .toList();

        return buildPagedResponse(pageContent, page, size, totalElements, totalPages);
    }

    /**
     * Creates a paginated response from a collection without materializing the full
     * filtered result set. The collection iteration order is preserved.
     */
    private static <T> PagedResponse<T> createPagedResponse(
            Collection<T> candidates,
            Predicate<T> filter,
            int page,
            int size) {
        validatePagination(page, size);

        long offset = (long) page * size;
        List<T> pageContent = new ArrayList<>(size);
        int totalElements = 0;

        for (T candidate : candidates) {
            if (!filter.test(candidate)) {
                continue;
            }

            if (totalElements >= offset && pageContent.size() < size) {
                pageContent.add(candidate);
            }
            totalElements++;
        }

        return buildPagedResponse(
                pageContent,
                page,
                size,
                totalElements,
                calculateTotalPages(totalElements, size));
    }

    private static <T> PagedResponse<T> buildPagedResponse(
            List<T> content,
            int page,
            int size,
            int totalElements,
            int totalPages) {
        return PagedResponse.<T>builder()
                .content(content)
                .pageNumber(page)
                .pageSize(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(totalPages == 0 || page >= totalPages - 1)
                .build();
    }

    private static int calculateTotalPages(int totalElements, int size) {
        return totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    private static void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("La pagina debe ser mayor o igual a 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("El tamaño debe ser mayor a 0");
        }
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

    /**
     * Devuelve todos los ZipCodes coincidentes (en orden ascendente por código
     * postal) recorriendo el índice invertido. {@code distinct()} es defensivo:
     * cada ZipCode aparece sólo bajo una clave por dimensión (porque cada CP
     * tiene una sola entidad/municipio), así que en práctica nunca hay
     * duplicados. Conservamos {@code distinct()} para que el contrato sea
     * robusto si en el futuro se admiten múltiples valores por ZipCode.
     */
    private List<ZipCode> findOrderedCandidatesInIndex(Map<String, Set<ZipCode>> index, String normalizedSearchTerm) {
        return index.entrySet().stream()
                .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                .flatMap(entry -> entry.getValue().stream())
                .distinct()
                .sorted(Comparator.comparing(ZipCode::getZipCode))
                .toList();
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
