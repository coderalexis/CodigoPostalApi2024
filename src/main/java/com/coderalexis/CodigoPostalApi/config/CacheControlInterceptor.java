package com.coderalexis.CodigoPostalApi.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Añade {@code Cache-Control} a las respuestas REST exitosas según el patrón
 * de la URI. Fix #23: antes sólo los recursos estáticos llevaban Cache-Control,
 * con lo cual CDNs y clientes no podían cachear nada del API. Los TTL son
 * conservadores y reflejan la estabilidad real del catálogo SEPOMEX (los
 * códigos postales cambian con muy baja frecuencia).
 *
 * <p>No se aplica a respuestas de error (4xx/5xx) para evitar que un fallo
 * transitorio quede cacheado en intermediarios.</p>
 */
@Component
public class CacheControlInterceptor implements HandlerInterceptor {

    private static final String CACHE_CONTROL_HEADER = "Cache-Control";

    // LinkedHashMap preserva el orden de declaración; las reglas más específicas van primero.
    private static final Map<Pattern, String> RULES = new LinkedHashMap<>();

    static {
        // Datos muy estables: catálogo completo de estados.
        RULES.put(Pattern.compile("^/zip-codes/federal-entities/?$"),
                CacheControl.maxAge(6, TimeUnit.HOURS).cachePublic().getHeaderValue());

        // Datos estables a la escala de horas: lookup directo, asentamientos, stats, municipios.
        String oneHour = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic().getHeaderValue();
        RULES.put(Pattern.compile("^/zip-codes/\\d{5}$"), oneHour);
        RULES.put(Pattern.compile("^/zip-codes/\\d{5}/settlements$"), oneHour);
        RULES.put(Pattern.compile("^/zip-codes/stats$"), oneHour);
        RULES.put(Pattern.compile("^/zip-codes/federal-entities/[^/]+/municipalities$"), oneHour);

        // Búsquedas: respuesta más volátil por términos arbitrarios; TTL más corto.
        String halfHour = CacheControl.maxAge(30, TimeUnit.MINUTES).cachePublic().getHeaderValue();
        RULES.put(Pattern.compile("^/zip-codes/search$"), halfHour);
        RULES.put(Pattern.compile("^/zip-codes/by-municipality$"), halfHour);
        RULES.put(Pattern.compile("^/zip-codes/advanced$"), halfHour);
        // Listado por entidad federativa (raíz del recurso): /zip-codes?federal_entity=...
        RULES.put(Pattern.compile("^/zip-codes/?$"), halfHour);
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        if (ex != null || response.getStatus() >= 400) {
            // No cacheamos errores: un 404 transitorio no debería quedarse pegado en
            // un CDN.
            return;
        }

        if (response.containsHeader(CACHE_CONTROL_HEADER)) {
            // Ya configurado por el controlador, no sobreescribir.
            return;
        }

        String uri = request.getRequestURI();
        for (Map.Entry<Pattern, String> rule : RULES.entrySet()) {
            if (rule.getKey().matcher(uri).matches()) {
                response.setHeader(CACHE_CONTROL_HEADER, rule.getValue());
                return;
            }
        }
    }
}
