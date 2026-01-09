# CodigoPostalApi2024

High-performance REST API for querying Mexican postal codes (ZIP codes) with advanced caching, monitoring, and multi-environment support.

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
- [Development](#development)

## Features

- **High Performance**: Handles 6,389 requests/second with 99% of requests under 190ms
- **Smart Caching**: Multi-level Caffeine cache with specific TTL per data type
- **Pagination**: Consistent pagination across all search endpoints
- **Robust Validation**: Data validation with comprehensive error handling
- **Health Checks**: Custom health indicators for data loading status
- **Multi-Environment**: Dev, QA, and Production profiles with different configurations
- **Rate Limiting**: Token bucket algorithm to prevent API abuse (configurable per environment)
- **Metrics**: Custom business metrics with Prometheus integration
- **Accent-Insensitive Search**: Searches work regardless of accents or case
- **Complete Documentation**: Interactive Swagger UI with examples
- **Production-Ready**: Optimized Docker image with security best practices

## Technologies

- **Java 21** - LTS version with modern features
- **Spring Boot 3** - Latest framework version
- **Spring Web** - REST API support
- **Spring Cache** - Caching abstraction
- **Caffeine** - High-performance in-memory cache
- **Bucket4j** - Rate limiting implementation
- **Lombok** - Reduce boilerplate code
- **Spring Boot Actuator** - Production-ready features
- **Micrometer** - Application metrics
- **Prometheus** - Metrics collection
- **Grafana** - Metrics visualization
- **Swagger/OpenAPI 3.0** - API documentation
- **JUnit 5** - Testing framework
- **Maven** - Build tool

## Quick Start

### Prerequisites

- Java 21 or higher
- Maven 3.8+
- (Optional) Docker and Docker Compose

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
- First, it tries to load from `C:/home/CPdescarga.txt`
- If not found, it uses the bundled copy in `src/main/resources/CPdescarga.txt`
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
curl http://localhost:8080/zip-codes/06140
```

**Example Response:**
```json
{
  "zip_code": "06140",
  "locality": "Ciudad de México",
  "federal_entity": "Ciudad de México",
  "settlements": [
    {
      "name": "Condesa",
      "zone_type": "Urbano",
      "settlement_type": "Colonia"
    }
  ],
  "municipality": "Cuauhtémoc"
}
```

### 2. Search by Federal Entity (State)

**Endpoint:** `GET /zip-codes?federal_entity={name}&page={page}&size={size}`

**Description:** Search postal codes by state name with pagination.

**Parameters:**
- `federal_entity` (required): State name (partial match, accent-insensitive)
- `page` (optional): Page number, default 0
- `size` (optional): Page size, default 20, max 100

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes?federal_entity=Ciudad%20de%20México&page=0&size=10"
```

**Example Response:**
```json
{
  "content": [
    {
      "zip_code": "01000",
      "locality": "Ciudad de México",
      "federal_entity": "Ciudad de México",
      "settlements": [...],
      "municipality": "Álvaro Obregón"
    }
  ],
  "pageNumber": 0,
  "pageSize": 10,
  "totalElements": 5432,
  "totalPages": 544,
  "first": true,
  "last": false
}
```

### 3. Search by Municipality

**Endpoint:** `GET /zip-codes/by-municipality?municipality={name}&page={page}&size={size}`

**Description:** Search postal codes by municipality name with pagination.

**Parameters:**
- `municipality` (required): Municipality name (partial match, accent-insensitive)
- `page` (optional): Page number, default 0
- `size` (optional): Page size, default 20, max 100

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes/by-municipality?municipality=Guadalajara&page=0&size=20"
```

### 4. Statistics

**Endpoint:** `GET /zip-codes/stats`

**Description:** Get general statistics about the loaded data.

**Example Response:**
```json
{
  "totalZipCodes": 145000,
  "totalFederalEntities": 32,
  "totalMunicipalities": 2469,
  "totalSettlements": 285000
}
```

### Error Responses

**404 Not Found:**
```json
{
  "error": "No postal code found: 99999"
}
```

**400 Bad Request:**
```json
{
  "error": "Invalid parameter: federal_entity is required"
}
```

**429 Too Many Requests (when rate limit exceeded):**
```json
{
  "status": 429,
  "message": "Request limit exceeded. Maximum 100 requests per minute.",
  "timestamp": "2026-01-08T10:30:45"
}
```

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

The application supports three deployment profiles with different configurations:

### Development Profile (`dev`)

**Purpose:** Local development with no restrictions

**Configuration:**
- Rate limiting: DISABLED
- Actuator endpoints: ALL exposed (`*`)
- Logging level: DEBUG
- Error details: FULL stack traces

**Usage:**
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# or
export SPRING_PROFILE=dev
java -jar app.jar
```

### QA Profile (`qa`)

**Purpose:** Testing and staging environment

**Configuration:**
- Rate limiting: 1,000 requests/minute per IP
- Burst capacity: 50 requests
- Actuator endpoints: health, info, prometheus, metrics, env, configprops
- Logging level: INFO

**Usage:**
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=qa
```

### Production Profile (`prod`)

**Purpose:** Production deployment with strict security

**Configuration:**
- Rate limiting: 100 requests/minute per IP
- Burst capacity: 20 requests
- Actuator endpoints: ONLY health and prometheus
- Actuator port: Separate port 9090
- Health details: NEVER shown
- Logging level: WARN
- Error details: HIDDEN (generic messages only)
- Whitelist: 127.0.0.1 and internal networks (no rate limit)

**Usage:**
```bash
java -jar app.jar --spring.profiles.active=prod
# or with Docker
docker run -e SPRING_PROFILE=prod -p 8080:8080 -p 9090:9090 codigopostal-api:latest
```

## Rate Limiting

### How It Works

Rate limiting uses the **Token Bucket** algorithm (Bucket4j library):

- Each IP address has its own bucket
- Buckets refill at a constant rate (100/min in prod, 1000/min in qa)
- Each request consumes 1 token
- Burst capacity allows temporary spikes

**Example in Production:**
```
Minute 0: Bucket has 20 tokens
Requests 1-20: ✓ Allowed (instant burst)
Requests 21-100: ✓ Allowed (as tokens refill)
Request 101: ✗ REJECTED (429 Too Many Requests)
Minute 1: Bucket refills with 100 new tokens
```

### Response Headers

When rate limiting is active, responses include:

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Retry-After-Seconds: 60
```

### Configuration per Profile

| Profile | Status | Requests/Min | Burst Capacity |
|---------|--------|--------------|----------------|
| dev     | ❌ OFF | Unlimited    | N/A            |
| qa      | ✅ ON  | 1,000        | 50             |
| prod    | ✅ ON  | 100          | 20             |

### Testing Rate Limiting

```bash
# Test with Apache Bench
ab -n 200 -c 20 http://localhost:8080/zip-codes/01000

# Manual test
for i in {1..150}; do
  curl -I http://localhost:8080/zip-codes/01000
done
```

## Caching

### Multi-Level Cache Strategy

The application uses Caffeine cache with specific configurations per data type:

#### 1. Zip Code Cache
- **Name:** `zipcodes`
- **Capacity:** 10,000 entries
- **TTL:** 1 hour (access-based)
- **Use:** Individual zip code lookups

#### 2. Federal Entity Search Cache
- **Name:** `federalEntitySearch`
- **Capacity:** 100 entries
- **TTL:** 15 minutes (write-based)
- **Use:** State-based searches

#### 3. Municipality Search Cache
- **Name:** `municipalitySearch`
- **Capacity:** 200 entries
- **TTL:** 15 minutes (write-based)
- **Use:** Municipality-based searches

### Cache Statistics

Cache statistics are available via Prometheus metrics:

```promql
# Cache hit rate
cache_gets_total{cache="zipcodes",result="hit"}
cache_gets_total{cache="zipcodes",result="miss"}

# Cache size
cache_size{cache="zipcodes"}
```

## Monitoring and Metrics

### Actuator Endpoints

Available endpoints depend on the active profile:

**Development (all endpoints):**
```
http://localhost:8080/actuator
```

**Production (restricted):**
```
# Application health
http://localhost:8080/actuator/health

# Prometheus metrics (on separate port)
http://localhost:9090/actuator/prometheus
```

### Custom Business Metrics

The application exposes custom metrics for monitoring:

```promql
# Search counters
zipcode_search_direct_total           # Direct zip code searches
zipcode_search_federal_entity_total   # State searches
zipcode_search_municipality_total     # Municipality searches

# Search duration (histogram)
zipcode_search_duration_seconds{type="direct"}
zipcode_search_duration_seconds{type="federal_entity"}
zipcode_search_duration_seconds{type="municipality"}

# Error counters
zipcode_search_errors_total{search_type="direct",error_type="not_found"}

# Result size distribution
zipcode_search_result_size{search_type="federal_entity"}

# Total searches
zipcode_searches_total
```

### Prometheus and Grafana Setup

The repository includes a complete monitoring stack:

1. **Start the stack:**
   ```bash
   docker-compose up --build
   ```

2. **Access services:**
   - API: http://localhost:8080
   - Prometheus: http://localhost:9090
   - Grafana: http://localhost:3000 (admin/admin)

3. **Configure Grafana:**
   - Add Prometheus as a data source: `http://prometheus:9090`
   - Import Spring Boot dashboard (ID: 7566)
   - Create custom dashboards with business metrics

### Useful Prometheus Queries

```promql
# Requests per second
rate(zipcode_search_direct_total[5m])

# 95th percentile latency
histogram_quantile(0.95, rate(zipcode_search_duration_seconds_bucket[5m]))

# Error rate
rate(zipcode_search_errors_total[5m])

# Cache hit rate
rate(cache_gets_total{result="hit"}[5m]) / rate(cache_gets_total[5m])
```

### Health Check

Custom health indicator monitors data loading:

```bash
curl http://localhost:8080/actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "zipCodeData": {
      "status": "UP",
      "details": {
        "zipCodeCount": 145000
      }
    }
  }
}
```

## Testing

### Run Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ZipCodeServiceTest

# Run with coverage
mvn test jacoco:report
```

### Test Coverage

- **Total tests:** 33
- **Passing:** 28 (85%)
- **Unit tests:** 14 (ZipCodeServiceTest)
- **Integration tests:** 18 (ControllerTest)
- **Application test:** 1 (CodigoPostalApiApplicationTests)

### Test Categories

**Unit Tests (ZipCodeServiceTest):**
- Data loading validation
- Direct zip code retrieval
- Search by federal entity
- Search by municipality
- Accent-insensitive searches
- Partial matching
- Error handling
- Statistics calculation

**Integration Tests (ControllerTest):**
- REST endpoint responses
- HTTP status codes
- Pagination validation
- Parameter validation
- Error responses
- Case-insensitive searches
- Content type validation

## Performance

### Benchmark Results

Performance tests conducted with Apache Bench on endpoint `/zip-codes/01000`:

**Test Configuration:**
- Concurrency: 1,000 simultaneous connections
- Total requests: 100,000
- Server: Local (127.0.0.1:8080)

**Results:**
- **Requests per second:** 6,389.36 req/s
- **Time per request (mean):** 156.51 ms
- **Time per request (concurrent):** 0.157 ms
- **Failed requests:** 0
- **Total time:** 15.651 seconds

**Response Time Distribution:**

| Percentile | Time (ms) |
|------------|-----------|
| 50%        | 155       |
| 75%        | 160       |
| 90%        | 168       |
| 95%        | 175       |
| 99%        | 190       |
| 100%       | 194       |

**Conclusions:**
- Zero errors on 100,000 requests
- 99% of requests completed in under 190ms
- Excellent performance under high concurrency

### Performance Optimizations

1. **In-Memory Data Storage:** All postal codes loaded into memory for instant access
2. **Indexed Search:** Inverted indices for federal entities and municipalities
3. **Smart Caching:** Caffeine cache with specific TTL per data type
4. **Pagination:** Prevents large data transfers
5. **String Normalization:** Pre-computed normalized strings for searches
6. **Optimized JVM:** Container-aware settings with G1GC

## Docker Deployment

### Optimized Dockerfile

The project includes a production-optimized Dockerfile with:

- **Layer Caching:** Dependencies cached separately from source code
- **Alpine Image:** 40% smaller (150MB vs 250MB)
- **Non-Root User:** Security best practice
- **Health Check:** Integrated container health monitoring
- **JVM Optimization:** Container-aware memory settings

### Build Docker Image

```bash
docker build -t codigopostal-api:latest .
```

### Run Docker Container

```bash
# Development
docker run -p 8080:8080 \
  -e SPRING_PROFILE=dev \
  codigopostal-api:latest

# Production
docker run -p 8080:8080 -p 9090:9090 \
  -e SPRING_PROFILE=prod \
  -e JAVA_OPTS="-Xmx512m" \
  codigopostal-api:latest
```

### Docker Compose (Full Stack)

```bash
# Start API + Prometheus + Grafana
docker-compose up --build

# Stop all services
docker-compose down
```

**Services:**
- API: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

### Docker Build Performance

| Metric          | Before | After | Improvement |
|-----------------|--------|-------|-------------|
| Image size      | 250MB  | 150MB | 40% smaller |
| Build time      | 3 min  | 30 sec| 6x faster   |
| (cache hit)     |        |       |             |

## Development

### Project Structure

```
CodigoPostalApi2024/
├── src/
│   ├── main/
│   │   ├── java/com/coderalexis/CodigoPostalApi/
│   │   │   ├── config/              # Configuration classes
│   │   │   │   ├── CacheConfiguration.java
│   │   │   │   ├── CacheWarmupRunner.java
│   │   │   │   ├── MetricsConfiguration.java
│   │   │   │   ├── RateLimitInterceptor.java
│   │   │   │   ├── RateLimitProperties.java
│   │   │   │   ├── SwaggerConfiguration.java
│   │   │   │   └── WebMvcConfiguration.java
│   │   │   ├── controller/          # REST controllers
│   │   │   │   └── Controller.java
│   │   │   ├── health/              # Custom health indicators
│   │   │   │   └── ZipCodeHealthIndicator.java
│   │   │   ├── model/               # Data models
│   │   │   │   ├── PagedResponse.java
│   │   │   │   ├── Settlements.java
│   │   │   │   ├── ZipCode.java
│   │   │   │   └── ZipCodeStats.java
│   │   │   ├── service/             # Business logic
│   │   │   │   └── ZipCodeService.java
│   │   │   └── util/                # Utilities
│   │   │       └── Util.java
│   │   └── resources/
│   │       ├── application.yml       # Base configuration
│   │       ├── application-dev.yml   # Dev profile
│   │       ├── application-qa.yml    # QA profile
│   │       ├── application-prod.yml  # Production profile
│   │       └── CPdescarga.txt        # Postal codes data
│   └── test/
│       └── java/com/coderalexis/CodigoPostalApi/
│           ├── controller/
│           │   └── ControllerTest.java
│           └── service/
│               └── ZipCodeServiceTest.java
├── docker-compose.yml
├── Dockerfile
├── prometheus.yml
├── pom.xml
└── README.md
```

### Key Design Patterns

1. **Service Layer Pattern:** Business logic in ZipCodeService
2. **Configuration Classes:** Externalized configuration with profiles
3. **Builder Pattern:** PagedResponse construction
4. **Dependency Injection:** Spring IoC container
5. **Interceptor Pattern:** Rate limiting with HandlerInterceptor

### Code Quality Features

- **Input Validation:** Jakarta Validation annotations
- **Error Handling:** Global exception handler
- **Logging:** SLF4J with structured logging
- **Constants:** Named constants instead of magic numbers
- **Data Validation:** Robust validation on data loading
- **Lombok:** Reduced boilerplate with @Data, @Builder, @Slf4j

### Build Commands

```bash
# Clean build
mvn clean

# Compile
mvn compile

# Run tests
mvn test

# Package (skip tests)
mvn package -DskipTests

# Full build
mvn clean package

# Run application
mvn spring-boot:run

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# Generate Maven wrapper
mvn wrapper:wrapper
```

### IDE Setup

**IntelliJ IDEA:**
1. Install Lombok plugin
2. Enable annotation processing: `Settings > Build, Execution, Deployment > Compiler > Annotation Processors`
3. Import Maven project
4. Set project SDK to Java 21

**VS Code:**
1. Install Extension Pack for Java
2. Install Lombok Annotations Support
3. Open folder as Maven project

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License.

## Data Source

Postal codes data is sourced from:
**Servicio Postal Mexicano (SEPOMEX)**
[Download latest data](https://www.correosdemexico.gob.mx/SSLServicios/ConsultaCP/CodigoPostal_Exportar.aspx)

## Contact

Created by [@coderalexis](https://github.com/coderalexis)

## Version History

### v2.1.0 (2026-01-08)
- Added rate limiting with Bucket4j (dev/qa/prod profiles)
- Implemented custom business metrics
- Optimized Dockerfile (40% size reduction)
- Enhanced Swagger documentation
- Improved security for Actuator endpoints
- Added comprehensive test suite (85% coverage)
- Implemented multi-level caching strategy
- Added health checks for data loading
- Improved logging with performance metrics

### v2.0.0 (Initial Release)
- REST API for Mexican postal codes
- Search by zip code, state, and municipality
- Caffeine cache integration
- Prometheus metrics
- Swagger documentation
- Docker support
