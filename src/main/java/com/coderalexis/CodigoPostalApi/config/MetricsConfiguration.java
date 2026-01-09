package com.coderalexis.CodigoPostalApi.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuración y tracking de métricas personalizadas de negocio
 */
@Component
public class MetricsConfiguration {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicLong> searchCounters = new ConcurrentHashMap<>();

    public MetricsConfiguration(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        initializeMetrics();
    }

    private void initializeMetrics() {
        // Registrar gauges para monitorear contadores
        meterRegistry.gauge("zipcode.searches.total", searchCounters,
            map -> map.values().stream().mapToLong(AtomicLong::get).sum());
    }

    /**
     * Registra una búsqueda por código postal
     */
    public void recordZipCodeSearch(String zipCode) {
        meterRegistry.counter("zipcode.search.direct",
            "zipcode", zipCode)
            .increment();
    }

    /**
     * Registra una búsqueda por entidad federativa
     */
    public void recordFederalEntitySearch(String entity) {
        meterRegistry.counter("zipcode.search.federal_entity",
            "entity", entity)
            .increment();

        searchCounters.computeIfAbsent("federal_entity", k -> new AtomicLong(0))
            .incrementAndGet();
    }

    /**
     * Registra una búsqueda por municipio
     */
    public void recordMunicipalitySearch(String municipality) {
        meterRegistry.counter("zipcode.search.municipality",
            "municipality", municipality)
            .increment();

        searchCounters.computeIfAbsent("municipality", k -> new AtomicLong(0))
            .incrementAndGet();
    }

    /**
     * Crea un timer para medir duración de operaciones
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Registra el tiempo de una búsqueda
     */
    public void recordSearchDuration(Timer.Sample sample, String searchType) {
        sample.stop(meterRegistry.timer("zipcode.search.duration",
            "type", searchType));
    }

    /**
     * Registra error en búsqueda
     */
    public void recordSearchError(String searchType, String errorType) {
        meterRegistry.counter("zipcode.search.errors",
            "search_type", searchType,
            "error_type", errorType)
            .increment();
    }

    /**
     * Registra el tamaño de resultados
     */
    public void recordResultSize(String searchType, int size) {
        meterRegistry.summary("zipcode.search.result_size",
            "search_type", searchType)
            .record(size);
    }
}
