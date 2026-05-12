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
                "federalEntitySearchPaged",
                "municipalitySearch",
                "municipalitySearchPaged",
                "partialSearch",
                "federalEntities",
                "municipalitiesByEntity",
                "advancedSearch",
                "advancedSearchPaged"
        );

        // Direct zip code lookup: O(1) in-memory Map access, no cache benefit.
        // Kept for backward compatibility with @Cacheable annotations.
        cacheManager.registerCustomCache("zipcodes",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterAccess(1, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        // Federal entity search: results can be large (full ZipCode objects with settlements).
        // Reduced size and TTL to avoid memory pressure. Consider caching only zip IDs if needed.
        cacheManager.registerCustomCache("federalEntitySearch",
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Paginated variant: more specific keys, separate cache entry.
        cacheManager.registerCustomCache("federalEntitySearchPaged",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Municipality search: results can be very large. Conservative cache settings.
        cacheManager.registerCustomCache("municipalitySearch",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Paginated municipality variant.
        cacheManager.registerCustomCache("municipalitySearchPaged",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Partial search (autocomplete): frequent reads, moderate cache.
        cacheManager.registerCustomCache("partialSearch",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Federal entities list: rarely changes, small result set.
        cacheManager.registerCustomCache("federalEntities",
                Caffeine.newBuilder()
                        .maximumSize(1)
                        .expireAfterWrite(1, TimeUnit.HOURS)
                        .recordStats()
                        .build());

        // Municipalities by entity: small result sets, moderate TTL.
        cacheManager.registerCustomCache("municipalitiesByEntity",
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Advanced search: can return large result sets. Conservative settings.
        // If memory usage is high, consider caching only zip code IDs instead of
        // full ZipCode objects (which include nested settlement lists).
        cacheManager.registerCustomCache("advancedSearch",
                Caffeine.newBuilder()
                        .maximumSize(25)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Paginated advanced search: smaller values than unpaged results, with
        // page-aware keys to reduce repeated filtering for common queries.
        cacheManager.registerCustomCache("advancedSearchPaged",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        return cacheManager;
    }
}