package com.coderalexis.CodigoPostalApi.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics configuration.
 * Uses low-cardinality tags to avoid Prometheus series explosion.
 */
@Component
public class MetricsConfiguration {

    private final MeterRegistry meterRegistry;

    public MetricsConfiguration(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records a search by type (low cardinality: direct, federal_entity, municipality, partial).
     */
    public void recordSearch(String searchType) {
        meterRegistry.counter("zipcode.search.total", "type", searchType).increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordSearchDuration(Timer.Sample sample, String searchType) {
        sample.stop(meterRegistry.timer("zipcode.search.duration", "type", searchType));
    }

    public void recordSearchError(String searchType, String errorType) {
        meterRegistry.counter("zipcode.search.errors",
            "search_type", searchType,
            "error_type", errorType)
            .increment();
    }

    public void recordResultSize(String searchType, int size) {
        meterRegistry.summary("zipcode.search.result_size", "search_type", searchType)
            .record(size);
    }
}
