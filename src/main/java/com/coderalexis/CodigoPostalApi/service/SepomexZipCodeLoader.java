package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.util.Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Carga y parseo del catálogo SEPOMEX ({@code CPdescarga.txt}). Extraído de
 * {@code ZipCodeService} (#10) para que esa clase quede enfocada en las consultas;
 * aquí vive todo lo relativo a I/O, detección de encoding y construcción de los
 * índices a partir del archivo.
 *
 * <p>El resultado ({@link ZipCodeData}) contiene {@link ZipCode} ya inmutables:
 * primero se acumulan los asentamientos de cada código postal en una estructura
 * temporal y luego se construye el objeto definitivo, evitando exponer estado
 * mutable.</p>
 *
 * <p>Durante la lectura se calcula, en una sola pasada, un checksum SHA-256 del
 * archivo (frescura de datos) y se canonizan los Strings de baja cardinalidad
 * (entidad, municipio, localidad, tipos) para reducir el footprint de memoria.</p>
 */
@Slf4j
@Component
public class SepomexZipCodeLoader {

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
    private static final int CHARSET_SAMPLE_SIZE = 4096;

    private static final Pattern ZIP_CODE_PATTERN = Pattern.compile("^\\d{5}$");

    private static final String RESOURCE_FILE = "CPdescarga.txt";

    @Value("${zipcode.file.path}")
    private String filePath;

    private int errorCount = 0;

    /**
     * Carga el catálogo y devuelve las cuatro estructuras de índice ya construidas,
     * junto con la metadata de frescura (checksum y origen).
     *
     * @return los índices + metadata, o {@code null} si no se pudo localizar ningún archivo.
     * @throws IOException si ocurre un error de lectura sobre un origen existente.
     */
    public ZipCodeData load() throws IOException {
        ResolvedSource resolved = resolveSource();
        if (resolved == null) {
            return null;
        }
        try (InputStream stream = resolved.stream()) {
            return parse(stream, resolved.description());
        }
    }

    private ZipCodeData parse(InputStream stream, String source) throws IOException {
        long startTime = System.currentTimeMillis();
        errorCount = 0;

        BufferedInputStream bufferedStream = new BufferedInputStream(stream);
        Charset charset = detectCharset(bufferedStream);
        log.info("Encoding detectado: {}", charset.name());

        MessageDigest digest = newSha256();
        // Se envuelve DESPUÉS de detectCharset (que hace mark/reset sobre los primeros
        // bytes), de modo que cada byte del archivo se digiere exactamente una vez.
        DigestInputStream digestStream = new DigestInputStream(bufferedStream, digest);

        // Estructuras temporales mutables: acumulan asentamientos por CP y canonizan
        // los Strings repetidos. Ambas se descartan al terminar el parseo.
        Map<String, Accumulator> accumulators = new HashMap<>();
        Map<String, String> stringPool = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(digestStream, charset))) {

            // Skip first two lines (metadata and header)
            reader.readLine();
            reader.readLine();

            long linesProcessed = reader.lines()
                    .filter(line -> processLine(line, accumulators, stringPool))
                    .count();

            String checksum = HexFormat.of().formatHex(digest.digest());
            ZipCodeData data = buildIndices(accumulators, checksum, source);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Datos cargados exitosamente en {}ms", duration);
            log.info("  - Codigos postales unicos: {}", data.byCode().size());
            log.info("  - Entidades federativas: {}", data.byNormalizedEntity().size());
            log.info("  - Municipios: {}", data.byNormalizedMunicipality().size());
            log.info("  - Lineas procesadas: {}", linesProcessed);
            log.info("  - Origen: {} | checksum SHA-256: {}", source, checksum);
            if (errorCount > 0) {
                log.warn("  - Lineas con errores: {}", errorCount);
            }

            return data;
        }
    }

    private ZipCodeData buildIndices(Map<String, Accumulator> accumulators, String checksum, String source) {
        Map<String, ZipCode> byCode = new HashMap<>(accumulators.size() * 2);
        NavigableMap<String, ZipCode> sorted = new TreeMap<>();
        Map<String, Set<ZipCode>> byNormalizedEntity = new HashMap<>();
        Map<String, Set<ZipCode>> byNormalizedMunicipality = new HashMap<>();

        for (Accumulator acc : accumulators.values()) {
            ZipCode zipCode = acc.toImmutable();

            byCode.put(zipCode.getZipCode(), zipCode);
            sorted.put(zipCode.getZipCode(), zipCode);
            byNormalizedEntity
                    .computeIfAbsent(zipCode.getNormalizedFederalEntity(), k -> new HashSet<>())
                    .add(zipCode);
            byNormalizedMunicipality
                    .computeIfAbsent(zipCode.getNormalizedMunicipality(), k -> new HashSet<>())
                    .add(zipCode);
        }

        return new ZipCodeData(byCode, sorted, byNormalizedEntity, byNormalizedMunicipality, checksum, source);
    }

    /**
     * Resuelve el origen del catálogo SEPOMEX (stream + descripción legible).
     *
     * <p>Fix #20: si el usuario configura un path explícito (vía
     * {@code zipcode.file.path}) y éste no existe, fallamos rápido en vez de
     * caer silenciosamente al recurso interno, que enmascaraba problemas de
     * despliegue (config incorrecta sin error visible).</p>
     */
    private ResolvedSource resolveSource() throws IOException {
        boolean userConfiguredPath = filePath != null && !filePath.isBlank();

        if (userConfiguredPath && filePath.startsWith("classpath:")) {
            String resourcePath = filePath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                log.info("Cargando codigos postales desde classpath: {}", resourcePath);
                return new ResolvedSource(resource.getInputStream(), "classpath:" + resourcePath);
            }
            throw new IOException(
                "Recurso configurado en 'zipcode.file.path' no encontrado en classpath: " + resourcePath);
        }

        if (userConfiguredPath) {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                log.info("Cargando codigos postales desde {}", filePath);
                return new ResolvedSource(Files.newInputStream(path), path.toString());
            }
            throw new IOException(
                "Archivo configurado en 'zipcode.file.path' no encontrado: " + filePath);
        }

        ClassPathResource resource = new ClassPathResource(RESOURCE_FILE);
        if (resource.exists()) {
            log.info("Cargando codigos postales desde recurso interno {}", RESOURCE_FILE);
            return new ResolvedSource(resource.getInputStream(), "classpath:" + RESOURCE_FILE);
        }

        return null;
    }

    private Charset detectCharset(BufferedInputStream stream) throws IOException {
        stream.mark(CHARSET_SAMPLE_SIZE);
        byte[] sample = new byte[CHARSET_SAMPLE_SIZE];
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

    private boolean processLine(String line, Map<String, Accumulator> accumulators, Map<String, String> pool) {
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

            // El primer registro de un código postal fija entidad/municipio/localidad;
            // los siguientes sólo añaden asentamientos. Los Strings de baja cardinalidad
            // se canonizan (#4) para que las ~145k entradas compartan las mismas instancias.
            Accumulator acc = accumulators.computeIfAbsent(zipCodeKey, k -> new Accumulator(
                    k,
                    canonical(words[COL_LOCALITY].trim(), pool),
                    canonical(federalEntity, pool),
                    canonical(municipality, pool),
                    canonical(Util.normalizeString(federalEntity), pool),
                    canonical(Util.normalizeString(municipality), pool)));

            String settlementName = words[COL_SETTLEMENT_NAME].trim();
            String settlementTypeVal = words[COL_SETTLEMENT_TYPE].trim();
            String zoneTypeVal = words.length > COL_ZONE_TYPE_INDEX ?
                words[COL_ZONE_TYPE_INDEX].trim() : "";

            // Fix #18: Settlements es un record inmutable con los campos normalizados precomputados.
            // El nombre del asentamiento es de alta cardinalidad y no se canoniza; el tipo de
            // asentamiento y el tipo de zona sí (apenas unas decenas de valores distintos).
            acc.settlements.add(new Settlements(
                    settlementName,
                    canonical(zoneTypeVal, pool),
                    canonical(settlementTypeVal, pool),
                    Util.normalizeString(settlementName),
                    canonical(Util.normalizeString(settlementTypeVal), pool),
                    canonical(Util.normalizeString(zoneTypeVal), pool)));

            return true;

        } catch (Exception e) {
            handleError("Error procesando la linea: {}",
                line.substring(0, Math.min(100, line.length())));
            log.debug("Detalle del error:", e);
            return false;
        }
    }

    /**
     * Devuelve la instancia canónica del String dado, de modo que valores
     * repetidos (entidad, municipio, tipos) compartan un único objeto en memoria.
     */
    private static String canonical(String value, Map<String, String> pool) {
        if (value == null) {
            return null;
        }
        return pool.computeIfAbsent(value, k -> k);
    }

    private MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda JVM; si faltara, el entorno está roto.
            throw new IOException("Algoritmo SHA-256 no disponible en la JVM", e);
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
     * Origen resuelto del catálogo: el stream a leer y una descripción legible
     * (classpath:... o ruta de archivo) para exponer como metadata de frescura.
     */
    private record ResolvedSource(InputStream stream, String description) {
    }

    /**
     * Estado temporal mutable durante el parseo. Una vez leídas todas las líneas
     * de un código postal, {@link #toImmutable()} produce el {@link ZipCode} final.
     */
    private static final class Accumulator {
        private final String zipCode;
        private final String locality;
        private final String federalEntity;
        private final String municipality;
        private final String normalizedFederalEntity;
        private final String normalizedMunicipality;
        private final List<Settlements> settlements = new ArrayList<>();

        private Accumulator(String zipCode,
                            String locality,
                            String federalEntity,
                            String municipality,
                            String normalizedFederalEntity,
                            String normalizedMunicipality) {
            this.zipCode = zipCode;
            this.locality = locality;
            this.federalEntity = federalEntity;
            this.municipality = municipality;
            this.normalizedFederalEntity = normalizedFederalEntity;
            this.normalizedMunicipality = normalizedMunicipality;
        }

        private ZipCode toImmutable() {
            return ZipCode.builder()
                    .zipCode(zipCode)
                    .locality(locality)
                    .federalEntity(federalEntity)
                    .municipality(municipality)
                    .settlements(List.copyOf(settlements))
                    .normalizedFederalEntity(normalizedFederalEntity)
                    .normalizedMunicipality(normalizedMunicipality)
                    .build();
        }
    }
}
