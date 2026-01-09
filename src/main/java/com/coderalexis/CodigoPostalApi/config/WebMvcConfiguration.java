package com.coderalexis.CodigoPostalApi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración de MVC para registrar interceptores.
 */
@Slf4j
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final RateLimitProperties rateLimitProperties;

    public WebMvcConfiguration(RateLimitInterceptor rateLimitInterceptor,
                              RateLimitProperties rateLimitProperties) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (rateLimitProperties.isEnabled()) {
            log.info("✓ Rate Limiting HABILITADO: {} req/min, Burst: {}",
                rateLimitProperties.getRequestsPerMinute(),
                rateLimitProperties.getBurstCapacity());

            registry.addInterceptor(rateLimitInterceptor)
                    .addPathPatterns("/zip-codes/**")  // Solo aplicar a endpoints de la API
                    .excludePathPatterns(
                        "/actuator/**",      // No limitar endpoints de actuator
                        "/swagger-ui/**",    // No limitar Swagger UI
                        "/v3/api-docs/**"    // No limitar API docs
                    );
        } else {
            log.info("✗ Rate Limiting DESHABILITADO (perfil de desarrollo)");
        }
    }
}
