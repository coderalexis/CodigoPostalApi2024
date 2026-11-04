package com.coderalexis.CodigoPostalApi.config;

import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

@RestController
@Profile("benchmark")
@RequestMapping("/internal/benchmark")
public class BenchmarkController {
    private final ZipCodeService service;
    private final CacheManager caches;
    public BenchmarkController(ZipCodeService service, CacheManager caches) { this.service = service; this.caches = caches; }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Boolean>> reset(HttpServletRequest request) {
        if (!isLoopback(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        service.clearSearchCachesForBenchmark();
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
        return ResponseEntity.ok(Map.of("cleared", true));
    }

    @GetMapping("/memory")
    public ResponseEntity<Map<String, Long>> memory(HttpServletRequest request) {
        if (!isLoopback(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(Map.of("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                "heap_committed_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted()));
    }

    private boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException ignored) { }
        return false;
    }
}
