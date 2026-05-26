package com.coderalexis.CodigoPostalApi.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfiguration {

    private final MeterRegistry meterRegistry;

    public CacheConfiguration(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "zipcodes",
                "federalEntitySearch",
                "federalEntitySearchPaged",
                "municipalitySearch",
                "municipalitySearchPaged",
                "federalEntities",
                "municipalitiesByEntity",
                "advancedSearch",
                "advancedSearchPaged"
        );

        // Direct zip code lookup: O(1) in-memory Map access, no cache benefit.
        // Kept for backward compatibility with @Cacheable annotations.
        registerCache(cacheManager, "zipcodes",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterAccess(1, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        // Federal entity search: results can be large (full ZipCode objects with settlements).
        registerCache(cacheManager, "federalEntitySearch",
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Paginated variant: more specific keys, separate cache entry.
        registerCache(cacheManager, "federalEntitySearchPaged",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        registerCache(cacheManager, "municipalitySearch",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        registerCache(cacheManager, "municipalitySearchPaged",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // NOTA: la cache de búsqueda parcial (autocompletado) se gestiona directamente
        // con Caffeine en ZipCodeService para evitar la trampa de self-invocation de
        // Spring Cache.

        registerCache(cacheManager, "federalEntities",
                Caffeine.newBuilder()
                        .maximumSize(1)
                        .expireAfterWrite(1, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        registerCache(cacheManager, "municipalitiesByEntity",
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        registerCache(cacheManager, "advancedSearch",
                Caffeine.newBuilder()
                        .maximumSize(25)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        registerCache(cacheManager, "advancedSearchPaged",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        return cacheManager;
    }

    /**
     * Registra una caché y la enlaza al MeterRegistry para que las estadísticas
     * generadas por {@code recordStats()} (hit ratio, evictions, load duration,
     * etc.) queden expuestas vía Prometheus/Micrometer. Antes se activaba
     * {@code recordStats()} pero nadie las cosechaba, lo cual sólo añadía
     * overhead sin beneficio. Fix #13.
     */
    private void registerCache(CaffeineCacheManager cacheManager,
                               String name,
                               com.github.benmanes.caffeine.cache.Cache<Object, Object> cache) {
        cacheManager.registerCustomCache(name, cache);
        CaffeineCacheMetrics.monitor(meterRegistry, cache, name);
    }
}
