# CLAUDE.md - Project Guidelines

## Project Overview
High-performance REST API for querying Mexican postal codes (ZIP codes). Built with **Spring Boot 4.0.6** and **Java 25**. Data is loaded from SEPOMEX's `CPdescarga.txt` file into in-memory data structures at startup.

## Build & Run
```bash
mvn clean package              # Build JAR
mvn spring-boot:run            # Run with dev profile
mvn test                       # Run all 80 tests (service + controller + rate-limit + health + context)
mvn verify                     # Tests + JaCoCo coverage report (target/site/jacoco/)
mvn spring-boot:run -Dspring-boot.run.profiles=prod  # Run with production profile
```

## Architecture

### Data Flow
1. **Startup**: `CPdescarga.txt` is loaded in `@PostConstruct`, parsed line-by-line, and indexed into multiple in-memory data structures. The load is **fail-fast**: if the catalog is missing or ends up empty, startup is aborted (`IllegalStateException`) instead of leaving a running-but-broken service.
2. **Indexing**: Four index structures for different access patterns. They are populated sequentially during the single-threaded `@PostConstruct` and treated as **read-only after load**, so plain (non-concurrent) collections are used to avoid synchronization overhead on the hot read path:
   - `HashMap<String, ZipCode>` - O(1) direct lookup by zip code
   - `TreeMap<String, ZipCode>` (`NavigableMap`) - O(log n) prefix search (autocomplete) via `subMap()`
   - `Map<String, List<ZipCode>>` - Inverted index by normalized federal entity; each bucket is an **immutable list pre-sorted by zip code at load time**
   - `Map<String, List<ZipCode>>` - Inverted index by normalized municipality, same pre-sorted buckets
   (buckets within one index are disjoint — each ZipCode has exactly one entity/municipality — so no Set dedup is needed and single-key matches are served with zero copies)
3. **Pre-computation**: Statistics, federal entities list, and the municipalities-by-entity index are computed once at startup (immutable after load). A SHA-256 checksum of the source catalog is computed in the same read pass and exposed via `/zip-codes/stats` (`catalogChecksum`/`catalogSource`/`loadedAt`) for data-freshness visibility; it also feeds the global weak ETag (`EtagInterceptor`).
4. **Caching**: 1 Spring-managed Caffeine region (`municipalitiesByEntity`) plus 4 manually-managed Caffeine caches inside `ZipCodeService` (`partialPrefixCache`, `entitySearchCache`, `municipalitySearchCache`, `advancedSearchCache`). Manual caches sidestep Spring's self-invocation pitfall, give per-key locking (no stampedes) and enable **negative caching**: search caches are keyed by normalized term/filter only (never page/size), store the **full sorted result list** (empty included), and pagination is a `subList` at read time; `ZipCodeNotFoundException` is still thrown per-request when the cached list is empty. All caches report metrics via `CaffeineCacheMetrics`.
5. **Serving**: REST endpoints in `Controller.java` (contract in `ZipCodeApi` interface) with pagination, validation, and Swagger docs. GETs under `/zip-codes/**` carry a catalog-versioned weak ETag; `If-None-Match` hits return 304 from `preHandle` without running the handler.

