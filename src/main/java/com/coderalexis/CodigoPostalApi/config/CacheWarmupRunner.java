package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@Slf4j
public class CacheWarmupRunner {

    // Códigos postales más consultados (ajustar según analytics reales)
    private static final List<String> COMMON_ZIP_CODES = List.of(
            "70000",  // Juchitán
            "01000",  // Ciudad de México
            "06600",  // Juárez, CDMX
            "44100",  // Guadalajara
            "64000",  // Monterrey
            "72000",  // Puebla
            "97000",  // Mérida
            "80000",  // Culiacán
            "83000",  // Hermosillo
            "20000"   // Aguascalientes
    );

    // Entidades federativas más buscadas
    private static final List<String> COMMON_FEDERAL_ENTITIES = List.of(
            "Ciudad de México",
            "México",
            "Jalisco",
            "Nuevo León",
            "Oaxaca"
    );

    // Municipios más buscados
    private static final List<String> COMMON_MUNICIPALITIES = List.of(
            "Guadalajara",
            "Monterrey",
            "Juchitán",
            "Álvaro Obregón"
    );

    @Bean
    public CommandLineRunner warmupCache(ZipCodeService zipCodeService) {
        return args -> {
            log.info("Iniciando precarga de caché...");
            long start = System.currentTimeMillis();

            int loaded = 0;

            // Pre-cargar códigos postales comunes
            for (String zipCode : COMMON_ZIP_CODES) {
                try {
                    zipCodeService.getZipCode(zipCode);
                    loaded++;
                } catch (Exception e) {
                    log.debug("Código postal {} no encontrado para precarga", zipCode);
                }
            }

            // Pre-cargar búsquedas por entidad federativa
            for (String entity : COMMON_FEDERAL_ENTITIES) {
                try {
                    zipCodeService.searchByFederalEntity(entity);
                    loaded++;
                } catch (Exception e) {
                    log.debug("Entidad {} no encontrada para precarga", entity);
                }
            }

            // Pre-cargar búsquedas por municipio
            for (String municipality : COMMON_MUNICIPALITIES) {
                try {
                    zipCodeService.searchByMunicipality(municipality);
                    loaded++;
                } catch (Exception e) {
                    log.debug("Municipio {} no encontrado para precarga", municipality);
                }
            }

            long duration = System.currentTimeMillis() - start;
            log.info("✓ Caché precargado: {} elementos en {}ms", loaded, duration);
        };
    }
}
