package com.coderalexis.CodigoPostalApi.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting interceptor using Token Bucket algorithm (Bucket4j).
 * Limits requests per IP address or globally based on configuration.
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties rateLimitProperties;
    // Cache of buckets per IP address
    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    public RateLimitInterceptor(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!rateLimitProperties.isEnabled()) {
            return true;
        }

        String clientIp = getClientIP(request);

        if (isWhitelisted(clientIp)) {
            log.debug("IP {} en whitelist, sin rate limiting", clientIp);
            return true;
        }

        String key = rateLimitProperties.isIpBased() ? clientIp : "global";
        Bucket bucket = resolveBucket(key);

        if (bucket.tryConsume(1)) {
            long availableTokens = bucket.getAvailableTokens();
            response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getRequestsPerMinute()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(availableTokens));
            return true;
        }

        log.warn("Rate limit excedido para IP: {}", clientIp);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getRequestsPerMinute()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Retry-After-Seconds", "60");
        response.setContentType("application/json");
        response.getWriter().write(String.format(
            "{\"status\":429,\"message\":\"Límite de peticiones excedido. Máximo %d peticiones por minuto.\",\"timestamp\":\"%s\"}",
            rateLimitProperties.getRequestsPerMinute(),
            java.time.LocalDateTime.now()
        ));

        return false;
    }

    private Bucket resolveBucket(String key) {
        return cache.computeIfAbsent(key, k -> createNewBucket());
    }

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitProperties.getBurstCapacity())
                .refillGreedy(rateLimitProperties.getRequestsPerMinute(), Duration.ofMinutes(1))
                .build();

        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    private String getClientIP(HttpServletRequest request) {
        // Get real client IP considering proxies and load balancers
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty() || "unknown".equalsIgnoreCase(xfHeader)) {
            return request.getRemoteAddr();
        }
        // X-Forwarded-For may contain multiple IPs, take the first one
        return xfHeader.split(",")[0].trim();
    }

    private boolean isWhitelisted(String ip) {
        if (rateLimitProperties.getWhitelist() == null) {
            return false;
        }

        for (String whitelistedIp : rateLimitProperties.getWhitelist()) {
            if (whitelistedIp.equals(ip)) {
                return true;
            }
            if (whitelistedIp.contains("/") && ipMatchesCIDR(ip, whitelistedIp)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Simplified CIDR matching for IPv4 ranges.
     * For production, consider using a dedicated IP library.
     */
    private boolean ipMatchesCIDR(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            if (ip.startsWith(network.substring(0, network.lastIndexOf('.')))) {
                return true;
            }
        } catch (Exception e) {
            log.error("Error verificando CIDR: {}", cidr, e);
        }

        return false;
    }

    /**
     * Cleans old buckets to prevent memory leaks.
     * For production, consider using Caffeine cache with TTL.
     */
    public void cleanupOldBuckets() {
        if (cache.size() > 10000) {
            log.info("Limpiando cache de buckets, tamaño actual: {}", cache.size());
            cache.clear();
        }
    }
}
