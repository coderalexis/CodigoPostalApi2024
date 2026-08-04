package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * ETag global versionado por catálogo para los endpoints REST. Toda respuesta
 * GET de {@code /zip-codes/**} es función pura de (versión del catálogo, URL),
 * así que un único validador por proceso es correcto: los clientes HTTP
 * comparan ETags por URL, y el valor sólo cambia cuando cambia el catálogo
 * (checksum SHA-256 ya computado en el load) o la versión de la app (que puede
 * alterar la serialización).
 *
 * <p>Se corta en {@code preHandle}: un {@code 304 Not Modified} no ejecuta la
 * búsqueda ni la serialización Jackson, a diferencia de
 * {@code ShallowEtagHeaderFilter}, que bufferiza el body completo y sólo ahorra
 * ancho de banda. El header se setea también aquí (no en {@code postHandle},
 * que para {@code @ResponseBody} llega tarde, con la respuesta ya escrita).</p>
 *
 * <p>ETag débil ({@code W/}) porque con compresión no se garantiza identidad
 * byte a byte entre representaciones.</p>
 */
@Component
public class EtagInterceptor implements HandlerInterceptor {

    private static final int CHECKSUM_PREFIX_LENGTH = 16;
    private static final String DEFAULT_VERSION = "0";

    private final ZipCodeService zipCodeService;
    private final String appVersion;
    // Calculado perezosamente en el primer request (el catálogo ya está cargado:
    // @PostConstruct corre antes de que el servidor acepte tráfico) y estable
    // durante toda la vida del proceso.
    private volatile String etag;

    public EtagInterceptor(ZipCodeService zipCodeService, ObjectProvider<BuildProperties> buildProperties) {
        this.zipCodeService = zipCodeService;
        BuildProperties properties = buildProperties.getIfAvailable();
        this.appVersion = properties != null ? properties.getVersion() : DEFAULT_VERSION;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return true;
        }

        String currentEtag = currentEtag();
        if (currentEtag == null) {
            return true;
        }

        // Comparación débil por substring: el ETag termina en comilla, así que un
        // validador no puede ser prefijo de otro. No se especial-casea "*" porque
        // significa "si existe alguna representación" y eso no puede saberse sin
        // ejecutar el handler (una URL que daría 404 no debe responder 304).
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(currentEtag)) {
            response.setHeader(HttpHeaders.ETAG, currentEtag);
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            return false;
        }

        response.setHeader(HttpHeaders.ETAG, currentEtag);
        return true;
    }

    private String currentEtag() {
        String cached = etag;
        if (cached != null) {
            return cached;
        }

        ZipCodeStats stats = zipCodeService.getStatistics();
        if (stats == null || stats.getCatalogChecksum() == null) {
            return null;
        }

        String checksum = stats.getCatalogChecksum();
        String prefix = checksum.length() > CHECKSUM_PREFIX_LENGTH
                ? checksum.substring(0, CHECKSUM_PREFIX_LENGTH)
                : checksum;
        cached = "W/\"" + prefix + "-" + appVersion + "\"";
        etag = cached;
        return cached;
    }
}
