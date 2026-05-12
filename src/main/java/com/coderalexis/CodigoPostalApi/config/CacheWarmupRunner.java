package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@Slf4j
public class CacheWarmupRunner {

    private static final int WARMUP_PAGE = 0;
    private static final int WARMUP_PAGE_SIZE = 20;

    private static final List<String> COMMON_FEDERAL_ENTITIES = List.of(
            "Ciudad de Mexico", "Mexico", "Jalisco", "Nuevo Leon", "Oaxaca"
    );

    private static final List<String> COMMON_MUNICIPALITIES = List.of(
            "Guadalajara", "Monterrey", "Juchitan", "Alvaro Obregon"
    );

    @Bean
    @ConditionalOnProperty(name = "cache.warmup.enabled", havingValue = "true", matchIfMissing = true)
    public CommandLineRunner warmupCache(ZipCodeService zipCodeService) {
        return args -> {
            log.info("Iniciando precarga de cache en paralelo...");
            long start = System.currentTimeMillis();

            AtomicInteger loaded = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    zipCodeService.getAllFederalEntities();
                    loaded.incrementAndGet();
                } catch (Exception e) {
                    log.debug("No se pudo precargar el catalogo de entidades federativas", e);
                }
            }));

            for (String entity : COMMON_FEDERAL_ENTITIES) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        zipCodeService.searchByFederalEntity(entity, WARMUP_PAGE, WARMUP_PAGE_SIZE);
                        loaded.incrementAndGet();
                    } catch (Exception e) {
                        log.debug("Entidad {} no encontrada para precarga", entity);
                    }
                }));
            }

            for (String municipality : COMMON_MUNICIPALITIES) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        zipCodeService.searchByMunicipality(municipality, WARMUP_PAGE, WARMUP_PAGE_SIZE);
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
