package com.coderalexis.CodigoPostalApi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimitProperties());

    @Test
    @DisplayName("Debe validar rangos CIDR IPv4 correctamente")
    void shouldMatchIpv4CidrRanges() {
        assertTrue(interceptor.ipMatchesCIDR("192.168.1.25", "192.168.1.0/24"));
        assertTrue(interceptor.ipMatchesCIDR("10.10.5.10", "10.10.0.0/16"));
        assertFalse(interceptor.ipMatchesCIDR("192.168.2.25", "192.168.1.0/24"));
        assertFalse(interceptor.ipMatchesCIDR("10.11.5.10", "10.10.0.0/16"));
    }

    @Test
    @DisplayName("Debe validar rangos CIDR IPv6 correctamente")
    void shouldMatchIpv6CidrRanges() {
        assertTrue(interceptor.ipMatchesCIDR("2001:db8::1", "2001:db8::/32"));
        assertFalse(interceptor.ipMatchesCIDR("2001:db9::1", "2001:db8::/32"));
    }

    @Test
    @DisplayName("Debe rechazar CIDR inválidos sin hacer match")
    void shouldRejectInvalidCidrValues() {
        assertFalse(interceptor.ipMatchesCIDR("192.168.1.25", "192.168.1.0/33"));
        assertFalse(interceptor.ipMatchesCIDR("192.168.1.25", "192.168.1.0"));
        assertFalse(interceptor.ipMatchesCIDR("192.168.1.25", "not-an-ip/24"));
    }
}
