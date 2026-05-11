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
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.List;

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

    private static final Pattern ZIP_CODE_PATTERN =
        Pattern.compile("^\\d{5}$");
    // Pre-compiled pattern for validating digit-only input (avoids recompiling on every partial search)
    private static final Pattern DIGITS_PATTERN = Pattern.compile("^\\d+$");

    // Thread-safe maps for concurrent access
    private final Map<String, ZipCode> zipCodesByCode = new ConcurrentHashMap<>();
    // Sorted map for O(log n) prefix searches instead of O(n) full scan
    private final ConcurrentSkipListMap<String, ZipCode> zipCodesSorted = new ConcurrentSkipListMap<>();
    // Inverted indices for fast searches by entity and municipality
    private final Map<String, Set<ZipCode>> zipCodesByNormalizedEntity = new ConcurrentHashMap<>();
    private final Map<String, Set<ZipCode>> zipCodesByNormalizedMunicipality = new ConcurrentHashMap<>();

    // Pre-computed statistics (immutable after load)
    private volatile ZipCodeStats cachedStats;
    // Pre-computed federal entities list (immutable after load)
    private volatile List<FederalEntity> cachedFederalEntities;

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

    // No @Cacheable needed: ConcurrentHashMap.get() is already O(1).
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

        } catch (IOException e) {
            log.error("Error al cargar los codigos postales", e);
        }
    }

    private void buildPreComputedData() {
        // Make all settlement lists immutable to prevent accidental mutation of internal state
        for (ZipCode zc : zipCodesByCode.values()) {
            if (zc.getSettlements() != null) {
                zc.setSettlements(List.copyOf(zc.getSettlements()));
            }
        }
        log.info("  - Listas de asentamientos convertidas a inmutables");

        // Pre-compute statistics once (data is immutable after load)
        long totalSettlements = zipCodesByCode.values().stream()
                .mapToLong(zc -> zc.getSettlements().size())
                .sum();

        cachedStats = ZipCodeStats.builder()
                .totalZipCodes(zipCodesByCode.size())
                .totalFederalEntities(zipCodesByNormalizedEntity.size())
                .totalMunicipalities(zipCodesByNormalizedMunicipality.size())
                .totalSettlements(totalSettlements)
                .build();

        // Pre-compute federal entities list
        Map<String, List<ZipCode>> zipCodesByEntity = zipCodesByCode.values().stream()
                .collect(Collectors.groupingBy(ZipCode::getFederalEntity));

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

        log.info("  - Estadisticas y entidades federativas pre-computadas");
    }

    private InputStream getInputStream() throws IOException {
        if (filePath != null && filePath.startsWith("classpath:")) {
            String resourcePath = filePath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                log.info("Cargando codigos postales desde classpath: {}", resourcePath);
                return resource.getInputStream();
            }
            log.warn("Recurso no encontrado en classpath: {}", resourcePath);
        }

        if (filePath != null && !filePath.startsWith("classpath:")) {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                log.info("Cargando codigos postales desde {}", filePath);
                return Files.newInputStream(path);
            }
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
            dataLoaded = true;

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

            Settlements settlement = new Settlements();
            String settlementName = words[COL_SETTLEMENT_NAME].trim();
            String settlementTypeVal = words[COL_SETTLEMENT_TYPE].trim();
            String zoneTypeVal = words.length > COL_ZONE_TYPE_INDEX ?
                words[COL_ZONE_TYPE_INDEX].trim() : "";
            
            settlement.setName(settlementName);
            settlement.setZoneType(zoneTypeVal);
            settlement.setSettlementType(settlementTypeVal);
            
            // Pre-compute normalized fields to avoid runtime normalization in searches
            settlement.setNormalizedName(Util.normalizeString(settlementName));
            settlement.setNormalizedSettlementType(Util.normalizeString(settlementTypeVal));
            settlement.setNormalizedZoneType(Util.normalizeString(zoneTypeVal));

            zipCode.getSettlements().add(settlement);

            zipCodesByNormalizedEntity
                    .computeIfAbsent(normalizedEntity, k -> ConcurrentHashMap.newKeySet())
                    .add(zipCode);

            zipCodesByNormalizedMunicipality
                    .computeIfAbsent(normalizedMunicipality, k -> ConcurrentHashMap.newKeySet())
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

    @Cacheable(value = "federalEntitySearch", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm)")
    public List<ZipCode> searchByFederalEntity(String searchTerm) {
        // Delegates to paginated version with default page 0 and full size
        return searchByFederalEntity(searchTerm, 0, Integer.MAX_VALUE).getContent();
    }

    /**
     * Paginated search by federal entity.
     * Uses skip/limit on the stream to avoid materializing the full list when pagination is applied.
     */
    @Cacheable(value = "federalEntitySearchPaged", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm) + '_' + #page + '_' + #size")
    public PagedResponse<ZipCode> searchByFederalEntity(String searchTerm, int page, int size) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("federal_entity");

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "empty_search");
                throw new IllegalArgumentException("El termino de busqueda no puede estar vacio");
            }

            String normalizedSearchTerm = Util.normalizeSearchTerm(searchTerm);

            // Sequential stream - only ~32 entries in the entity index
            List<ZipCode> results = zipCodesByNormalizedEntity.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                    .flatMap(entry -> entry.getValue().stream())
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para la entidad federativa: " + searchTerm
                );
            }

            metricsConfiguration.recordResultSize("federal_entity", results.size());

            // If no pagination requested (size == MAX_VALUE), return the full list directly
            if (size == Integer.MAX_VALUE) {
                return results;
            }

            return buildPagedResponse(results, page, size, "federal_entity");
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "federal_entity");
        }
    }

    @Cacheable(value = "municipalitySearch", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm)")
    public List<ZipCode> searchByMunicipality(String searchTerm) {
        // Delegates to paginated version with default page 0 and full size
        return searchByMunicipality(searchTerm, 0, Integer.MAX_VALUE).getContent();
    }

    /**
     * Paginated search by municipality.
     * Uses skip/limit on the stream to avoid materializing the full list when pagination is applied.
     */
    @Cacheable(value = "municipalitySearchPaged", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#searchTerm) + '_' + #page + '_' + #size")
    public PagedResponse<ZipCode> searchByMunicipality(String searchTerm, int page, int size) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordSearch("municipality");

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "empty_search");
                throw new IllegalArgumentException("El termino de busqueda no puede estar vacio");
            }

            String normalizedSearchTerm = Util.normalizeSearchTerm(searchTerm);

            // Sequential stream - municipality index has ~2500 entries
            List<ZipCode> results = zipCodesByNormalizedMunicipality.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                    .flatMap(entry -> entry.getValue().stream())
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales para el municipio: " + searchTerm
                );
            }

            metricsConfiguration.recordResultSize("municipality", results.size());

            // If no pagination requested (size == MAX_VALUE), return the full list directly
            if (size == Integer.MAX_VALUE) {
                return results;
            }

            return buildPagedResponse(results, page, size, "municipality");
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
     * Uses exclusive upper bound with incremented prefix for correct range matching.
     * 
     * For example, prefix "019" matches codes in range ["01900", "01999"].
     * The upper bound is computed by incrementing the last character: "019" -> "020",
     * then using subMap("019", "020") which gives us all codes starting with "019".
     */
    @Cacheable(value = "partialSearch", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#partialCode) + '_' + #limit")
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

            int effectiveLimit = Math.min(Math.max(limit, 1), 50);

            // O(log n + k) using sorted map range query.
            // The exclusive upper bound stays inside the requested prefix, so a search
            // like "0199" never leaks "020xx" rows.
            String fromKey = cleanCode;
            String toKey = computeUpperBound(cleanCode);

            List<ZipCode> results = zipCodesSorted.subMap(fromKey, true, toKey, false)
                    .values().stream()
                    .limit(effectiveLimit)
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("partial", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron codigos postales que inicien con: " + partialCode
                );
            }

            metricsConfiguration.recordResultSize("partial", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "partial");
        }
    }

    /**
     * Computes the exclusive upper bound for prefix-based range queries.
     *
     * Appending the maximum Unicode character creates a key that is greater than
     * every digit-only zip code that starts with the requested prefix, without
     * including the next numeric range. For example, range ["0199", "0199\uFFFF")
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

    @Cacheable(value = "municipalitiesByEntity", key = "T(com.coderalexis.CodigoPostalApi.util.Util).normalizeCacheKey(#federalEntity)")
    public List<String> getMunicipalitiesByFederalEntity(String federalEntity) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            if (federalEntity == null || federalEntity.trim().isEmpty()) {
                throw new IllegalArgumentException("La entidad federativa no puede estar vacia");
            }

            String normalizedSearchTerm = Util.normalizeSearchTerm(federalEntity);

            // Sequential stream - only ~32 entity keys to filter
            List<String> municipalities = zipCodesByNormalizedEntity.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                    .flatMap(entry -> entry.getValue().stream())
                    .map(ZipCode::getMunicipality)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            if (municipalities.isEmpty()) {
                throw new ZipCodeNotFoundException(
                        "No se encontraron municipios para la entidad federativa: " + federalEntity
                );
            }

            metricsConfiguration.recordResultSize("municipalities_by_entity", municipalities.size());
            return municipalities;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "municipalities_by_entity");
        }
    }

    public List<Settlements> getSettlementsByZipCode(String zipcode) {
        ZipCode zipCode = getZipCode(zipcode);
        return zipCode.getSettlements();
    }

    /**
     * Advanced search using inverted indices as starting point when possible.
     * Uses pre-computed normalized fields to avoid runtime normalization.
     */
    @Cacheable(value = "advancedSearch", key = "#request.normalizedFilterCacheKey()")
    public List<ZipCode> advancedSearch(AdvancedSearchRequest request) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            if ((request.getFederalEntity() == null || request.getFederalEntity().isBlank()) &&
                (request.getMunicipality() == null || request.getMunicipality().isBlank()) &&
                (request.getSettlement() == null || request.getSettlement().isBlank()) &&
                (request.getSettlementType() == null || request.getSettlementType().isBlank()) &&
                (request.getZoneType() == null || request.getZoneType().isBlank())) {
                throw new IllegalArgumentException("Debe proporcionar al menos un criterio de busqueda");
            }

            String normalizedEntity = request.getFederalEntity() != null ?
                    Util.normalizeSearchTerm(request.getFederalEntity()) : null;
            String normalizedMunicipality = request.getMunicipality() != null ?
                    Util.normalizeSearchTerm(request.getMunicipality()) : null;
            String normalizedSettlement = request.getSettlement() != null ?
                    Util.normalizeSearchTerm(request.getSettlement()) : null;
            String normalizedSettlementType = request.getSettlementType() != null ?
                    Util.normalizeSearchTerm(request.getSettlementType()) : null;
            String normalizedZoneType = request.getZoneType() != null ?
                    Util.normalizeSearchTerm(request.getZoneType()) : null;

            // Use inverted indices as starting point to reduce scan scope
            Collection<ZipCode> candidates = resolveSearchCandidates(normalizedEntity, normalizedMunicipality);

            List<ZipCode> results = candidates.stream()
                    .filter(zipCode -> {
                        // Filter by entity using pre-computed normalized field
                        if (normalizedEntity != null && !normalizedEntity.isEmpty()) {
                            if (!zipCode.getNormalizedFederalEntity().contains(normalizedEntity)) {
                                return false;
                            }
                        }

                        // Filter by municipality using pre-computed normalized field
                        if (normalizedMunicipality != null && !normalizedMunicipality.isEmpty()) {
                            if (!zipCode.getNormalizedMunicipality().contains(normalizedMunicipality)) {
                                return false;
                            }
                        }

                        // Filter by settlement fields using pre-computed normalized fields
                        if ((normalizedSettlement != null && !normalizedSettlement.isEmpty()) ||
                            (normalizedSettlementType != null && !normalizedSettlementType.isEmpty()) ||
                            (normalizedZoneType != null && !normalizedZoneType.isEmpty())) {

                            boolean hasMatchingSettlement = zipCode.getSettlements().stream()
                                    .anyMatch(settlement -> {
                                        boolean matches = true;

                                        if (normalizedSettlement != null && !normalizedSettlement.isEmpty()) {
                                            // Use pre-computed normalized field instead of runtime normalization
                                            String settlementName = settlement.getNormalizedName() != null 
                                                    ? settlement.getNormalizedName() 
                                                    : Util.normalizeString(settlement.getName());
                                            matches = settlementName.contains(normalizedSettlement);
                                        }

                                        if (matches && normalizedSettlementType != null && !normalizedSettlementType.isEmpty()) {
                                            // Use pre-computed normalized field instead of runtime normalization
                                            String type = settlement.getNormalizedSettlementType() != null 
                                                    ? settlement.getNormalizedSettlementType() 
                                                    : Util.normalizeString(settlement.getSettlementType());
                                            matches = type.contains(normalizedSettlementType);
                                        }

                                        if (matches && normalizedZoneType != null && !normalizedZoneType.isEmpty()) {
                                            // Use pre-computed normalized field instead of runtime normalization
                                            String zone = settlement.getNormalizedZoneType() != null 
                                                    ? settlement.getNormalizedZoneType() 
                                                    : Util.normalizeString(settlement.getZoneType());
                                            matches = zone.contains(normalizedZoneType);
                                        }

                                        return matches;
                                    });

                            if (!hasMatchingSettlement) {
                                return false;
                            }
                        }

                        return true;
                    })
                    .sorted(Comparator.comparing(ZipCode::getZipCode))
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
     * Creates a paginated response from a list of results.
     * Uses long arithmetic to prevent overflow when page * size exceeds Integer.MAX_VALUE.
     * 
     * @param allResults All results (already loaded in memory)
     * @param page Page number (0-based)
     * @param size Page size
     * @param <T> Result type
     * @return PaginatedResponse with the sliced results (empty if page exceeds bounds)
     */
    public static <T> PagedResponse<T> createPagedResponse(List<T> allResults, int page, int size) {
        int totalElements = allResults.size();
        
        // Prevent overflow: use long arithmetic for the offset calculation
        long offset = Math.multiplyExact((long) page, (long) size);
        
        // If offset exceeds total elements, return empty page
        if (offset >= totalElements) {
            int totalPages = totalElements > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
            return PagedResponse.<T>builder()
                    .content(List.of())
                    .pageNumber(page)
                    .pageSize(size)
                    .totalElements(totalElements)
                    .totalPages(totalPages)
                    .first(false)
                    .last(true)
                    .build();
        }

        int start = (int) offset; // Safe: offset < totalElements which is an int
        int end = Math.min(start + size, totalElements);

        List<T> pagedResults = (start < end && totalElements > 0) 
                ? allResults.subList(start, end) 
                : List.of();

        return PagedResponse.<T>builder()
                .content(pagedResults)
                .pageNumber(page)
                .pageSize(size)
                .totalElements(totalElements)
                .totalPages((int) Math.ceil((double) totalElements / size))
                .first(page == 0)
                .last(page >= ((int) Math.ceil((double) totalElements / size)) - 1)
                .build();
    }

    /**
     * Builds a paginated response from pre-computed results, recording metrics.
     */
    private <T> PagedResponse<T> buildPagedResponse(List<T> allResults, int page, int size, String searchType) {
        metricsConfiguration.recordResultSize(searchType, allResults.size());
        return createPagedResponse(allResults, page, size);
    }

    /**
     * Resolves the smallest candidate set using available inverted indices.
     *
     * If an indexed filter is present but has no matches, it returns an empty
     * candidate set immediately because advanced-search filters are combined with
     * AND semantics. This avoids a full catalog scan for impossible entity or
     * municipality criteria.
     */
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

        // Fallback: full scan only when filtering by settlement/type/zone.
        return zipCodesByCode.values();
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
