package com.coderalexis.CodigoPostalApi.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting interceptor using Token Bucket algorithm (Bucket4j).
 * Uses Caffeine cache for automatic bucket eviction to prevent memory leaks.
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties rateLimitProperties;
    // Caffeine cache with TTL-based eviction (buckets expire after 5 minutes of inactivity)
    private final Cache<String, Bucket> bucketCache;

    public RateLimitInterceptor(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
        this.bucketCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .build();
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
        Bucket bucket = bucketCache.get(key, k -> createNewBucket());

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
            "{\"status\":429,\"message\":\"Limite de peticiones excedido. Maximo %d peticiones por minuto.\",\"timestamp\":\"%s\"}",
            rateLimitProperties.getRequestsPerMinute(),
            java.time.LocalDateTime.now()
        ));

        return false;
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
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty() || "unknown".equalsIgnoreCase(xfHeader)) {
            return request.getRemoteAddr();
        }
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

    boolean ipMatchesCIDR(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                log.warn("CIDR invalido en whitelist: {}", cidr);
                return false;
            }

            InetAddress ipAddress = InetAddress.getByName(ip);
            InetAddress networkAddress = InetAddress.getByName(parts[0]);

            byte[] ipBytes = ipAddress.getAddress();
            byte[] networkBytes = networkAddress.getAddress();
            if (ipBytes.length != networkBytes.length) {
                return false;
            }

            int prefixLength = Integer.parseInt(parts[1]);
            int maxPrefixLength = ipBytes.length * Byte.SIZE;
            if (prefixLength < 0 || prefixLength > maxPrefixLength) {
                log.warn("Mascara CIDR invalida en whitelist: {}", cidr);
                return false;
            }

            BigInteger ipValue = new BigInteger(1, ipBytes);
            BigInteger networkValue = new BigInteger(1, networkBytes);
            BigInteger mask = cidrMask(prefixLength, maxPrefixLength);

            return ipValue.and(mask).equals(networkValue.and(mask));
        } catch (NumberFormatException | UnknownHostException e) {
            log.warn("Error verificando CIDR {}: {}", cidr, e.getMessage());
            return false;
        }
    }

    private BigInteger cidrMask(int prefixLength, int totalBits) {
        if (prefixLength == 0) {
            return BigInteger.ZERO;
        }

        return BigInteger.ONE
                .shiftLeft(prefixLength)
                .subtract(BigInteger.ONE)
                .shiftLeft(totalBits - prefixLength);
    }
}
