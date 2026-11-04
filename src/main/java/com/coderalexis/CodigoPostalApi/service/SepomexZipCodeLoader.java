package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.util.Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
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
    private static final int MIN_COLUMNS = 15;
    private static final int MAX_ERRORS_THRESHOLD = 100;
    private static final int MAX_FILE_BYTES = 100 * 1024 * 1024;
    private static final String HEADER = "d_codigo|d_asenta|d_tipo_asenta|D_mnpio|d_estado|d_ciudad|d_CP|c_estado|c_oficina|c_CP|c_tipo_asenta|c_mnpio|id_asenta_cpcons|d_zona|c_cve_ciudad";

    private static final Pattern ZIP_CODE_PATTERN = Pattern.compile("^\\d{5}$");

    private static final String RESOURCE_FILE = "CPdescarga.txt";

    @Value("${zipcode.file.path}")
    private String filePath;

    @Value("${zipcode.file.encoding:auto}")
    private String fileEncoding = "auto";

    @Value("${zipcode.catalog.directory:}")
    private String catalogDirectory = "";

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
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("El catálogo excede 100 MiB");
            ZipCodeData data = parse(new ByteArrayInputStream(bytes), resolved.description(), detectCharset(bytes));
            if (resolved.expectedChecksum() != null && !resolved.expectedChecksum().equals(data.checksum())) {
                throw new IOException("Checksum incorrecto en el catálogo activo");
            }
            return data;
        }
    }

    private ZipCodeData parse(InputStream stream, String source, Charset charset) throws IOException {
        long startTime = System.currentTimeMillis();
        errorCount = 0;

        log.info("Encoding detectado: {}", charset.name());
        MessageDigest digest = newSha256();
        DigestInputStream digestStream = new DigestInputStream(stream, digest);

        // Estructuras temporales mutables: acumulan asentamientos por CP y canonizan
        // los Strings repetidos. Todas se descartan al terminar el parseo.
        // normalizedPool memoiza valor crudo -> valor normalizado canónico para los
        // campos de baja cardinalidad, de modo que la normalización NFD corra una vez
        // por valor distinto (decenas) y no una vez por línea (~1.5M).
        Map<String, Accumulator> accumulators = new HashMap<>();
        Map<String, String> stringPool = new HashMap<>();
        Map<String, String> normalizedPool = new HashMap<>();
        Set<String> identities = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(digestStream, charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {

            // Skip first two lines (metadata and header)
            reader.readLine();
            if (!HEADER.equals(reader.readLine())) throw new IOException("Encabezado SEPOMEX inválido");

            long linesProcessed = reader.lines()
                    .filter(line -> processLine(line, accumulators, stringPool, normalizedPool, identities))
                    .count();

            if (errorCount > 0 || linesProcessed == 0) {
                throw new IOException("Catálogo rechazado: " + errorCount + " líneas inválidas; " + linesProcessed + " válidas");
            }
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
        Map<String, List<ZipCode>> byNormalizedEntity = new HashMap<>();
        Map<String, List<ZipCode>> byNormalizedMunicipality = new HashMap<>();

        for (Accumulator acc : accumulators.values()) {
            ZipCode zipCode = acc.toImmutable();

            byCode.put(zipCode.getZipCode(), zipCode);
            sorted.put(zipCode.getZipCode(), zipCode);
            byNormalizedEntity
                    .computeIfAbsent(zipCode.getNormalizedFederalEntity(), k -> new ArrayList<>())
                    .add(zipCode);
            byNormalizedMunicipality
                    .computeIfAbsent(zipCode.getNormalizedMunicipality(), k -> new ArrayList<>())
                    .add(zipCode);
        }

        // Los buckets se ordenan UNA vez aquí y se congelan: las búsquedas paginadas
        // ya no re-ordenan por request. No hace falta dedup (antes HashSet) porque
        // cada ZipCode tiene exactamente una entidad y un municipio normalizados,
        // así que los buckets de un mismo índice son disjuntos.
        Comparator<ZipCode> byZip = Comparator.comparing(ZipCode::getZipCode);
        byNormalizedEntity.replaceAll((k, bucket) -> {
            bucket.sort(byZip);
            return List.copyOf(bucket);
        });
        byNormalizedMunicipality.replaceAll((k, bucket) -> {
            bucket.sort(byZip);
            return List.copyOf(bucket);
        });

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
        if (catalogDirectory != null && !catalogDirectory.isBlank()) {
            Path store = Path.of(catalogDirectory);
            var manifest = JsonMapper.builder().build().readTree(store.resolve("active.json").toFile());
            String checksum = manifest.path("current").asText();
            if (!checksum.matches("[0-9a-f]{64}")) throw new IOException("Checksum inválido en active.json");
            Path version = store.resolve("versions").resolve(checksum + ".txt");
            return new ResolvedSource(Files.newInputStream(version), "catalog:" + checksum, checksum);
        }
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

    private Charset detectCharset(byte[] bytes) throws IOException {
        if (!"auto".equalsIgnoreCase(fileEncoding)) {
            if (!Set.of("UTF-8", "ISO-8859-1").contains(fileEncoding.toUpperCase(java.util.Locale.ROOT))) {
                throw new IOException("Encoding admitido: auto, UTF-8 o ISO-8859-1");
            }
            return Charset.forName(fileEncoding);
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException invalidUtf8) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private boolean processLine(String line,
                                Map<String, Accumulator> accumulators,
                                Map<String, String> pool,
                                Map<String, String> normalizedPool, Set<String> identities) {
        try {
            String[] words = PIPE_PATTERN.split(line, -1);

            if (words.length != MIN_COLUMNS) {
                handleError("Linea con formato incorrecto (columnas insuficientes): {}",
                    line.substring(0, Math.min(100, line.length())));
                return false;
            }

            for (int i = 0; i < words.length; i++) words[i] = words[i].trim();
            if (!words[7].matches("[0-9]{2}") || !words[11].matches("[0-9]{3}") ||
                    !words[12].matches("[0-9]{4}") || Integer.parseInt(words[7]) < 1 ||
                    Integer.parseInt(words[7]) > 32 || words[1].isBlank() || words[2].isBlank() ||
                    !Set.of("Urbano", "Rural", "Semiurbano").contains(words[13])) {
                handleError("Claves oficiales o campos inválidos en CP {}", words[0]);
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
                    canonical(words[7].trim(), pool),
                    canonical(words[11].trim(), pool),
                    canonical(Util.normalizeString(federalEntity), pool),
                    canonical(Util.normalizeString(municipality), pool)));

            if (!acc.federalEntityCode.equals(words[7]) || !acc.municipalityCode.equals(words[11]) ||
                    !acc.federalEntity.equals(federalEntity) || !acc.municipality.equals(municipality) ||
                    !identities.add(zipCodeKey + ":" + words[7] + ":" + words[11] + ":" + words[12])) {
                handleError("Ubicación contradictoria o asentamiento duplicado en CP {}", zipCodeKey);
                return false;
            }
            String settlementName = words[COL_SETTLEMENT_NAME].trim();
            String settlementTypeVal = words[COL_SETTLEMENT_TYPE].trim();
            String zoneTypeVal = words.length > COL_ZONE_TYPE_INDEX ?
                words[COL_ZONE_TYPE_INDEX].trim() : "";

            // Fix #18: Settlements es un record inmutable con los campos normalizados precomputados.
            // El nombre del asentamiento es de alta cardinalidad y no se canoniza; el tipo de
            // asentamiento y el tipo de zona sí (apenas unas decenas de valores distintos), y su
            // normalización se memoiza para no repetir NFD+regex en cada línea.
            acc.settlements.add(new Settlements(
                    settlementName,
                    canonical(zoneTypeVal, pool),
                    canonical(settlementTypeVal, pool),
                    Util.normalizeString(settlementName),
                    normalizedCanonical(settlementTypeVal, pool, normalizedPool),
                    normalizedCanonical(zoneTypeVal, pool, normalizedPool)));

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

    /**
     * Versión normalizada y canónica de un valor de baja cardinalidad. Memoiza
     * crudo -> normalizado en {@code normalizedPool} para que la normalización
     * NFD sólo se ejecute la primera vez que aparece cada valor distinto.
     */
    private static String normalizedCanonical(String value,
                                              Map<String, String> pool,
                                              Map<String, String> normalizedPool) {
        if (value == null) {
            return null;
        }
        return normalizedPool.computeIfAbsent(value, v -> canonical(Util.normalizeString(v), pool));
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
    private record ResolvedSource(InputStream stream, String description, String expectedChecksum) {
        ResolvedSource(InputStream stream, String description) { this(stream, description, null); }
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
        private final String federalEntityCode;
        private final String municipalityCode;
        private final String normalizedFederalEntity;
        private final String normalizedMunicipality;
        private final List<Settlements> settlements = new ArrayList<>();

        private Accumulator(String zipCode,
                            String locality,
                            String federalEntity,
                            String municipality,
                            String federalEntityCode,
                            String municipalityCode,
                            String normalizedFederalEntity,
                            String normalizedMunicipality) {
            this.zipCode = zipCode;
            this.locality = locality;
            this.federalEntity = federalEntity;
            this.municipality = municipality;
            this.federalEntityCode = federalEntityCode;
            this.municipalityCode = municipalityCode;
            this.normalizedFederalEntity = normalizedFederalEntity;
            this.normalizedMunicipality = normalizedMunicipality;
        }

        private ZipCode toImmutable() {
            return ZipCode.builder()
                    .zipCode(zipCode)
                    .locality(locality)
                    .federalEntity(federalEntity)
                    .municipality(municipality)
                    .federalEntityCode(federalEntityCode)
                    .municipalityCode(municipalityCode)
                    .settlements(List.copyOf(settlements))
                    .normalizedFederalEntity(normalizedFederalEntity)
                    .normalizedMunicipality(normalizedMunicipality)
                    .build();
        }
    }
}
