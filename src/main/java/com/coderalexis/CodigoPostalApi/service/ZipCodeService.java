package com.coderalexis.CodigoPostalApi.service;


import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.util.Util;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final Map<String, ZipCode> mapZipCodes = new HashMap<>();
    private final List<ZipCode> zipCodeList = new ArrayList<>();

    @Value("${zipcode.file.path}")
    private String filePath;

    /**
     * Obtiene un ZipCode por su código postal.
     *
     * @param zipcode El código postal a buscar.
     * @return El objeto ZipCode correspondiente o null si no se encuentra.
     */
    public ZipCode getZipCode(String zipcode) {
        return mapZipCodes.get(zipcode);
    }


    /**
     * Carga los códigos postales desde el archivo especificado al iniciar la aplicación.
     */
    @PostConstruct
    public void loadZipCodes() {

        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            log.error("El archivo de códigos postales no existe en la ruta especificada: {}", filePath);
            return;
        }

        try (Stream<String> lines = Files.lines(path, StandardCharsets.ISO_8859_1)) {
            lines.skip(2).forEach(line -> {
                try {
                    String separator = Pattern.quote("|");
                    String[] words = line.split(separator);

                    if (words.length >= 6) {
                        String zip_code_key = words[0];

                        ZipCode zipcode = mapZipCodes.computeIfAbsent(zip_code_key, k -> {
                            ZipCode z = new ZipCode();
                            z.setZip_code(words[0]);
                            z.setLocality(words[4]);
                            z.setFederal_entity(words[5]);
                            z.setMunicipality(words[3]);
                            z.setSettlements(new ArrayList<>());
                            zipCodeList.add(z);
                            return z;
                        });

                        Settlements settlement = new Settlements();
                        settlement.setName(words[1]);
                        settlement.setZone_type(words.length < 15 ? words[words.length - 1] : words[words.length - 2]);
                        settlement.setSettlement_type(words[2]);

                        zipcode.getSettlements().add(settlement);
                    } else {
                        log.warn("Línea con formato incorrecto: {}", line);

                    }
                } catch (Exception e) {
                    log.error("Error procesando la línea: {}", line, e);
                }
            });
            log.info("Códigos postales cargados correctamente, registros cargados: {}",mapZipCodes.size());

        } catch (IOException e) {
            log.error("Error al cargar los códigos postales", e);
        }
    }


    /**
     * Busca ZipCodes cuya entidad federativa contenga el término de búsqueda.
     *
     * @param searchTerm El término de búsqueda.
     * @return Lista de ZipCodes que coinciden con el término de búsqueda.
     */
    public List<ZipCode> searchByFederalEntity(String searchTerm) {
        if (searchTerm == null || searchTerm.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedSearchTerm = Util.normalizeString(searchTerm);

        return zipCodeList.stream()
                .filter(zipCode -> {
                    String normalizedFederalEntity = Util.normalizeString(zipCode.getFederal_entity());
                    return normalizedFederalEntity != null && normalizedFederalEntity.contains(normalizedSearchTerm);
                })
                .collect(Collectors.toList());
    }


}
