package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.config.MetricsConfiguration;
import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.AdvancedSearchRequest;
import com.coderalexis.CodigoPostalApi.model.FederalEntity;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ZipCodeService {
    private static final String LINE_SEPARATOR = Pattern.quote("|");

    // Column indices based on CPdescarga.txt structure:
    // d_codigo|d_asenta|d_tipo_asenta|D_mnpio|d_estado|d_ciudad|d_CP|c_estado|c_oficina|c_CP|c_tipo_asenta|c_mnpio|id_asenta_cpcons|d_zona|c_cve_ciudad
    private static final int COL_ZIP_CODE = 0;
    private static final int COL_SETTLEMENT_NAME = 1;
    private static final int COL_SETTLEMENT_TYPE = 2;
    private static final int COL_MUNICIPALITY = 3;
    private static final int COL_FEDERAL_ENTITY = 4;
    private static final int COL_LOCALITY = 5;
    private static final int COL_ZONE_TYPE_INDEX = 13;
    private static final int MIN_COLUMNS = 6;
    private static final int MAX_ERRORS_THRESHOLD = 100;

    private static final java.util.regex.Pattern ZIP_CODE_PATTERN =
        java.util.regex.Pattern.compile("^\\d{5}$");

    // Thread-safe maps for concurrent access
    private final Map<String, ZipCode> zipCodesByCode = new ConcurrentHashMap<>();
    // Inverted indices for fast searches by entity and municipality
    private final Map<String, Set<ZipCode>> zipCodesByNormalizedEntity = new ConcurrentHashMap<>();
    private final Map<String, Set<ZipCode>> zipCodesByNormalizedMunicipality = new ConcurrentHashMap<>();

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

    @Cacheable(value = "zipcodes", key = "#zipcode")
    public ZipCode getZipCode(String zipcode) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordZipCodeSearch(zipcode);
            ZipCode zipCode = zipCodesByCode.get(zipcode);
            if (zipCode == null) {
                metricsConfiguration.recordSearchError("direct", "not_found");
                throw new ZipCodeNotFoundException("Código postal no encontrado: " + zipcode);
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
                log.error("No se pudo cargar ningún archivo de códigos postales");
                return;
            }

            processZipCodeFile(stream);

        } catch (IOException e) {
            log.error("Error al cargar los códigos postales", e);
        }
    }

    private InputStream getInputStream() throws IOException {
        // Si el path empieza con "classpath:", cargar desde recursos internos
        if (filePath != null && filePath.startsWith("classpath:")) {
            String resourcePath = filePath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                log.info("Cargando códigos postales desde classpath: {}", resourcePath);
                return resource.getInputStream();
            }
            log.warn("Recurso no encontrado en classpath: {}", resourcePath);
        }

        // Intentar cargar desde sistema de archivos
        if (filePath != null && !filePath.startsWith("classpath:")) {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                log.info("Cargando códigos postales desde {}", filePath);
                return Files.newInputStream(path);
            }
        }

        // Fallback: intentar cargar desde recursos internos con nombre por defecto
        ClassPathResource resource = new ClassPathResource(RESOURCE_FILE);
        if (resource.exists()) {
            log.info("Cargando códigos postales desde recurso interno {}", RESOURCE_FILE);
            return resource.getInputStream();
        }

        return null;
    }

    private void processZipCodeFile(InputStream stream) throws IOException {
        long startTime = System.currentTimeMillis();
        errorCount = 0;

        // El archivo del SEPOMEX viene en ISO-8859-1 (Latin-1), no UTF-8
        // Usamos BufferedInputStream para poder detectar el encoding sin perder datos
        BufferedInputStream bufferedStream = new BufferedInputStream(stream);
        Charset charset = detectCharset(bufferedStream);
        log.info("Encoding detectado: {}", charset.name());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(bufferedStream, charset))) {

            // Saltar las primeras dos líneas (metadata y header)
            reader.readLine();
            reader.readLine();

            long linesProcessed = reader.lines()
                    .filter(this::processLine)
                    .count();

            long duration = System.currentTimeMillis() - startTime;
            dataLoaded = true;

            log.info("✓ Datos cargados exitosamente en {}ms", duration);
            log.info("  - Códigos postales únicos: {}", zipCodesByCode.size());
            log.info("  - Entidades federativas: {}", zipCodesByNormalizedEntity.size());
            log.info("  - Municipios: {}", zipCodesByNormalizedMunicipality.size());
            log.info("  - Líneas procesadas: {}", linesProcessed);
            if (errorCount > 0) {
                log.warn("  - Líneas con errores: {}", errorCount);
            }
        }
    }

    /**
     * Detecta el charset del archivo probando con las codificaciones más comunes.
     * El archivo del SEPOMEX generalmente viene en ISO-8859-1 (Latin-1).
     * Usa mark/reset para no consumir el stream.
     */
    private Charset detectCharset(BufferedInputStream stream) throws IOException {
        // Marcar la posición actual para poder volver después de leer
        stream.mark(4096);

        // Leer los primeros bytes para analizar
        byte[] sample = new byte[4096];
        int bytesRead = stream.read(sample);

        // Volver al inicio del stream
        stream.reset();

        if (bytesRead <= 0) {
            return StandardCharsets.UTF_8;
        }

        // Buscar bytes que indican ISO-8859-1 (caracteres acentuados en rango 0x80-0xFF)
        // En ISO-8859-1: á=0xE1, é=0xE9, í=0xED, ó=0xF3, ú=0xFA, ñ=0xF1
        boolean hasHighBytes = false;
        boolean hasUtf8Sequences = false;

        for (int i = 0; i < bytesRead; i++) {
            int b = sample[i] & 0xFF;
            if (b >= 0x80) {
                hasHighBytes = true;
                // Verificar si parece una secuencia UTF-8 válida (empieza con 110xxxxx o 1110xxxx)
                if ((b & 0xE0) == 0xC0 || (b & 0xF0) == 0xE0) {
                    // Verificar el siguiente byte (debe ser 10xxxxxx)
                    if (i + 1 < bytesRead && (sample[i + 1] & 0xC0) == 0x80) {
                        hasUtf8Sequences = true;
                    }
                }
            }
        }

        // Si hay bytes altos pero no parecen secuencias UTF-8 válidas, es ISO-8859-1
        if (hasHighBytes && !hasUtf8Sequences) {
            log.debug("Detectado encoding ISO-8859-1 (bytes altos sin secuencias UTF-8)");
            return StandardCharsets.ISO_8859_1;
        }

        // Si hay secuencias UTF-8 válidas, usar UTF-8
        if (hasUtf8Sequences) {
            log.debug("Detectado encoding UTF-8 (secuencias UTF-8 válidas encontradas)");
            return StandardCharsets.UTF_8;
        }

        // Por defecto para archivos del SEPOMEX, usar ISO-8859-1
        log.debug("Usando encoding por defecto ISO-8859-1 para archivo SEPOMEX");
        return StandardCharsets.ISO_8859_1;
    }

    private boolean processLine(String line) {
        try {
            String[] words = line.split(LINE_SEPARATOR);

            if (words.length < MIN_COLUMNS) {
                handleError("Línea con formato incorrecto (columnas insuficientes): {}",
                    line.substring(0, Math.min(100, line.length())));
                return false;
            }

            String zipCodeKey = words[COL_ZIP_CODE].trim();

            if (!isValidZipCode(zipCodeKey)) {
                handleError("Código postal inválido '{}' en línea: {}",
                    zipCodeKey, line.substring(0, Math.min(100, line.length())));
                return false;
            }

            if (words[COL_FEDERAL_ENTITY].trim().isEmpty() ||
                words[COL_MUNICIPALITY].trim().isEmpty()) {
                handleError("Campos requeridos vacíos en código postal: {}", zipCodeKey);
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
                z.setSettlements(new ArrayList<>());
                return z;
            });

            Settlements settlement = new Settlements();
            settlement.setName(words[COL_SETTLEMENT_NAME].trim());
            settlement.setZoneType(words.length > COL_ZONE_TYPE_INDEX ?
                words[COL_ZONE_TYPE_INDEX].trim() : "");
            settlement.setSettlementType(words[COL_SETTLEMENT_TYPE].trim());

            zipCode.getSettlements().add(settlement);

            // Actualizar índices con referencias directas (evita lookups en búsquedas)
            zipCodesByNormalizedEntity
                    .computeIfAbsent(normalizedEntity, k -> ConcurrentHashMap.newKeySet())
                    .add(zipCode);

            zipCodesByNormalizedMunicipality
                    .computeIfAbsent(normalizedMunicipality, k -> ConcurrentHashMap.newKeySet())
                    .add(zipCode);

            return true;

        } catch (Exception e) {
            handleError("Error procesando la línea: {}",
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
            log.warn("Se alcanzó el límite de {} errores. Los siguientes errores no se mostrarán.", MAX_ERRORS_THRESHOLD);
        }
    }

    private boolean isValidZipCode(String zipCode) {
        return zipCode != null && ZIP_CODE_PATTERN.matcher(zipCode).matches();
    }

    @Cacheable(value = "federalEntitySearch", key = "#searchTerm.toLowerCase()")
    public List<ZipCode> searchByFederalEntity(String searchTerm) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordFederalEntitySearch(searchTerm);

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "empty_search");
                throw new IllegalArgumentException("El término de búsqueda no puede estar vacío");
            }

            String normalizedSearchTerm = Util.normalizeString(searchTerm.trim());

            // Búsqueda eficiente usando índice con referencias directas (sin lookup adicional)
            // No necesita distinct() porque usamos Set<ZipCode> que garantiza unicidad
            List<ZipCode> results = zipCodesByNormalizedEntity.entrySet().parallelStream()
                    .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                    .flatMap(entry -> entry.getValue().stream())
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("federal_entity", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron códigos postales para la entidad federativa: " + searchTerm
                );
            }

            metricsConfiguration.recordResultSize("federal_entity", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "federal_entity");
        }
    }

    @Cacheable(value = "municipalitySearch", key = "#searchTerm.toLowerCase()")
    public List<ZipCode> searchByMunicipality(String searchTerm) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordMunicipalitySearch(searchTerm);

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "empty_search");
                throw new IllegalArgumentException("El término de búsqueda no puede estar vacío");
            }

            String normalizedSearchTerm = Util.normalizeString(searchTerm.trim());

            // Búsqueda eficiente usando índice con referencias directas (sin lookup adicional)
            // No necesita distinct() porque usamos Set<ZipCode> que garantiza unicidad
            List<ZipCode> results = zipCodesByNormalizedMunicipality.entrySet().parallelStream()
                    .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                    .flatMap(entry -> entry.getValue().stream())
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("municipality", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron códigos postales para el municipio: " + searchTerm
                );
            }

            metricsConfiguration.recordResultSize("municipality", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "municipality");
        }
    }

    public ZipCodeStats getStatistics() {
        long totalSettlements = zipCodesByCode.values().stream()
                .mapToLong(zc -> zc.getSettlements().size())
                .sum();

        return ZipCodeStats.builder()
                .totalZipCodes(zipCodesByCode.size())
                .totalFederalEntities(zipCodesByNormalizedEntity.size())
                .totalMunicipalities(zipCodesByNormalizedMunicipality.size())
                .totalSettlements(totalSettlements)
                .build();
    }

    /**
     * Búsqueda parcial de códigos postales.
     * Permite buscar con códigos incompletos (ej: "010" encuentra "01000", "01010", etc.)
     *
     * @param partialCode Código postal parcial (mínimo 1 dígito)
     * @param limit Número máximo de resultados (default 10, max 50)
     * @return Lista de códigos postales que coinciden con el prefijo
     */
    @Cacheable(value = "partialSearch", key = "#partialCode + '_' + #limit")
    public List<ZipCode> searchByPartialCode(String partialCode, int limit) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            metricsConfiguration.recordZipCodeSearch("partial:" + partialCode);

            if (partialCode == null || partialCode.trim().isEmpty()) {
                metricsConfiguration.recordSearchError("partial", "empty_search");
                throw new IllegalArgumentException("El código postal parcial no puede estar vacío");
            }

            String cleanCode = partialCode.trim();

            // Validar que solo contenga dígitos
            if (!cleanCode.matches("\\d+")) {
                metricsConfiguration.recordSearchError("partial", "invalid_format");
                throw new IllegalArgumentException("El código postal solo debe contener dígitos");
            }

            // Limitar el tamaño del resultado
            int effectiveLimit = Math.min(Math.max(limit, 1), 50);

            List<ZipCode> results = zipCodesByCode.entrySet().parallelStream()
                    .filter(entry -> entry.getKey().startsWith(cleanCode))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(ZipCode::getZipCode))
                    .limit(effectiveLimit)
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                metricsConfiguration.recordSearchError("partial", "not_found");
                throw new ZipCodeNotFoundException(
                        "No se encontraron códigos postales que inicien con: " + partialCode
                );
            }

            metricsConfiguration.recordResultSize("partial", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "partial");
        }
    }

    /**
     * Obtiene la lista de todas las entidades federativas (estados) de México.
     *
     * @return Lista de entidades federativas con sus estadísticas
     */
    @Cacheable(value = "federalEntities")
    public List<FederalEntity> getAllFederalEntities() {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            // Agrupar códigos postales por entidad federativa original (no normalizada)
            Map<String, List<ZipCode>> zipCodesByEntity = zipCodesByCode.values().stream()
                    .collect(Collectors.groupingBy(ZipCode::getFederalEntity));

            List<FederalEntity> entities = zipCodesByEntity.entrySet().stream()
                    .map(entry -> {
                        String entityName = entry.getKey();
                        List<ZipCode> zipCodes = entry.getValue();

                        // Contar municipios únicos para esta entidad
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
                    .collect(Collectors.toList());

            metricsConfiguration.recordResultSize("federal_entities", entities.size());
            return entities;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "federal_entities");
        }
    }

    /**
     * Obtiene la lista de municipios de una entidad federativa específica.
     *
     * @param federalEntity Nombre de la entidad federativa
     * @return Lista de nombres de municipios
     */
    @Cacheable(value = "municipalitiesByEntity", key = "#federalEntity.toLowerCase()")
    public List<String> getMunicipalitiesByFederalEntity(String federalEntity) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            if (federalEntity == null || federalEntity.trim().isEmpty()) {
                throw new IllegalArgumentException("La entidad federativa no puede estar vacía");
            }

            String normalizedSearchTerm = Util.normalizeString(federalEntity.trim());

            // Buscar la entidad y obtener sus municipios únicos
            List<String> municipalities = zipCodesByNormalizedEntity.entrySet().parallelStream()
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

    /**
     * Obtiene las colonias/asentamientos de un código postal específico.
     *
     * @param zipcode Código postal de 5 dígitos
     * @return Lista de asentamientos
     */
    public List<Settlements> getSettlementsByZipCode(String zipcode) {
        ZipCode zipCode = getZipCode(zipcode);
        return zipCode.getSettlements();
    }

    /**
     * Búsqueda avanzada con múltiples filtros.
     *
     * @param request Objeto con los filtros de búsqueda
     * @return Lista de códigos postales que coinciden con todos los filtros
     */
    @Cacheable(value = "advancedSearch", key = "#request.toString()")
    public List<ZipCode> advancedSearch(AdvancedSearchRequest request) {
        Timer.Sample sample = metricsConfiguration.startTimer();
        try {
            // Validar que al menos un filtro esté presente
            if ((request.getFederalEntity() == null || request.getFederalEntity().isBlank()) &&
                (request.getMunicipality() == null || request.getMunicipality().isBlank()) &&
                (request.getSettlement() == null || request.getSettlement().isBlank()) &&
                (request.getSettlementType() == null || request.getSettlementType().isBlank()) &&
                (request.getZoneType() == null || request.getZoneType().isBlank())) {
                throw new IllegalArgumentException("Debe proporcionar al menos un criterio de búsqueda");
            }

            // Normalizar términos de búsqueda
            String normalizedEntity = request.getFederalEntity() != null ?
                    Util.normalizeString(request.getFederalEntity().trim()) : null;
            String normalizedMunicipality = request.getMunicipality() != null ?
                    Util.normalizeString(request.getMunicipality().trim()) : null;
            String normalizedSettlement = request.getSettlement() != null ?
                    Util.normalizeString(request.getSettlement().trim()) : null;
            String normalizedSettlementType = request.getSettlementType() != null ?
                    Util.normalizeString(request.getSettlementType().trim()) : null;
            String normalizedZoneType = request.getZoneType() != null ?
                    Util.normalizeString(request.getZoneType().trim()) : null;

            List<ZipCode> results = zipCodesByCode.values().parallelStream()
                    .filter(zipCode -> {
                        // Filtrar por entidad federativa
                        if (normalizedEntity != null && !normalizedEntity.isEmpty()) {
                            String zipEntity = Util.normalizeString(zipCode.getFederalEntity());
                            if (!zipEntity.contains(normalizedEntity)) {
                                return false;
                            }
                        }

                        // Filtrar por municipio
                        if (normalizedMunicipality != null && !normalizedMunicipality.isEmpty()) {
                            String zipMunicipality = Util.normalizeString(zipCode.getMunicipality());
                            if (!zipMunicipality.contains(normalizedMunicipality)) {
                                return false;
                            }
                        }

                        // Filtrar por asentamiento, tipo de asentamiento o tipo de zona
                        if ((normalizedSettlement != null && !normalizedSettlement.isEmpty()) ||
                            (normalizedSettlementType != null && !normalizedSettlementType.isEmpty()) ||
                            (normalizedZoneType != null && !normalizedZoneType.isEmpty())) {

                            boolean hasMatchingSettlement = zipCode.getSettlements().stream()
                                    .anyMatch(settlement -> {
                                        boolean matches = true;

                                        if (normalizedSettlement != null && !normalizedSettlement.isEmpty()) {
                                            String settlementName = Util.normalizeString(settlement.getName());
                                            matches = settlementName.contains(normalizedSettlement);
                                        }

                                        if (matches && normalizedSettlementType != null && !normalizedSettlementType.isEmpty()) {
                                            String type = Util.normalizeString(settlement.getSettlementType());
                                            matches = type.contains(normalizedSettlementType);
                                        }

                                        if (matches && normalizedZoneType != null && !normalizedZoneType.isEmpty()) {
                                            String zone = Util.normalizeString(settlement.getZoneType());
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
                throw new ZipCodeNotFoundException("No se encontraron códigos postales con los criterios especificados");
            }

            metricsConfiguration.recordResultSize("advanced", results.size());
            return results;
        } finally {
            metricsConfiguration.recordSearchDuration(sample, "advanced");
        }
    }
}