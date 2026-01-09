package com.coderalexis.CodigoPostalApi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Propiedades de configuración para Rate Limiting.
 * Se mapean desde application-{profile}.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    /**
     * Habilitar o deshabilitar rate limiting globalmente
     */
    private boolean enabled = false;

    /**
     * Número de peticiones permitidas por minuto
     */
    private int requestsPerMinute = 100;

    /**
     * Si se debe aplicar rate limiting por IP
     */
    private boolean ipBased = true;

    /**
     * Capacidad de ráfaga (burst capacity)
     * Permite un pico temporal de peticiones
     */
    private int burstCapacity = 20;

    /**
     * Lista de IPs en whitelist (sin rate limiting)
     */
    private List<String> whitelist = new ArrayList<>();
}
