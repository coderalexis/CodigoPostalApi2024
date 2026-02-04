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

- **High Performance**: Handles 6,389+ requests/second with 99% of requests under 190ms
- **Smart Caching**: Multi-level Caffeine cache with specific TTL per data type
- **Pagination**: Consistent pagination across all search endpoints
- **Partial Code Search**: Autocomplete support with partial zip code matching
- **Advanced Search**: Multi-filter search by state, municipality, settlement, and zone type
- **Simplified Response**: Optional lightweight response format without settlement details
- **Robust Validation**: Data validation with comprehensive error handling
- **Health Checks**: Custom health indicators for data loading status
- **Multi-Environment**: Dev, QA, Production, and Railway profiles
- **Rate Limiting**: Token bucket algorithm to prevent API abuse
- **Metrics**: Custom business metrics with Prometheus integration
- **Accent-Insensitive Search**: Searches work regardless of accents or case
- **Auto Encoding Detection**: Automatic detection of ISO-8859-1/UTF-8 file encoding
- **Complete Documentation**: Interactive Swagger UI with examples
- **Cloud-Native**: Optimized for Railway, Docker, and container deployments

## Technologies

| Technology | Version | Description |
|------------|---------|-------------|
| **Java** | 25 LTS | Latest LTS with Compact Object Headers |
| **Spring Boot** | 4.0.2 | Latest framework with modular architecture |
| **Spring Web** | 7.0 | REST API support |
| **Spring Cache** | - | Caching abstraction |
| **Caffeine** | - | High-performance in-memory cache |
| **Bucket4j** | 8.10.1 | Rate limiting implementation |
| **Lombok** | 1.18.40 | Reduce boilerplate code |
| **Spring Boot Actuator** | - | Production-ready features |
| **Micrometer** | - | Application metrics |
| **Prometheus** | - | Metrics collection |
| **SpringDoc OpenAPI** | 3.0.1 | Swagger UI & API documentation |
| **JUnit 6** | - | Testing framework |
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
  "locality": "Ciudad de México",
  "federal_entity": "Ciudad de México",
  "municipality": "Álvaro Obregón",
  "settlements": [
    {
      "name": "San Ángel",
      "zone_type": "Urbano",
      "settlement_type": "Colonia"
    }
  ]
}
```

### 2. Partial Zip Code Search (Autocomplete)

**Endpoint:** `GET /zip-codes/search?code={prefix}&limit={n}&simplified={bool}`

**Description:** Search zip codes by prefix for autocomplete functionality.

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

**Description:** Get all 32 Mexican states with statistics.

**Example Response:**
```json
[
  {
    "name": "Aguascalientes",
    "zip_codes_count": 358,
    "municipalities_count": 11
  },
  {
    "name": "Ciudad de México",
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

**Description:** Search postal codes by state name with pagination.

**Parameters:**
- `federal_entity` (required): State name (partial match, accent-insensitive)
- `page` (optional): Page number, default 0
- `size` (optional): Page size, default 20, max 100

**Example Request:**
```bash
curl "http://localhost:8080/zip-codes?federal_entity=Ciudad%20de%20México&page=0&size=10"
```

### 7. Search by Municipality

**Endpoint:** `GET /zip-codes/by-municipality?municipality={name}&page={page}&size={size}`

**Description:** Search postal codes by municipality name with pagination.

### 8. Advanced Search

**Endpoint:** `GET /zip-codes/advanced`

**Description:** Search with multiple filters combined.

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

**Description:** Get general statistics about the loaded data.

**Example Response:**
```json
{
  "totalZipCodes": 31918,
  "totalFederalEntities": 32,
  "totalMunicipalities": 2337,
  "totalSettlements": 157424
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

### Response Headers

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Retry-After-Seconds: 60
```

## Caching

### Multi-Level Cache Strategy

| Cache Name | Capacity | TTL | Use Case |
|------------|----------|-----|----------|
| `zipcodes` | 10,000 | 1 hour | Individual zip code lookups |
| `federalEntitySearch` | 100 | 15 min | State-based searches |
| `municipalitySearch` | 200 | 15 min | Municipality searches |
| `partialSearch` | 500 | 10 min | Autocomplete searches |
| `federalEntities` | 1 | 1 hour | States list |
| `municipalitiesByEntity` | 50 | 30 min | Municipalities by state |
| `advancedSearch` | 100 | 10 min | Multi-filter searches |

## Monitoring and Metrics

### Actuator Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus
```

### Custom Business Metrics

```promql
zipcode_search_direct_total           # Direct zip code searches
zipcode_search_federal_entity_total   # State searches
zipcode_search_duration_seconds       # Search latency histogram
zipcode_search_errors_total           # Error counters
```

## Testing

```bash
# Run all tests
mvn test

# Run with coverage
mvn test jacoco:report
```

### Test Coverage

- **Total tests:** 33
- **Unit tests:** 14 (ZipCodeServiceTest)
- **Integration tests:** 18 (ControllerTest)
- **Application test:** 1

## Performance

### Benchmark Results (Apache Bench)

| Metric | Value |
|--------|-------|
| Requests per second | 6,389 req/s |
| Time per request (mean) | 156 ms |
| Failed requests | 0 |
| 99th percentile | 190 ms |

### Java 25 Optimizations

The application leverages Java 25 LTS features:

- **Compact Object Headers**: Reduces object header from 12 to 8 bytes (~20% heap reduction)
- **ZGC Generational**: Low-latency garbage collector
- **Virtual Threads**: Enabled for high concurrency

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
- Non-root user for security
- Integrated health check
- JVM optimizations for containers:
  ```
  -XX:+UseCompactObjectHeaders
  -XX:+UseZGC
  -XX:+ZGenerational
  -XX:MaxRAMPercentage=70.0
  ```

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
├── src/main/java/com/coderalexis/CodigoPostalApi/
│   ├── config/           # Configuration classes
│   ├── controller/       # REST controllers
│   ├── exceptions/       # Exception handlers
│   ├── health/           # Health indicators
│   ├── model/            # Data models
│   ├── service/          # Business logic
│   └── util/             # Utilities
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   ├── application-qa.yml
│   ├── application-railway.yml
│   └── CPdescarga.txt
├── Dockerfile
├── railway.toml
└── pom.xml
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

### v3.0.0 (2026-02-04)
- **Upgraded to Java 25 LTS** with Compact Object Headers
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
