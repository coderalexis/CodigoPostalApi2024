package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Evalúa precondiciones después de validar y resolver el recurso, antes de serializarlo. */
@ControllerAdvice
@Order(0)
public class EtagResponseAdvice implements ResponseBodyAdvice<Object> {
    private final ZipCodeService service;
    private final String buildIdentity;

    public EtagResponseAdvice(ZipCodeService service, ObjectProvider<BuildProperties> properties) {
        this.service = service;
        BuildProperties build = properties.getIfAvailable();
        this.buildIdentity = build == null ? "dev" : build.getVersion() + ":" + build.getTime();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest in) ||
            !(response instanceof ServletServerHttpResponse out)) return body;
        var servletRequest = in.getServletRequest();
        var servletResponse = out.getServletResponse();
        String path = servletRequest.getRequestURI().substring(servletRequest.getContextPath().length());
        if (!(path.equals("/zip-codes") || path.startsWith("/zip-codes/")) ||
            !("GET".equals(servletRequest.getMethod()) || "HEAD".equals(servletRequest.getMethod())) ||
            servletResponse.getStatus() != 200 || body == null) return body;

        var stats = service.getStatistics();
        // loadedAt/origen también forman parte de /stats. Un reinicio invalida el validador.
        String identity = stats.getCatalogChecksum() + "\n" + buildIdentity + "\n" +
                stats.getLoadedAt() + "\n" + stats.getCatalogSource() + "\n" + request.getURI() + "\n" + contentType;
        String etag = "W/\"" + sha256(identity) + "\"";
        if (new ServletWebRequest(servletRequest, servletResponse).checkNotModified(etag)) return null;
        // Spring no evalúa el comodín para GET; aquí ya sabemos que existe una representación 200.
        if ("*".equals(java.util.Objects.toString(servletRequest.getHeader("If-None-Match"), "").trim())) {
            servletResponse.setHeader("ETag", etag);
            servletResponse.setStatus(304);
            return null;
        }
        return body;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
