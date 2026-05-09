package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@Slf4j
public class CacheWarmupRunner {

    private static final List<String> COMMON_ZIP_CODES = List.of(
            "70000", "01000", "06600", "44100", "64000",
            "72000", "97000", "80000", "83000", "20000"
    );

    private static final List<String> COMMON_FEDERAL_ENTITIES = List.of(
            "Ciudad de Mexico", "Mexico", "Jalisco", "Nuevo Leon", "Oaxaca"
    );

    private static final List<String> COMMON_MUNICIPALITIES = List.of(
            "Guadalajara", "Monterrey", "Juchitan", "Alvaro Obregon"
    );

    @Bean
    public CommandLineRunner warmupCache(ZipCodeService zipCodeService) {
        return args -> {
            log.info("Iniciando precarga de cache en paralelo...");
            long start = System.currentTimeMillis();

            AtomicInteger loaded = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String zipCode : COMMON_ZIP_CODES) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        zipCodeService.getZipCode(zipCode);
                        loaded.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Codigo postal {} no encontrado para precarga", zipCode);
                    }
                }));
            }

            for (String entity : COMMON_FEDERAL_ENTITIES) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        zipCodeService.searchByFederalEntity(entity);
                        loaded.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Entidad {} no encontrada para precarga", entity);
                    }
                }));
            }

            for (String municipality : COMMON_MUNICIPALITIES) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        zipCodeService.searchByMunicipality(municipality);
                        loaded.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Municipio {} no encontrado para precarga", municipality);
                    }
                }));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            long duration = System.currentTimeMillis() - start;
            log.info("Cache precargado: {} elementos en {}ms", loaded.get(), duration);
        };
    }
}
