package com.coderalexis.CodigoPostalApi.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfiguration {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "zipcodes",
                "federalEntitySearch",
                "municipalitySearch"
        );

        cacheManager.registerCustomCache("zipcodes",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterAccess(1, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        cacheManager.registerCustomCache("federalEntitySearch",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(15, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        cacheManager.registerCustomCache("municipalitySearch",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(15, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        return cacheManager;
    }
}
