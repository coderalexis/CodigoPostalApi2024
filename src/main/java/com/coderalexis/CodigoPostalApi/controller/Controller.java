package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.exceptions.ErrorResponse;
import com.coderalexis.CodigoPostalApi.model.AdvancedSearchRequest;
import com.coderalexis.CodigoPostalApi.model.FederalEntity;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeSimplified;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/zip-codes")
@Validated
public class Controller {

    private final ZipCodeService zipCodeService;

    public Controller(ZipCodeService zipCodeService) {
        this.zipCodeService = zipCodeService;
    }

    @Operation(
            summary = "🔍 Buscar código postal específico",
            description = """
                    Obtiene información completa de un código postal específico incluyendo:
                    - Entidad federativa
                    - Municipio
                    - Localidad
                    - Lista de asentamientos (colonias, fraccionamientos, etc.)

                    ### Ejemplos de uso:
                    - `01000` - San Ángel, Ciudad de México
                    - `44100` - Guadalajara Centro, Jalisco
                    - `64000` - Monterrey Centro, Nuevo León

                    ### Rendimiento:
                    Este endpoint está optimizado con caché, tiempo de respuesta típico: **~50ms**
                    """,
            tags = {"Búsqueda Directa"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Código postal encontrado",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ZipCode.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Formato de código postal inválido",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Código postal no encontrado",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/{zipcode}")
    public ResponseEntity<ZipCode> getZipCode(
            @Parameter(
                    description = "El código postal a buscar",
                    required = true,
                    example = "01000"
            )
            @PathVariable("zipcode")
            @Pattern(regexp = "\\d{5}", message = "El código postal debe tener exactamente 5 dígitos")
            String zipcode
    ) {
        ZipCode response = zipCodeService.getZipCode(zipcode);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "🗺️ Buscar por entidad federativa (estado)",
            description = """
                    Busca códigos postales por entidad federativa con paginación automática.

                    ### Características:
                    - ✅ Búsqueda parcial (ej: "mex" encuentra "México", "Nuevo México")
                    - ✅ Insensible a acentos ("Mexico" = "México")
                    - ✅ Insensible a mayúsculas
                    - ✅ Resultados paginados

                    ### Ejemplos:
                    - `federal_entity=Ciudad de México&page=0&size=20`
                    - `federal_entity=jalisco&page=0&size=10`
                    - `federal_entity=nuevo leon` (sin acentos)

                    ### Parámetros de paginación:
                    - `page`: Número de página (inicia en 0)
                    - `size`: Elementos por página (1-100, default: 20)
                    """,
            tags = {"Búsqueda por Ubicación"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de códigos postales que coinciden con la entidad federativa",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PagedResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parámetros de búsqueda inválidos",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No se encontraron códigos postales para la entidad federativa proporcionada",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping
    public ResponseEntity<PagedResponse<ZipCode>> searchByFederalEntity(
            @Parameter(
                    description = "Término de búsqueda para la entidad federativa (puede ser parcial)",
                    required = true,
                    example = "mexico"
            )
            @RequestParam("federal_entity")
            @NotBlank(message = "El término de búsqueda no puede estar vacío")
            String federalEntity,

            @Parameter(description = "Número de página (comienza en 0)")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "La página debe ser mayor o igual a 0")
            int page,

            @Parameter(description = "Tamaño de página")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "El tamaño debe ser mayor a 0")
            @Max(value = 100, message = "El tamaño máximo es 100")
            int size
    ) {
        List<ZipCode> allResults = zipCodeService.searchByFederalEntity(federalEntity);

        int totalElements = allResults.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int start = page * size;
        int end = Math.min(start + size, totalElements);

        List<ZipCode> pagedResults = allResults.subList(start, end);

        PagedResponse<ZipCode> response = PagedResponse.<ZipCode>builder()
                .content(pagedResults)
                .pageNumber(page)
                .pageSize(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(page == totalPages - 1)
                .build();

        return ResponseEntity.ok(response);
    }


    @Operation(
            summary = "🏘️ Buscar por municipio",
            description = """
                    Busca códigos postales por municipio con paginación.

                    ### Características:
                    - ✅ Búsqueda parcial
                    - ✅ Insensible a acentos y mayúsculas
                    - ✅ Resultados paginados

                    ### Ejemplos:
                    - `municipality=Guadalajara&page=0&size=20`
                    - `municipality=Alvaro Obregon` (sin acentos)
                    - `municipality=monte&page=0` (búsqueda parcial)
                    """,
            tags = {"Búsqueda por Ubicación"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de códigos postales que coinciden con el municipio",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PagedResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parámetros de búsqueda inválidos",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No se encontraron códigos postales para el municipio proporcionado",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/by-municipality")
    public ResponseEntity<PagedResponse<ZipCode>> searchByMunicipality(
            @Parameter(
                    description = "Término de búsqueda para el municipio (puede ser parcial)",
                    required = true,
                    example = "Guadalajara"
            )
            @RequestParam("municipality")
            @NotBlank(message = "El municipio no puede estar vacío")
            String municipality,

            @Parameter(description = "Número de página (comienza en 0)")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "La página debe ser mayor o igual a 0")
            int page,

            @Parameter(description = "Tamaño de página")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "El tamaño debe ser mayor a 0")
            @Max(value = 100, message = "El tamaño máximo es 100")
            int size
    ) {
        List<ZipCode> allResults = zipCodeService.searchByMunicipality(municipality);

        int totalElements = allResults.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int start = page * size;
        int end = Math.min(start + size, totalElements);

        List<ZipCode> pagedResults = allResults.subList(start, end);

        PagedResponse<ZipCode> response = PagedResponse.<ZipCode>builder()
                .content(pagedResults)
                .pageNumber(page)
                .pageSize(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(page == totalPages - 1)
                .build();

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "📊 Estadísticas generales",
            description = """
                    Obtiene estadísticas sobre el catálogo completo de códigos postales.

                    ### Información incluida:
                    - Total de códigos postales únicos
                    - Total de entidades federativas
                    - Total de municipios
                    - Total de asentamientos (colonias, fraccionamientos, etc.)

                    ### Ejemplo de respuesta:
                    ```json
                    {
                      "totalZipCodes": 145000,
                      "totalFederalEntities": 32,
                      "totalMunicipalities": 2469,
                      "totalSettlements": 285000
                    }
                    ```

                    ### Uso:
                    Útil para conocer la cobertura del catálogo y validar la integridad de los datos.
                    """,
            tags = {"Estadísticas"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Estadísticas obtenidas exitosamente",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ZipCodeStats.class))
            )
    })
    @GetMapping("/stats")
    public ResponseEntity<ZipCodeStats> getStats() {
        ZipCodeStats stats = zipCodeService.getStatistics();
        return ResponseEntity.ok(stats);
    }

    @Operation(
            summary = "🔎 Búsqueda parcial de código postal",
            description = """
                    Busca códigos postales que inicien con el prefijo proporcionado.
                    Ideal para implementar autocompletado.

                    ### Características:
                    - ✅ Búsqueda por prefijo (ej: "010" encuentra "01000", "01010", etc.)
                    - ✅ Límite configurable de resultados
                    - ✅ Resultados ordenados numéricamente

                    ### Ejemplos:
                    - `/zip-codes/search?code=010&limit=5` - Primeros 5 códigos que inician con "010"
                    - `/zip-codes/search?code=44` - Códigos de Jalisco (inician con 44)
                    """,
            tags = {"Búsqueda Directa"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Códigos postales encontrados",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ZipCode.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Formato de código inválido",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No se encontraron códigos postales",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/search")
    public ResponseEntity<?> searchByPartialCode(
            @Parameter(
                    description = "Código postal parcial (solo dígitos)",
                    required = true,
                    example = "010"
            )
            @RequestParam("code")
            @NotBlank(message = "El código no puede estar vacío")
            @Pattern(regexp = "\\d{1,5}", message = "El código debe contener de 1 a 5 dígitos")
            String code,

            @Parameter(description = "Número máximo de resultados (1-50)")
            @RequestParam(value = "limit", defaultValue = "10")
            @Min(value = 1, message = "El límite debe ser mayor a 0")
            @Max(value = 50, message = "El límite máximo es 50")
            int limit,

            @Parameter(description = "Si es true, devuelve formato simplificado")
            @RequestParam(value = "simplified", defaultValue = "false")
            boolean simplified
    ) {
        List<ZipCode> results = zipCodeService.searchByPartialCode(code, limit);

        if (simplified) {
            List<ZipCodeSimplified> simplifiedResults = results.stream()
                    .map(ZipCodeSimplified::fromZipCode)
                    .toList();
            return ResponseEntity.ok(simplifiedResults);
        }

        return ResponseEntity.ok(results);
    }

    @Operation(
            summary = "🗺️ Listar todas las entidades federativas",
            description = """
                    Obtiene la lista completa de las 32 entidades federativas (estados) de México.

                    ### Información incluida:
                    - Nombre del estado
                    - Total de códigos postales
                    - Total de municipios

                    ### Uso:
                    Útil para poblar selectores de estados en formularios o para conocer la cobertura por estado.
                    """,
            tags = {"Catálogos"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de entidades federativas",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = FederalEntity.class)))
            )
    })
    @GetMapping("/federal-entities")
    public ResponseEntity<List<FederalEntity>> getAllFederalEntities() {
        List<FederalEntity> entities = zipCodeService.getAllFederalEntities();
        return ResponseEntity.ok(entities);
    }

    @Operation(
            summary = "🏙️ Listar municipios por entidad federativa",
            description = """
                    Obtiene la lista de municipios de una entidad federativa específica.

                    ### Características:
                    - ✅ Búsqueda parcial del nombre del estado
                    - ✅ Insensible a acentos y mayúsculas
                    - ✅ Resultados ordenados alfabéticamente

                    ### Ejemplos:
                    - `/zip-codes/federal-entities/jalisco/municipalities`
                    - `/zip-codes/federal-entities/ciudad de mexico/municipalities`
                    """,
            tags = {"Catálogos"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de municipios",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = String.class)))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Entidad federativa no encontrada",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/federal-entities/{federalEntity}/municipalities")
    public ResponseEntity<List<String>> getMunicipalitiesByFederalEntity(
            @Parameter(
                    description = "Nombre de la entidad federativa",
                    required = true,
                    example = "Jalisco"
            )
            @PathVariable("federalEntity")
            @NotBlank(message = "La entidad federativa no puede estar vacía")
            String federalEntity
    ) {
        List<String> municipalities = zipCodeService.getMunicipalitiesByFederalEntity(federalEntity);
        return ResponseEntity.ok(municipalities);
    }

    @Operation(
            summary = "🏘️ Obtener colonias por código postal",
            description = """
                    Obtiene la lista de colonias/asentamientos de un código postal específico.

                    ### Información incluida por colonia:
                    - Nombre del asentamiento
                    - Tipo de asentamiento (Colonia, Fraccionamiento, etc.)
                    - Tipo de zona (Urbano, Rural)

                    ### Ejemplo:
                    `/zip-codes/01000/settlements` - Colonias del CP 01000
                    """,
            tags = {"Búsqueda Directa"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de asentamientos",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = Settlements.class)))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Formato de código postal inválido",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Código postal no encontrado",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/{zipcode}/settlements")
    public ResponseEntity<List<Settlements>> getSettlementsByZipCode(
            @Parameter(
                    description = "El código postal",
                    required = true,
                    example = "01000"
            )
            @PathVariable("zipcode")
            @Pattern(regexp = "\\d{5}", message = "El código postal debe tener exactamente 5 dígitos")
            String zipcode
    ) {
        List<Settlements> settlements = zipCodeService.getSettlementsByZipCode(zipcode);
        return ResponseEntity.ok(settlements);
    }

    @Operation(
            summary = "🔬 Búsqueda avanzada",
            description = """
                    Búsqueda con múltiples filtros combinados.

                    ### Filtros disponibles:
                    - `federal_entity` - Entidad federativa (estado)
                    - `municipality` - Municipio
                    - `settlement` - Nombre de colonia/asentamiento
                    - `settlement_type` - Tipo de asentamiento (Colonia, Fraccionamiento, etc.)
                    - `zone_type` - Tipo de zona (Urbano, Rural)

                    ### Características:
                    - ✅ Todos los filtros son opcionales (pero al menos uno requerido)
                    - ✅ Búsqueda parcial en todos los campos
                    - ✅ Insensible a acentos y mayúsculas
                    - ✅ Paginación incluida
                    - ✅ Opción de respuesta simplificada

                    ### Ejemplo:
                    Buscar colonias urbanas en Guadalajara:
                    ```
                    /zip-codes/advanced?federal_entity=jalisco&municipality=guadalajara&zone_type=urbano
                    ```
                    """,
            tags = {"Búsqueda Avanzada"}
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Códigos postales encontrados",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PagedResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parámetros de búsqueda inválidos",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No se encontraron resultados",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/advanced")
    public ResponseEntity<?> advancedSearch(
            @Parameter(description = "Entidad federativa (estado)")
            @RequestParam(value = "federal_entity", required = false)
            String federalEntity,

            @Parameter(description = "Municipio")
            @RequestParam(value = "municipality", required = false)
            String municipality,

            @Parameter(description = "Nombre de colonia/asentamiento")
            @RequestParam(value = "settlement", required = false)
            String settlement,

            @Parameter(description = "Tipo de asentamiento")
            @RequestParam(value = "settlement_type", required = false)
            String settlementType,

            @Parameter(description = "Tipo de zona (Urbano/Rural)")
            @RequestParam(value = "zone_type", required = false)
            String zoneType,

            @Parameter(description = "Número de página")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "La página debe ser mayor o igual a 0")
            int page,

            @Parameter(description = "Tamaño de página")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "El tamaño debe ser mayor a 0")
            @Max(value = 100, message = "El tamaño máximo es 100")
            int size,

            @Parameter(description = "Si es true, devuelve formato simplificado")
            @RequestParam(value = "simplified", defaultValue = "false")
            boolean simplified
    ) {
        AdvancedSearchRequest request = AdvancedSearchRequest.builder()
                .federalEntity(federalEntity)
                .municipality(municipality)
                .settlement(settlement)
                .settlementType(settlementType)
                .zoneType(zoneType)
                .page(page)
                .size(size)
                .simplified(simplified)
                .build();

        List<ZipCode> allResults = zipCodeService.advancedSearch(request);

        int totalElements = allResults.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int start = Math.min(page * size, totalElements);
        int end = Math.min(start + size, totalElements);

        List<ZipCode> pagedResults = allResults.subList(start, end);

        if (simplified) {
            List<ZipCodeSimplified> simplifiedResults = pagedResults.stream()
                    .map(ZipCodeSimplified::fromZipCode)
                    .toList();

            PagedResponse<ZipCodeSimplified> response = PagedResponse.<ZipCodeSimplified>builder()
                    .content(simplifiedResults)
                    .pageNumber(page)
                    .pageSize(size)
                    .totalElements(totalElements)
                    .totalPages(totalPages)
                    .first(page == 0)
                    .last(page >= totalPages - 1)
                    .build();

            return ResponseEntity.ok(response);
        }

        PagedResponse<ZipCode> response = PagedResponse.<ZipCode>builder()
                .content(pagedResults)
                .pageNumber(page)
                .pageSize(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(page >= totalPages - 1)
                .build();

        return ResponseEntity.ok(response);
    }
}