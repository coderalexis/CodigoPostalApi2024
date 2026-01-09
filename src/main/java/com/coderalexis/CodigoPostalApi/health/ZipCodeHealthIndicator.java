package com.coderalexis.CodigoPostalApi.health;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ZipCodeHealthIndicator implements HealthIndicator {

    private final ZipCodeService zipCodeService;

    public ZipCodeHealthIndicator(ZipCodeService zipCodeService) {
        this.zipCodeService = zipCodeService;
    }

    @Override
    public Health health() {
        if (!zipCodeService.isDataLoaded()) {
            return Health.down()
                    .withDetail("reason", "Zip codes data not loaded")
                    .withDetail("status", "Service unavailable")
                    .build();
        }

        int zipCodeCount = zipCodeService.getZipCodeCount();

        if (zipCodeCount == 0) {
            return Health.down()
                    .withDetail("reason", "No zip codes available")
                    .withDetail("zipCodeCount", 0)
                    .build();
        }

        return Health.up()
                .withDetail("zipCodeCount", zipCodeCount)
                .withDetail("status", "Data loaded successfully")
                .build();
    }
}
