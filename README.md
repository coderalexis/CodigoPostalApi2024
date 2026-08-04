# CodigoPostalApi

High-performance REST API for querying Mexican postal codes (ZIP codes) with advanced caching, monitoring, and cloud-native deployment support.

## Table of Contents

- [Features](#features)
- [Technologies](#technologies)
- [Quick Start](#quick-start)
- [API Endpoints](#api-endpoints)
- [Configuration Profiles](#configuration-profiles)
- [Rate Limiting](#rate-limiting)
- [Caching](#caching)
- [Monitoring and Metrics](#monitoring-and-metrics)
- [Testing](#testing)
- [Performance](#performance)
- [Docker Deployment](#docker-deployment)
- [Railway Deployment](#railway-deployment)
- [Development](#development)

## Features

- **High Performance**: Optimized data structures for sub-millisecond lookups
- **Smart Caching**: Multi-level Caffeine cache with specific TTL per data type
- **Pagination**: Consistent pagination across all search endpoints
- **Partial Code Search**: Autocomplete support with O(log n) prefix matching via sorted map
- **Advanced Search**: Multi-filter search by state, municipality, settlement, and zone type using inverted indices
- **Simplified Response**: Optional lightweight response format without settlement details
- **Pre-computed Data**: Statistics and federal entities computed at startup for instant retrieval
- **Robust Validation**: Data validation with comprehensive error handling
- **Health Checks**: Custom health indicators for data loading status
- **Multi-Environment**: Dev, QA, Production, and Railway profiles
- **Rate Limiting**: Token bucket algorithm with Caffeine-backed auto-eviction
- **Metrics**: Low-cardinality business metrics with Prometheus integration
- **Accent-Insensitive Search**: Searches work regardless of accents or case
- **Auto Encoding Detection**: Automatic detection of ISO-8859-1/UTF-8 file encoding
- **HTTP/2**: Enabled for connection multiplexing and reduced latency
- **Complete Documentation**: Interactive Swagger UI with examples
- **Cloud-Native**: Optimized for Railway, Docker, and container deployments

## Technologies

| Technology | Version | Description |
|------------|---------|-------------|
| **Java** | 25 | Latest with Compact Object Headers and Virtual Threads |
| **Spring Boot** | 4.0.2 | Latest framework with modular architecture |
| **Spring Web** | - | REST API support with HTTP/2 |
| **Spring Cache** | - | Caching abstraction |
| **Caffeine** | - | High-performance in-memory cache |
| **Bucket4j** | 8.10.1 | Rate limiting implementation |
| **Lombok** | 1.18.40 | Reduce boilerplate code |
| **Spring Boot Actuator** | - | Production-ready features |
| **Micrometer** | - | Application metrics |
| **Prometheus** | - | Metrics collection |
| **SpringDoc OpenAPI** | 3.0.1 | Swagger UI & API documentation |
| **JUnit 5** | - | Testing framework (Jupiter) |
| **Maven** | 3.8+ | Build tool |

## Quick Start

### Prerequisites

- Java 25 or higher
- Maven 3.8+
- (Optional) Docker

### Clone and Run

```bash
# Clone the repository
git clone https://github.com/coderalexis/CodigoPostalApi2024.git
cd CodigoPostalApi2024

# Build the project
mvn clean package

# Run with default profile (dev)
mvn spring-boot:run

# Or run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# Or run the JAR
java -jar target/CodigoPostalApi-*.jar --spring.profiles.active=prod
```

The API will be available at `http://localhost:8080`

### Data Source

The application uses the `CPdescarga.txt` file containing Mexican postal codes:
- By default, it loads from `classpath:CPdescarga.txt` (bundled in JAR)
- Can be overridden with environment variable: `ZIPCODE_FILE_PATH`
- Supports both UTF-8 and ISO-8859-1 encoding (auto-detected)
- Download the latest version [here](https://www.correosdemexico.gob.mx/SSLServicios/ConsultaCP/CodigoPostal_Exportar.aspx)

## API Endpoints

### Base URL
```
http://localhost:8080
```

### 1. Search by Zip Code

**Endpoint:** `GET /zip-codes/{zipcode}`

**Description:** Retrieve complete information for a specific postal code.

**Example Request:**
```bash
curl http://localhost:8080/zip-codes/01000
```

**Example Response:**
```json
{
  "zip_code": "01000",
  "locality": "Ciudad de Mexico",
  "federal_entity": "Ciudad de Mexico",
  "municipality": "Alvaro Obregon",
  "settlements": [
    {
      "name": "San Angel",
      "zone_type": "Urbano",
      "settlement_type": "Colonia"
    }
  ]
}
```

### 2. Partial Zip Code Search (Autocomplete)

**Endpoint:** `GET /zip-codes/search?code={prefix}&limit={n}&simplified={bool}`

**Description:** Search zip codes by prefix using O(log n) sorted map range query.

**Parameters:**
- `code` (required): Partial zip code (1-5 digits)
- `limit` (optional): Max results, default 10, max 50
- `simplified` (optional): Return lightweight response, default false

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes/search?code=010&limit=5"
```

### 3. List All Federal Entities (States)

**Endpoint:** `GET /zip-codes/federal-entities`

**Description:** Get all 32 Mexican states with statistics. Pre-computed at startup for instant response.

**Example Response:**
```json
[
  {
    "name": "Aguascalientes",
    "zip_codes_count": 358,
    "municipalities_count": 11
  },
  {
    "name": "Ciudad de Mexico",
    "zip_codes_count": 1110,
    "municipalities_count": 16
  }
]
```

### 4. List Municipalities by State

**Endpoint:** `GET /zip-codes/federal-entities/{state}/municipalities`

**Description:** Get all municipalities for a specific state.

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes/federal-entities/jalisco/municipalities"
```

### 5. Get Settlements by Zip Code

**Endpoint:** `GET /zip-codes/{zipcode}/settlements`

**Description:** Get only the settlements (colonies) for a specific zip code.

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes/01000/settlements"
```

### 6. Search by Federal Entity (State)

**Endpoint:** `GET /zip-codes?federal_entity={name}&page={page}&size={size}`

**Description:** Search postal codes by state name with pagination. Uses inverted index for fast lookups.

**Parameters:**
- `federal_entity` (required): State name (partial match, accent-insensitive)
- `page` (optional): Page number, default 0
- `size` (optional): Page size, default 20, max 100

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes?federal_entity=Ciudad%20de%20Mexico&page=0&size=10"
```

### 7. Search by Municipality

**Endpoint:** `GET /zip-codes/by-municipality?municipality={name}&page={page}&size={size}`

**Description:** Search postal codes by municipality name with pagination. Uses inverted index.

### 8. Advanced Search

**Endpoint:** `GET /zip-codes/advanced`

**Description:** Search with multiple filters combined. Uses inverted indices as starting point to minimize scan scope.

**Parameters:**
- `federal_entity` (optional): State filter
- `municipality` (optional): Municipality filter
- `settlement` (optional): Settlement/colony name filter
- `settlement_type` (optional): Type filter (Colonia, Fraccionamiento, etc.)
- `zone_type` (optional): Zone filter (Urbano, Rural)
- `page` (optional): Page number, default 0
- `size` (optional): Page size, default 20, max 100
- `simplified` (optional): Return lightweight response

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes/advanced?federal_entity=jalisco&municipality=guadalajara&zone_type=urbano"
```

### 9. Statistics

**Endpoint:** `GET /zip-codes/stats`

**Description:** Get general statistics (pre-computed at startup, O(1) retrieval).

**Example Response:**
```json
{
  "totalZipCodes": 31918,
  "totalFederalEntities": 32,
  "totalMunicipalities": 2337,
  "totalSettlements": 157424,
  "loadedAt": "2026-06-13T10:15:30",
  "catalogChecksum": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "catalogSource": "classpath:CPdescarga.txt"
}
```

The `catalogChecksum` (SHA-256 of the source catalog) and `loadedAt`/`catalogSource`
let you verify which version of the SEPOMEX catalog the running instance is serving.

### Interactive Documentation

Access the Swagger UI for interactive API documentation:
```
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON specification:
```
http://localhost:8080/v3/api-docs
```

## Configuration Profiles

### Development Profile (`dev`)

- Rate limiting: DISABLED
- Actuator endpoints: ALL exposed
- Logging level: DEBUG
- Error details: FULL stack traces

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### QA Profile (`qa`)

- Rate limiting: 1,000 requests/minute per IP
- Burst capacity: 50 requests
- Logging level: INFO

### Production Profile (`prod`)

- Rate limiting: 100 requests/minute per IP
- Actuator port: Separate port 9090
- Health details: NEVER shown
- Logging level: WARN

### Railway Profile (`railway`)

- Port: Dynamic via `$PORT` environment variable
- Rate limiting: 60 requests/minute per IP
- Actuator: Same port as application
- Optimized for cloud deployment

```bash
# Set in Railway dashboard
SPRING_PROFILES_ACTIVE=railway
```

## Rate Limiting

### Configuration per Profile

| Profile | Status | Requests/Min | Burst Capacity |
|---------|--------|--------------|----------------|
| dev     | OFF | Unlimited    | N/A            |
| qa      | ON  | 1,000        | 50             |
| prod    | ON  | 100          | 20             |
| railway | ON  | 60           | 15             |

Rate limit buckets use Caffeine cache with automatic eviction after 5 minutes of inactivity, preventing memory leaks.

If `burst-capacity` is not configured, the effective bucket capacity equals `requests-per-minute` (the bucket can hold as many tokens as the advertised sustained rate).

### Response Headers

```
X-RateLimit-Limit: 100                 # Sustained rate (tokens refilled per minute)
X-RateLimit-Burst: 20                  # Real bucket capacity (upper bound of Remaining)
X-RateLimit-Remaining: 17
X-RateLimit-Retry-After-Seconds: 60    # Only on 429 responses
```

## Caching

### Multi-Level Cache Strategy

Search caches are managed directly with Caffeine inside `ZipCodeService` (avoids Spring's
self-invocation trap, gives per-key locking against cache stampedes, and enables
**negative caching**: terms with no results are cached too, so repeated not-found
queries never re-scan the indices — the 404 is still raised on every request).

Entity/municipality/advanced caches are keyed **by normalized term only** (not by page):
they store the full sorted result list and pagination is a cheap `subList`, so browsing
pages of the same term never re-computes or re-sorts anything.

| Cache | Managed by | Capacity | TTL | Use Case |
|------------|-----------|----------|-----|----------|
| `partialPrefix` | Caffeine (service) | 500 terms | 10 min (write) | Autocomplete prefix searches |
| `federalEntitySearch` | Caffeine (service) | 200 terms | 30 min (access) | Full sorted list per state term |
| `municipalitySearch` | Caffeine (service) | 200 terms | 30 min (access) | Full sorted list per municipality term |
| `advancedSearch` | Caffeine (service) | 500K refs (weight) | 15 min (access) | Full filtered list per filter combination |
| `municipalitiesByEntity` | Spring `@Cacheable` | 50 | 30 min (write) | Municipality names by state |

All caches (manual and Spring-managed) are wired to Micrometer via `CaffeineCacheMetrics`,
so hit ratios and evictions are visible in Prometheus (`cache_gets_total{cache="..."}`).

Cache warmup runs in parallel at startup for common queries; since search caches are
keyed by term, warming a term benefits every page and page size of that term.

### HTTP Caching (ETag / 304)

Every `GET /zip-codes/**` response carries a weak `ETag` derived from the catalog's
SHA-256 checksum plus the app version. Clients (and CDNs) can revalidate with
`If-None-Match`: a match short-circuits **before** the handler runs and returns
`304 Not Modified` with no body — no search, no JSON serialization. The validator only
changes when the catalog file or the application version changes.

```bash
curl -si http://localhost:8080/zip-codes/01000 | grep ETag
# ETag: W/"3f6c2a9b8d41e07a-3.0.0-SNAPSHOT"
curl -si -H 'If-None-Match: W/"3f6c2a9b8d41e07a-3.0.0-SNAPSHOT"' http://localhost:8080/zip-codes/01000
# HTTP/1.1 304 Not Modified
```

## Monitoring and Metrics

### Actuator Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus
```

### Custom Business Metrics

Low-cardinality metrics to avoid Prometheus series explosion:

```promql
zipcode_search_total{type="direct"}         # Direct zip code searches
zipcode_search_total{type="federal_entity"} # State searches
zipcode_search_total{type="municipality"}   # Municipality searches
zipcode_search_total{type="partial"}        # Partial/autocomplete searches
zipcode_search_duration_seconds             # Search latency histogram
zipcode_search_errors_total                 # Error counters
zipcode_search_result_size                  # Result size distribution
```

## Testing

```bash
# Run all tests
mvn test

# Run with coverage
mvn test jacoco:report
```

### Test Coverage

- **Total tests:** 80
- **Service tests:** 29 (ZipCodeServiceTest — includes negative caching, page consistency and cache-hit metrics)
- **Controller/integration tests:** 39 (ControllerTest — includes 400/405 error mapping and ETag/304)
- **Rate-limit tests:** 8 (RateLimitInterceptorTest)
- **Health indicator tests:** 3 (ZipCodeHealthIndicatorTest)
- **Application context test:** 1

The JaCoCo HTML report is generated at `target/site/jacoco/index.html` (also produced by
`mvn verify` and uploaded as a CI artifact).

Tests run automatically on every push and pull request via GitHub Actions
(`.github/workflows/ci.yml`), which also builds the Docker image (including the CDS
training run) to catch Dockerfile regressions.

## Performance

### Data Structure Complexity

| Operation | Complexity | Data Structure |
|-----------|-----------|----------------|
| Direct zip code lookup | O(1) | HashMap |
| Prefix/autocomplete search | O(log n + k) | TreeMap (NavigableMap) `subMap()` |
| Federal entity search | O(m) on miss, O(page) on hit | Inverted index (32 pre-sorted buckets) + term-keyed cache |
| Municipality search | O(m) on miss, O(page) on hit | Inverted index (~2500 pre-sorted buckets) + term-keyed cache |
| Advanced search | O(candidates) once per filter combo | Inverted index as starting point + term-keyed cache |
| Statistics | O(1) | Pre-computed at startup |
| Federal entities list | O(1) | Pre-computed at startup |

### Optimizations Applied

- **Pre-sorted inverted index buckets**: each entity/municipality bucket is sorted once at
  load time and frozen as an immutable list — paginated searches never re-sort per request
- **Term-keyed search caches with negative caching**: full sorted result lists cached per
  normalized term (empty results included); pagination is a `subList`, cold keys are
  computed once thanks to Caffeine per-key locking (no cache stampede)
- **Pre-computed normalized fields**: NFD normalization done at load time, not at query time
- **Memoized normalization in the loader**: low-cardinality settlement/zone types are
  normalized once per distinct value instead of once per line (~1.5M redundant NFD passes removed)
- **TreeMap (NavigableMap)**: Prefix search uses `subMap()` range query instead of O(n) full scan
- **Inverted indices for advanced search**: Reduces candidate set before filtering
- **Canonical String pool at load**: repeated low-cardinality strings share single instances
- **Sequential streams for small collections**: Avoids ForkJoinPool overhead on indices with <100 entries
- **Pre-compiled regex Pattern**: `PIPE_PATTERN` compiled once for file parsing
- **Pre-computed statistics**: Stats and federal entities calculated once at startup
- **Caffeine-backed rate limit buckets**: Auto-eviction prevents unbounded memory growth
- **Low-cardinality metrics**: Search type tags instead of per-zipcode counters
- **Parallel cache warmup**: CompletableFuture for concurrent cache preloading
- **HTTP/2**: Enabled for connection multiplexing
- **Response compression**: Gzip for responses > 1KB
- **Catalog-versioned ETag**: conditional requests return 304 before running the handler

### Java 25 Optimizations

- **Compact Object Headers**: Reduces object header from 12 to 8 bytes (~20% heap reduction)
- **ZGC**: Low-latency garbage collector (generational by default since JDK 23)
- **Virtual Threads**: Enabled for high concurrency
- **CDS archive baked into the Docker image**: classes pre-parsed during a build-time
  training run cut 1-3s of cold-start time

## Docker Deployment

### Build and Run

```bash
# Build image
docker build -t codigopostal-api:latest .

# Run container
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  codigopostal-api:latest
```

### Dockerfile Features

- Multi-stage build (JDK 25 for build, JRE 25 Alpine for runtime)
- Extracted JAR layout (`jarmode=tools extract`) — faster startup than the fat JAR
- **CDS training run at build time**: the Spring context boots once during `docker build`
  (`-Dspring.context.exit=onRefresh`), generating a class-data-sharing archive loaded at
  runtime via `-XX:SharedArchiveFile` (measured ~1.1s faster startup: 3.4s vs 4.8s average;
  also validates the catalog file at build time). Note: with ZGC only the class portion of
  the archive is used — archived heap objects are not supported by ZGC and the JVM logs a
  harmless `Cannot use CDS heap data` notice at startup.
- Non-root user for security
- **Profile-aware health check**: the `prod` profile moves actuator to port 9090, so the
  healthcheck probes `${MANAGEMENT_PORT:-$PORT}` and falls back to 9090 — the container
  reports healthy on every profile without extra configuration
- Log directory `/var/log/codigopostal-api` is pre-created and owned by the `spring` user
  (the `prod` profile writes there; without it Logback failed on every container start)
- JVM optimizations for containers:
  ```
  -XX:+UseCompactObjectHeaders
  -XX:+UseZGC
  -XX:MaxRAMPercentage=70.0
  -XX:SharedArchiveFile=/app/application/app.jsa
  ```
  (`-XX:+ZGenerational` was removed: generational ZGC is the default since JDK 23 and the
  flag no longer exists on JDK 25; `-XX:+UseContainerSupport` is the default since JDK 10)

## Railway Deployment

### Quick Deploy

1. Push code to GitHub
2. Create new project in [Railway](https://railway.app)
3. Connect your repository
4. Set environment variable:
   ```
   SPRING_PROFILES_ACTIVE=railway
   ```
5. Deploy!

### Configuration Files

- `railway.toml` - Railway-specific deployment configuration
- `application-railway.yml` - Railway profile settings

### Estimated Resources

- **RAM**: ~200-250 MB
- **Startup time**: ~3-5 seconds
- **Cost**: ~$5 USD/month (Hobby plan)

## Development

### Project Structure

```
CodigoPostalApi2024/
  src/main/java/com/coderalexis/CodigoPostalApi/
    config/           # Configuration classes
    controller/       # REST controllers
    exceptions/       # Exception handlers
    health/           # Health indicators
    model/            # Data models
    service/          # Business logic
    util/             # Utilities
  src/main/resources/
    application.yml
    application-dev.yml
    application-prod.yml
    application-qa.yml
    application-railway.yml
    CPdescarga.txt
  Dockerfile
  railway.toml
  CLAUDE.md
  pom.xml
```

### Build Commands

```bash
mvn clean compile      # Compile
mvn test               # Run tests
mvn package            # Build JAR
mvn spring-boot:run    # Run application
```

### IDE Setup

**IntelliJ IDEA:**
1. Install Lombok plugin
2. Enable annotation processing
3. Set project SDK to Java 25

## License

This project is licensed under the MIT License.

## Data Source

Postal codes data sourced from:
**Servicio Postal Mexicano (SEPOMEX)**
[Download latest data](https://www.correosdemexico.gob.mx/SSLServicios/ConsultaCP/CodigoPostal_Exportar.aspx)

## Version History

### v3.2.0 (2026-07-21)
- **Hot-path performance**:
  - Inverted index buckets pre-sorted at load time (no per-request re-sorting)
  - Search caches redesigned: keyed by normalized term only (page/size no longer churn
    cache keys), storing the full sorted list; pagination is a cheap `subList`
  - Negative caching: not-found terms are cached (repeated 404s no longer re-scan indices)
  - Per-key locking via Caffeine `get(key, fn)` eliminates cache stampedes on cold keys
  - Loader memoizes normalization of low-cardinality settlement/zone types (~1.5M
    redundant NFD passes removed at startup)
  - Removed dead `zipcodes` cache region; all manual caches now report metrics to Prometheus
- **API robustness**:
  - Missing required query param now returns 400 (was 500); type mismatches return 400;
    unsupported methods return 405 with `Allow` header
  - `ErrorResponse` semantics unified: `message` describes the error, `path` is always the request URI
  - New `X-RateLimit-Burst` header; unset `burst-capacity` now defaults to
    `requests-per-minute` instead of a silent cap of 20
- **HTTP caching & security**:
  - Weak ETag derived from the catalog SHA-256 + app version on all `/zip-codes/**` GETs;
    `If-None-Match` returns `304` before executing the handler
  - Railway profile no longer exposes `/actuator/prometheus` publicly by default
    (opt back in with `ACTUATOR_EXPOSURE`)
- **Build & deploy**:
  - JaCoCo coverage report wired into the build (`mvn verify` → `target/site/jacoco/`)
  - CI uploads coverage and builds the Docker image on every push/PR
  - Dockerfile: extracted JAR layout + build-time CDS training run (measured ~1.1s faster
    startup); removed obsolete `-XX:+ZGenerational`/`-XX:+UseContainerSupport` flags
  - `spring-boot-maven-plugin` now generates build-info (app version feeds the ETag)
- **Container fixes** (found while verifying the image):
  - Docker healthcheck was permanently failing under the `prod` profile (actuator listens
    on 9090 there, the check probed `$PORT`) — orchestrators would have restart-looped the
    container; now probes `MANAGEMENT_PORT` with a 9090 fallback
  - Pre-created `/var/log/codigopostal-api` owned by the `spring` user, fixing the Logback
    `FileNotFoundException` logged on every `prod` container start

### v3.1.0 (2026-02-07)
- **Performance optimizations**:
  - ConcurrentSkipListMap for O(log n) prefix search (was O(n) full scan)
  - Inverted indices used as starting point in advanced search (reduces candidates from ~32K to ~2K)
  - Pre-computed normalized fields on ZipCode model (eliminates runtime NFD normalization)
  - Pre-computed statistics and federal entities list at startup
  - Sequential streams for small collections (removed unnecessary parallelStream overhead)
  - Pre-compiled regex Pattern for file parsing
  - Parallel cache warmup with CompletableFuture
- **Bug fixes**:
  - Fixed ZipCode hashCode/equals to use only zipCode field (was including mutable settlements list)
- **Infrastructure improvements**:
  - Low-cardinality Prometheus metrics (prevents series explosion)
  - Caffeine-backed rate limit buckets with auto-eviction (prevents memory leaks)
  - HTTP/2 enabled for connection multiplexing
  - Jackson non-null serialization by default
- **Documentation**:
  - Added CLAUDE.md project guidelines
  - Fixed JUnit version reference (JUnit 5, not 6)
  - Updated performance documentation with complexity analysis

### v3.0.0 (2026-02-04)
- **Upgraded to Java 25** with Compact Object Headers
- **Upgraded to Spring Boot 4.0.2** with modular architecture
- Added partial zip code search (autocomplete)
- Added list all federal entities endpoint
- Added municipalities by state endpoint
- Added settlements by zip code endpoint
- Added advanced multi-filter search
- Added simplified response format option
- Added Railway deployment support
- Auto-detection of file encoding (ISO-8859-1/UTF-8)
- Improved error handling for browser requests
- Updated all dependencies for Java 25 compatibility

### v2.1.0 (2026-01-08)
- Added rate limiting with Bucket4j
- Implemented custom business metrics
- Optimized Dockerfile
- Enhanced Swagger documentation
- Multi-level caching strategy

### v2.0.0 (Initial Release)
- REST API for Mexican postal codes
- Search by zip code, state, and municipality
- Caffeine cache integration
- Prometheus metrics
- Docker support
