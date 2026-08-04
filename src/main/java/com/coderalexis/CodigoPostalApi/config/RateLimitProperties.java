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
     * Capacidad de ráfaga (burst capacity): permite un pico temporal de
     * peticiones. {@code 0} (default) significa "no configurada": la capacidad
     * efectiva pasa a ser {@code requestsPerMinute}. Antes el default silencioso
     * era 20, con lo que un perfil que sólo configuraba requests-per-minute=1000
     * anunciaba 1000/min pero el bucket nunca podía contener más de 20 tokens.
     */
    private int burstCapacity = 0;

    /**
     * Lista de IPs en whitelist (sin rate limiting)
     */
    private List<String> whitelist = new ArrayList<>();

    /**
     * Capacidad real del bucket: el burst configurado o, si no se configuró,
     * la tasa sostenida por minuto.
     */
    public int getEffectiveBurstCapacity() {
        return burstCapacity > 0 ? burstCapacity : requestsPerMinute;
    }
}