### Key Design Decisions
- **No database**: All data in memory (~200MB RAM) for sub-millisecond lookups
- **Pre-computed normalized fields**: `ZipCode` stores `normalizedFederalEntity` and `normalizedMunicipality` (computed at load time) to avoid NFD normalization in hot search paths
- **`@EqualsAndHashCode(of = "zipCode")`**: ZipCode identity is based solely on the zip code string, not the settlements list (each ZipCode's `settlements` list is replaced with an immutable `List.copyOf(...)` after load, and `Settlements` itself is an immutable record)
- **Term-keyed search caches (manual Caffeine)**: entity/municipality/advanced caches store the full sorted result list per normalized term — including empty lists (negative caching) — and never include page/size in the key. Chosen over `@Cacheable` deliberately: per-key locking, no self-invocation trap, and the 404 can still be thrown per-request after a cache hit on an empty list. Don't convert them back to `@Cacheable`.
- **Sequential streams for small indices**: Federal entity index has ~32 entries, municipality ~2500. `parallelStream()` overhead exceeds benefit at these sizes
- **`TreeMap.subMap()`**: Prefix search uses sorted map range query instead of full scan
- **Low-cardinality metrics**: Search metrics use type tags (direct/federal_entity/municipality/partial) instead of per-zipcode counters to avoid Prometheus series explosion
- **Caffeine for rate limit buckets**: Auto-eviction after 5 min of inactivity prevents memory leaks vs unbounded ConcurrentHashMap. `X-RateLimit-Limit` = sustained rate, `X-RateLimit-Burst` = real bucket capacity; unset `burst-capacity` defaults to `requests-per-minute`
- **Canonical String pool at load**: low-cardinality fields (federal entity ~32, municipality ~2500, settlement/zone types) are deduplicated during parsing so the ~145k `ZipCode` objects share single String instances, cutting heap footprint; their normalization is memoized (once per distinct value, not per line)
- **Catalog-versioned ETag**: single process-wide weak ETag (`W/"<checksum16>-<version>"`) valid for every `/zip-codes/**` URL because responses are pure functions of (catalog, URL); 304s short-circuit in `preHandle` (no search, no serialization)
- **Liveness/Readiness probes**: Actuator health groups (`/actuator/health/liveness` and `/readiness`); the `zipCode` indicator feeds the readiness group so traffic is only routed once the catalog is loaded
- **Docker CDS training run**: the image boots the Spring context at build time (`spring.context.exit=onRefresh`) to generate a CDS archive; GC flags must match between training and runtime. Spring AOT was evaluated and rejected (it would freeze profile/property conditions at build time; this app switches between 5 runtime profiles)

### Project Structure
```
src/main/java/com/coderalexis/CodigoPostalApi/
  config/         # CacheConfiguration, CacheControlInterceptor, MetricsConfiguration, RateLimitInterceptor, SwaggerConfiguration, CacheWarmupRunner, WebMvcConfiguration, RateLimitProperties
  controller/     # ZipCodeApi (REST contract + OpenAPI/validation) + Controller.java (implementation), all under /zip-codes
  exceptions/     # GlobalExceptionHandler, ZipCodeNotFoundException, ErrorResponse
  health/         # ZipCodeHealthIndicator
  model/          # ZipCode, Settlements, ZipCodeSimplified, FederalEntity, PagedResponse, ZipCodeStats, AdvancedSearchRequest
  service/        # ZipCodeService - core business logic and data loading
  util/           # Util.java - string normalization (NFD + diacritics removal)
```

### Configuration Profiles
- `dev` - Rate limiting OFF, debug logging, all actuator endpoints
- `qa` - Rate limiting 1000 req/min, INFO logging
- `prod` - Rate limiting 100 req/min, WARN logging, actuator on port 9090
- `railway` - Rate limiting 60 req/min, dynamic port from $PORT
- `test` - Rate limiting OFF, WARN logging

## Code Conventions
- Java 25 features: virtual threads, compact object headers (JVM flag), records where appropriate
- Lombok: `@Getter/@Setter/@ToString` preferred over `@Data` when custom `equals/hashCode` needed
- Accent-insensitive search: all text comparisons go through `Util.normalizeString()` (NFD decomposition + diacritics removal + lowercase)
- Error messages in Spanish
- Validation: Jakarta Bean Validation on controller parameters
- JSON: snake_case via `@JsonProperty` annotations

## Performance Notes
- Direct zip code lookup: O(1) HashMap (no cache: Map access is already O(1))
- Partial/prefix search: O(log n + k) via TreeMap (NavigableMap) `subMap()`, results cached per-prefix in `partialPrefixCache`
- Federal entity/municipality search: O(index keys) scan only on cache miss; buckets are pre-sorted at load so no per-request sorting; cache hit + pagination = O(page) `subList`
- Advanced search: Uses inverted indices as starting point; the full filtered list is computed once per filter combination and cached (worst case — settlement-only filter scanning ~145k entries — pays that cost once)
- Negative caching: not-found terms/filters are cached as empty lists; repeated 404 queries never re-scan indices
- Statistics & federal entities: Pre-computed at startup, O(1) retrieval
- Conditional requests: catalog-versioned ETag → `304 Not Modified` before handler execution
- HTTP/2 enabled for connection multiplexing
- Gzip compression for responses > 1KB
- Cache warmup on startup for common queries (parallel); term-keyed caches mean a warmed term covers all its pages
- Docker image ships a CDS archive generated at build time (1-3s faster cold start)
