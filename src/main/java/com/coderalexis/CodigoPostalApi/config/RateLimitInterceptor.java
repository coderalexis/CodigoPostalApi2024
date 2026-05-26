package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.exceptions.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting interceptor using Token Bucket algorithm (Bucket4j).
 * Uses Caffeine cache for automatic bucket eviction to prevent memory leaks.
 *
 * <p>Fix #3: la IP del cliente se obtiene exclusivamente via
 * {@code request.getRemoteAddr()}. En entornos detrás de proxy/CDN se debe
 * habilitar {@code server.forward-headers-strategy=framework} (configurado en
 * los perfiles prod/railway) para que Spring Boot resuelva la cadena de
 * X-Forwarded-* a través de su filtro ya validado, en vez de confiar a ciegas
 * en el header (que es spoofable trivialmente).</p>
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties rateLimitProperties;
    // ObjectMapper privado con JSR310 registrado. Antes lo inyectábamos por
    // constructor pero el orden de inicialización (WebMvc → Interceptors →
    // JacksonAutoConfiguration) provocaba que la dependencia no estuviera
    // disponible en algunos contextos de test. Como sólo serializamos un
    // ErrorResponse, una instancia local autosuficiente es más robusta.
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
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
        writeRateLimitExceededResponse(request, response);
        return false;
    }

    /**
     * Fix #7: serializa la respuesta 429 reusando {@link ErrorResponse} y
     * {@link ObjectMapper} para mantener el mismo formato que el resto de
     * errores (timestamp formateado, status, message, path) en vez de
     * construir el JSON con {@code String.format}.
     */
    private void writeRateLimitExceededResponse(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getRequestsPerMinute()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Retry-After-Seconds", "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse body = new ErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                String.format("Limite de peticiones excedido. Maximo %d peticiones por minuto.",
                        rateLimitProperties.getRequestsPerMinute()),
                request.getRequestURI(),
                LocalDateTime.now()
        );

        OBJECT_MAPPER.writeValue(response.getWriter(), body);
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

    /**
     * Devuelve la IP real del cliente. Spring Boot, cuando
     * {@code server.forward-headers-strategy} está en {@code framework} o
     * {@code native}, ya resuelve {@code request.getRemoteAddr()} a la IP
     * propagada por el proxy de confianza. Si no hay proxy configurado, este
     * método cae a la IP TCP directa, que tampoco es spoofable.
     */
    private String getClientIP(HttpServletRequest request) {
        return request.getRemoteAddr();
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
