package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.exceptions.ErrorResponse;
import com.coderalexis.CodigoPostalApi.model.FederalEntity;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Contrato REST del API de códigos postales. Fix #16: extraer las anotaciones
 * OpenAPI (que dominaban el archivo Controller.java) a esta interfaz deja la
 * implementación enfocada en la lógica y permite documentar el contrato sin
 * leer el código de los handlers. Spring procesa mapping y validation
 * annotations declaradas en la interfaz tal como si estuvieran en la clase.
 */
@RequestMapping("/zip-codes")
public interface ZipCodeApi {

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
            @ApiResponse(responseCode = "200", description = "Código postal encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ZipCode.class))),
            @ApiResponse(responseCode = "400", description = "Formato de código postal inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Código postal no encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{zipcode}")
    ResponseEntity<ZipCode> getZipCode(
            @Parameter(description = "El código postal a buscar", required = true, example = "01000")
            @PathVariable("zipcode")
            @Pattern(regexp = "\\d{5}", message = "El código postal debe tener exactamente 5 dígitos")
            String zipcode
    );

    @Operation(
            summary = "🗺️ Buscar por entidad federativa (estado)",
            description = """
                    Busca códigos postales por entidad federativa con paginación automática.

                    ### Características:
                    - ✅ Búsqueda parcial (ej: "mex" encuentra "México", "Nuevo México")
                    - ✅ Insensible a acentos ("Mexico" = "México")
                    - ✅ Insensible a mayúsculas
                    - ✅ Resultados paginados
                    """,
            tags = {"Búsqueda por Ubicación"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de códigos postales que coinciden",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedResponse.class))),
            @ApiResponse(responseCode = "400", description = "Parámetros de búsqueda inválidos",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No se encontraron códigos postales",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    ResponseEntity<PagedResponse<ZipCode>> searchByFederalEntity(
            @Parameter(description = "Término de búsqueda para la entidad federativa (puede ser parcial)",
                    required = true, example = "mexico")
            @RequestParam("federal_entity")
            @NotBlank(message = "El término de búsqueda no puede estar vacío")
            String federalEntity,

            @Parameter(description = "Número de página (comienza en 0)")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "La página debe ser mayor o igual a 0")
            @Max(value = 10_000, message = "El número de página es inválido")
            int page,

            @Parameter(description = "Tamaño de página")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "El tamaño debe ser mayor a 0")
            @Max(value = 100, message = "El tamaño máximo es 100")
            int size
    );

    @Operation(
            summary = "🏘️ Buscar por municipio",
            description = """
                    Busca códigos postales por municipio con paginación.

                    ### Características:
                    - ✅ Búsqueda parcial
                    - ✅ Insensible a acentos y mayúsculas
                    - ✅ Resultados paginados
                    """,
            tags = {"Búsqueda por Ubicación"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de códigos postales que coinciden con el municipio",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedResponse.class))),
            @ApiResponse(responseCode = "400", description = "Parámetros de búsqueda inválidos",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No se encontraron códigos postales",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/by-municipality")
    ResponseEntity<PagedResponse<ZipCode>> searchByMunicipality(
            @Parameter(description = "Término de búsqueda para el municipio (puede ser parcial)",
                    required = true, example = "Guadalajara")
            @RequestParam("municipality")
            @NotBlank(message = "El municipio no puede estar vacío")
            String municipality,

            @Parameter(description = "Número de página (comienza en 0)")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "La página debe ser mayor o igual a 0")
            @Max(value = 10_000, message = "El número de página es inválido")
            int page,

            @Parameter(description = "Tamaño de página")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "El tamaño debe ser mayor a 0")
            @Max(value = 100, message = "El tamaño máximo es 100")
            int size
    );

    @Operation(
            summary = "📊 Estadísticas generales",
            description = "Obtiene estadísticas sobre el catálogo completo de códigos postales.",
            tags = {"Estadísticas"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Estadísticas obtenidas exitosamente",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = com.coderalexis.CodigoPostalApi.model.ZipCodeStats.class)))
    })
    @GetMapping("/stats")
    ResponseEntity<com.coderalexis.CodigoPostalApi.model.ZipCodeStats> getStats();

    @Operation(
            summary = "🔎 Búsqueda parcial de código postal",
            description = """
                    Busca códigos postales que inicien con el prefijo proporcionado.
                    Ideal para implementar autocompletado.
                    """,
            tags = {"Búsqueda Directa"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Códigos postales encontrados",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ZipCode.class)))),
            @ApiResponse(responseCode = "400", description = "Formato de código inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No se encontraron códigos postales",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/search")
    ResponseEntity<?> searchByPartialCode(
            @Parameter(description = "Código postal parcial (solo dígitos)", required = true, example = "010")
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
    );

    @Operation(
            summary = "🗺️ Listar todas las entidades federativas",
            description = "Obtiene la lista completa de las 32 entidades federativas (estados) de México.",
            tags = {"Catálogos"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de entidades federativas",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = FederalEntity.class))))
    })
    @GetMapping("/federal-entities")
    ResponseEntity<List<FederalEntity>> getAllFederalEntities();

    @Operation(
            summary = "🏙️ Listar municipios por entidad federativa",
            description = "Obtiene la lista de municipios de una entidad federativa específica.",
            tags = {"Catálogos"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de municipios",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = String.class)))),
            @ApiResponse(responseCode = "404", description = "Entidad federativa no encontrada",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/federal-entities/{federalEntity}/municipalities")
    ResponseEntity<List<String>> getMunicipalitiesByFederalEntity(
            @Parameter(description = "Nombre de la entidad federativa", required = true, example = "Jalisco")
            @PathVariable("federalEntity")
            @NotBlank(message = "La entidad federativa no puede estar vacía")
            String federalEntity
    );

    @Operation(
            summary = "🏘️ Obtener colonias por código postal",
            description = "Obtiene la lista de colonias/asentamientos de un código postal específico.",
            tags = {"Búsqueda Directa"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de asentamientos",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = Settlements.class)))),
            @ApiResponse(responseCode = "400", description = "Formato de código postal inválido",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Código postal no encontrado",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{zipcode}/settlements")
    ResponseEntity<List<Settlements>> getSettlementsByZipCode(
            @Parameter(description = "El código postal", required = true, example = "01000")
            @PathVariable("zipcode")
            @Pattern(regexp = "\\d{5}", message = "El código postal debe tener exactamente 5 dígitos")
            String zipcode
    );

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
                    """,
            tags = {"Búsqueda Avanzada"}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Códigos postales encontrados",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PagedResponse.class))),
            @ApiResponse(responseCode = "400", description = "Parámetros de búsqueda inválidos",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No se encontraron resultados",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/advanced")
    ResponseEntity<?> advancedSearch(
            @Parameter(description = "Entidad federativa (estado)")
            @RequestParam(value = "federal_entity", required = false) String federalEntity,

            @Parameter(description = "Municipio")
            @RequestParam(value = "municipality", required = false) String municipality,

            @Parameter(description = "Nombre de colonia/asentamiento")
            @RequestParam(value = "settlement", required = false) String settlement,

            @Parameter(description = "Tipo de asentamiento")
            @RequestParam(value = "settlement_type", required = false) String settlementType,

            @Parameter(description = "Tipo de zona (Urbano/Rural)")
            @RequestParam(value = "zone_type", required = false) String zoneType,

            @Parameter(description = "Número de página")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "La página debe ser mayor o igual a 0")
            @Max(value = 10_000, message = "El número de página es inválido")
            int page,

            @Parameter(description = "Tamaño de página")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "El tamaño debe ser mayor a 0")
            @Max(value = 100, message = "El tamaño máximo es 100")
            int size,

            @Parameter(description = "Si es true, devuelve formato simplificado")
            @RequestParam(value = "simplified", defaultValue = "false")
            boolean simplified
    );
}
