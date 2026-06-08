package com.coderalexis.CodigoPostalApi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    private final RateLimitInterceptor interceptor =
            new RateLimitInterceptor(new RateLimitProperties());

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

    // ============================================================
    // Tests añadidos en #22: cobertura del flujo preHandle completo
    // ============================================================

    @Test
    @DisplayName("preHandle con rate limit deshabilitado deja pasar siempre")
    void shouldPassThroughWhenDisabled() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(false);
        RateLimitInterceptor disabled = new RateLimitInterceptor(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/zip-codes/01000");
        request.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(disabled.preHandle(request, response, new Object()));
        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }

    @Test
    @DisplayName("preHandle con IP en whitelist deja pasar sin consumir bucket")
    void shouldBypassRateLimitForWhitelistedIp() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerMinute(1);
        props.setBurstCapacity(1);
        props.setWhitelist(List.of("203.0.113.5"));
        RateLimitInterceptor limited = new RateLimitInterceptor(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/zip-codes/01000");
        request.setRemoteAddr("203.0.113.5");

        // Una IP en whitelist debe pasar muchas veces aunque el bucket sea de 1
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(limited.preHandle(request, response, new Object()),
                    "La IP whitelisted no debe ser limitada (iteración " + i + ")");
            assertEquals(HttpStatus.OK.value(), response.getStatus());
        }
    }

    @Test
    @DisplayName("preHandle bloquea cuando se excede la capacidad del bucket")
    void shouldBlockWhenBucketExhausted() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerMinute(1);
        props.setBurstCapacity(2);
        RateLimitInterceptor limited = new RateLimitInterceptor(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/zip-codes/01000");
        request.setRemoteAddr("198.51.100.1");

        // Las dos primeras peticiones consumen el burst y pasan.
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(limited.preHandle(request, response, new Object()),
                    "Las primeras peticiones dentro del burst deben pasar");
            assertNotNull(response.getHeader("X-RateLimit-Limit"));
            assertNotNull(response.getHeader("X-RateLimit-Remaining"));
        }

        // La tercera supera el burst → 429.
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(limited.preHandle(request, blocked, new Object()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), blocked.getStatus());
        assertEquals("0", blocked.getHeader("X-RateLimit-Remaining"));
        assertNotNull(blocked.getHeader("X-RateLimit-Retry-After-Seconds"));
        assertTrue(blocked.getContentAsString().contains("Limite de peticiones excedido"),
                "El body debe usar el formato consistente con ErrorResponse");
    }

    @Test
    @DisplayName("preHandle aísla buckets entre IPs cuando ip-based=true")
    void shouldIsolateBucketsBetweenClients() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setIpBased(true);
        props.setRequestsPerMinute(1);
        props.setBurstCapacity(1);
        RateLimitInterceptor limited = new RateLimitInterceptor(props);

        MockHttpServletRequest clientA = new MockHttpServletRequest("GET", "/zip-codes/01000");
        clientA.setRemoteAddr("198.51.100.10");
        MockHttpServletRequest clientB = new MockHttpServletRequest("GET", "/zip-codes/01000");
        clientB.setRemoteAddr("198.51.100.11");

        // Cliente A agota su bucket.
        assertTrue(limited.preHandle(clientA, new MockHttpServletResponse(), new Object()));
        assertFalse(limited.preHandle(clientA, new MockHttpServletResponse(), new Object()));

        // Cliente B sigue teniendo su propio bucket.
        assertTrue(limited.preHandle(clientB, new MockHttpServletResponse(), new Object()));
    }
}
