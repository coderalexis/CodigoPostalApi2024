package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.config.MetricsConfiguration;
import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
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
        Path path = Paths.get(filePath);

        if (Files.exists(path)) {
            log.info("Cargando códigos postales desde {}", filePath);
            return Files.newInputStream(path);
        }

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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            // Validar encoding leyendo primeras líneas
            String firstLine = reader.readLine();
            if (firstLine != null && !isValidUtf8Content(firstLine)) {
                log.warn("El archivo podría no estar en UTF-8. Algunos caracteres podrían no mostrarse correctamente.");
            }

            // Saltar la segunda línea (header)
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

    private boolean isValidUtf8Content(String content) {
        // Verificar si contiene caracteres típicos del español que indican UTF-8 correcto
        return !content.contains("�") && !content.contains("Ã");
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
}