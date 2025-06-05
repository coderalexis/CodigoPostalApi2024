package com.coderalexis.CodigoPostalApi.service;


import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.util.Util;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class ZipCodeService {
    private static final String LINE_SEPARATOR = Pattern.quote("|");

    // Usar solo un mapa principal
    private final Map<String, ZipCode> zipCodesByCode = new HashMap<>();
    // Índice invertido para búsquedas por entidad federativa
    private final Map<String, Set<String>> zipCodesByNormalizedEntity = new HashMap<>();
    private final Map<String, Set<String>> zipCodesByNormalizedMunicipality = new HashMap<>();


    @Value("${zipcode.file.path}")
    private String filePath;

    private static final String RESOURCE_FILE = "CPdescarga.txt";

    @Cacheable(value = "zipcodes", key = "#zipcode")
    public ZipCode getZipCode(String zipcode) {
        ZipCode zipCode = zipCodesByCode.get(zipcode);
        if (zipCode == null) {
            throw new ZipCodeNotFoundException("Código postal no encontrado: " + zipcode);
        }
        return zipCode;
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
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.ISO_8859_1))) {

            reader.lines()
                    .skip(2)
                    .forEach(this::processLine);

            log.info("Códigos postales cargados: {}, Índice de entidades: {}",
                    zipCodesByCode.size(), zipCodesByNormalizedEntity.size());
        }


    }

    private void processLine(String line) {
        try {
            String[] words = line.split(LINE_SEPARATOR);

            if (words.length < 6) {
                log.warn("Línea con formato incorrecto: {}", line);
                return;
            }

            String zipCodeKey = words[0];
            String federalEntity = words[5];
            String normalizedEntity = Util.normalizeString(federalEntity);

            ZipCode zipCode = zipCodesByCode.computeIfAbsent(zipCodeKey, k -> {
                ZipCode z = new ZipCode();
                z.setZip_code(k);
                z.setLocality(words[4]);
                z.setFederal_entity(federalEntity);
                z.setMunicipality(words[3]);
                z.setSettlements(new ArrayList<>());

                // Actualizar índice de entidad federativa
                zipCodesByNormalizedEntity
                        .computeIfAbsent(normalizedEntity, ke -> new HashSet<>())
                        .add(k);

                return z;
            });

            Settlements settlement = new Settlements();
            settlement.setName(words[1]);
            settlement.setZone_type(words.length < 15 ? words[words.length - 1] : words[words.length - 2]);
            settlement.setSettlement_type(words[2]);

            zipCode.getSettlements().add(settlement);


            String municipality = words[3];
            String normalizedMunicipality = Util.normalizeString(municipality);

            // Actualizar índice de municipio
            zipCodesByNormalizedMunicipality
                    .computeIfAbsent(normalizedMunicipality, k -> new HashSet<>())
                    .add(zipCodeKey);

        } catch (Exception e) {
            log.error("Error procesando la línea: {}", line, e);
        }
    }

    @Cacheable(value = "federalEntitySearch", key = "#searchTerm.toLowerCase()")
    public List<ZipCode> searchByFederalEntity(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            throw new IllegalArgumentException("El término de búsqueda no puede estar vacío");
        }

        String normalizedSearchTerm = Util.normalizeString(searchTerm.trim());

        // Búsqueda eficiente usando el índice
        List<ZipCode> results = zipCodesByNormalizedEntity.entrySet().stream()
                .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                .flatMap(entry -> entry.getValue().stream())
                .map(zipCodesByCode::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            throw new ZipCodeNotFoundException(
                    "No se encontraron códigos postales para la entidad federativa: " + searchTerm
            );
        }

        return results;
    }

    @Cacheable(value = "municipalitySearch", key = "#searchTerm.toLowerCase()")
    public List<ZipCode> searchByMunicipality(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            throw new IllegalArgumentException("El término de búsqueda no puede estar vacío");
        }

        String normalizedSearchTerm = Util.normalizeString(searchTerm.trim());

        List<ZipCode> results = zipCodesByNormalizedMunicipality.entrySet().stream()
                .filter(entry -> entry.getKey().contains(normalizedSearchTerm))
                .flatMap(entry -> entry.getValue().stream())
                .map(zipCodesByCode::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            throw new ZipCodeNotFoundException(
                    "No se encontraron códigos postales para el municipio: " + searchTerm
            );
        }

        return results;
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