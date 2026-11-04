package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BenchmarkControllerTest {
    @Test void rejectsRemoteRequestsBeforeClearingCaches() {
        var service = mock(ZipCodeService.class);
        var controller = new BenchmarkController(service, new ConcurrentMapCacheManager());
        var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.1");
        assertEquals(403, controller.reset(request).getStatusCode().value());
        assertEquals(403, controller.memory(request).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void clearsAllCachesAndReportsHeapOnlyLocally() {
        var service = mock(ZipCodeService.class);
        var manager = new ConcurrentMapCacheManager("municipalitiesByEntity");
        var cache = manager.getCache("municipalitiesByEntity"); cache.put("key", "value");
        var controller = new BenchmarkController(service, manager);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        assertEquals(200, controller.reset(request).getStatusCode().value());
        verify(service).clearSearchCachesForBenchmark();
        assertNull(cache.get("key"));
        assertTrue(controller.memory(request).getBody().get("heap_committed_bytes") > 0);
    }
}
