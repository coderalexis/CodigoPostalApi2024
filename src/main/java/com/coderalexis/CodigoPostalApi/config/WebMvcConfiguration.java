package com.coderalexis.CodigoPostalApi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Configuración de MVC para registrar interceptores.
 */
@Slf4j
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final RateLimitProperties rateLimitProperties;
    private final CacheControlInterceptor cacheControlInterceptor;

    public WebMvcConfiguration(RateLimitInterceptor rateLimitInterceptor,
                              RateLimitProperties rateLimitProperties,
                              CacheControlInterceptor cacheControlInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.rateLimitProperties = rateLimitProperties;
        this.cacheControlInterceptor = cacheControlInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Cache-Control para recursos estáticos (favicon, etc.). Las respuestas REST
        // usan CacheControlInterceptor (#23) que aplica TTLs específicos por endpoint.
        registry.addResourceHandler("/**")
                .setCacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Cache-Control para endpoints REST: se registra primero para que las
        // respuestas exitosas siempre lleven el header adecuado.
        registry.addInterceptor(cacheControlInterceptor)
                .addPathPatterns("/zip-codes/**", "/zip-codes");

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
