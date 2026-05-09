# CLAUDE.md - Project Guidelines

## Project Overview
High-performance REST API for querying Mexican postal codes (ZIP codes). Built with **Spring Boot 4.0.2** and **Java 25**. Data is loaded from SEPOMEX's `CPdescarga.txt` file into in-memory data structures at startup.

## Build & Run
```bash
mvn clean package              # Build JAR
mvn spring-boot:run            # Run with dev profile
mvn test                       # Run all 33 tests (14 unit + 18 integration + 1 context)
mvn spring-boot:run -Dspring-boot.run.profiles=prod  # Run with production profile
```

## Architecture

### Data Flow
1. **Startup**: `CPdescarga.txt` is loaded in `@PostConstruct`, parsed line-by-line, and indexed into multiple concurrent data structures
2. **Indexing**: Four index structures for different access patterns:
   - `ConcurrentHashMap<String, ZipCode>` - O(1) direct lookup by zip code
   - `ConcurrentSkipListMap<String, ZipCode>` - O(log n) prefix search (autocomplete)
   - `Map<String, Set<ZipCode>>` - Inverted index by normalized federal entity
   - `Map<String, Set<ZipCode>>` - Inverted index by normalized municipality
3. **Pre-computation**: Statistics and federal entities list are computed once at startup (immutable after load)
4. **Caching**: 7 Caffeine cache regions with different TTLs per data type
5. **Serving**: REST endpoints in `Controller.java` with pagination, validation, and Swagger docs

### Key Design Decisions
- **No database**: All data in memory (~200MB RAM) for sub-millisecond lookups
- **Pre-computed normalized fields**: `ZipCode` stores `normalizedFederalEntity` and `normalizedMunicipality` (computed at load time) to avoid NFD normalization in hot search paths
- **`@EqualsAndHashCode(of = "zipCode")`**: ZipCode identity is based solely on the zip code string, not the mutable settlements list. This is critical for correctness in the inverted index Sets
- **Sequential streams for small indices**: Federal entity index has ~32 entries, municipality ~2500. `parallelStream()` overhead exceeds benefit at these sizes
- **`ConcurrentSkipListMap.subMap()`**: Prefix search uses sorted map range query instead of full scan
- **Low-cardinality metrics**: Search metrics use type tags (direct/federal_entity/municipality/partial) instead of per-zipcode counters to avoid Prometheus series explosion
- **Caffeine for rate limit buckets**: Auto-eviction after 5 min of inactivity prevents memory leaks vs unbounded ConcurrentHashMap

### Project Structure
```
src/main/java/com/coderalexis/CodigoPostalApi/
  config/         # CacheConfiguration, MetricsConfiguration, RateLimitInterceptor, SwaggerConfiguration, CacheWarmupRunner, WebMvcConfiguration, RateLimitProperties
  controller/     # Controller.java - all REST endpoints under /zip-codes
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
- Direct zip code lookup: O(1) HashMap + Caffeine cache
- Partial/prefix search: O(log n + k) via ConcurrentSkipListMap.subMap()
- Federal entity/municipality search: O(m) where m = matching index entries (not full scan)
- Advanced search: Uses inverted indices as starting point, reduces candidate set before filtering
- Statistics & federal entities: Pre-computed at startup, O(1) retrieval
- HTTP/2 enabled for connection multiplexing
- Gzip compression for responses > 1KB
- Cache warmup on startup for common queries (parallel)
