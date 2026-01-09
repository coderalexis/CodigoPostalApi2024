package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.exceptions.ErrorResponse;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
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
}