package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.http.server.*;
import org.springframework.mock.web.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtagResponseAdviceTest {
    @Test
    void changedLoadTimeMustInvalidateStats() {
        var service = mock(ZipCodeService.class);
        var stats = ZipCodeStats.builder().catalogChecksum("abc").catalogSource("test")
                .loadedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        when(service.getStatistics()).thenReturn(stats);
        var advice = new EtagResponseAdvice(service,
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class));
        var first = response(advice, stats, null);
        stats.setLoadedAt(stats.getLoadedAt().plusSeconds(1));
        var second = response(advice, stats, first.getHeader("ETag"));
        assertEquals(200, second.getStatus());
        assertNotEquals(first.getHeader("ETag"), second.getHeader("ETag"));
    }

    private MockHttpServletResponse response(EtagResponseAdvice advice, Object body, String etag) {
        var request = new MockHttpServletRequest("GET", "/zip-codes/stats");
        if (etag != null) request.addHeader("If-None-Match", etag);
        var response = new MockHttpServletResponse();
        advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(request), new ServletServerHttpResponse(response));
        return response;
    }
}
