package com.coderalexis.CodigoPostalApi.health;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests añadidos en #22 para cubrir las tres ramas del health indicator que
 * antes se ejercitaban sólo indirectamente.
 */
class ZipCodeHealthIndicatorTest {

    @Test
    @DisplayName("UP cuando los datos están cargados y hay códigos postales")
    void shouldBeUpWhenDataLoaded() {
        ZipCodeService service = mock(ZipCodeService.class);
        when(service.isDataLoaded()).thenReturn(true);
        when(service.getZipCodeCount()).thenReturn(145_000);

        Health health = new ZipCodeHealthIndicator(service).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(145_000, health.getDetails().get("zipCodeCount"));
    }

    @Test
    @DisplayName("DOWN cuando los datos NO están cargados")
    void shouldBeDownWhenDataNotLoaded() {
        ZipCodeService service = mock(ZipCodeService.class);
        when(service.isDataLoaded()).thenReturn(false);

        Health health = new ZipCodeHealthIndicator(service).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Zip codes data not loaded", health.getDetails().get("reason"));
    }

    @Test
    @DisplayName("DOWN cuando se cargaron pero quedaron cero códigos postales")
    void shouldBeDownWhenZeroZipCodes() {
        ZipCodeService service = mock(ZipCodeService.class);
        when(service.isDataLoaded()).thenReturn(true);
        when(service.getZipCodeCount()).thenReturn(0);

        Health health = new ZipCodeHealthIndicator(service).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("No zip codes available", health.getDetails().get("reason"));
    }
}
