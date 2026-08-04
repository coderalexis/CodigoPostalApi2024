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
                "municipalitiesByEntity"
        );

        // NOTA: las cachés de búsqueda (por prefijo, entidad, municipio y avanzada)
        // se gestionan directamente con Caffeine dentro de ZipCodeService: evita la
        // trampa de self-invocation de Spring Cache, permite negative caching
        // (cachear listas vacías y aun así lanzar la excepción 404 por request) y
        // da bloqueo por clave contra estampidas. Sus métricas se registran allí
        // con CaffeineCacheMetrics, igual que aquí.
        // NOTA: getAllFederalEntities() ya NO se cachea (devuelve un campo inmutable
        // en memoria) y el lookup directo por CP tampoco (Map.get ya es O(1)).

        registerCache(cacheManager, "municipalitiesByEntity",
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
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
