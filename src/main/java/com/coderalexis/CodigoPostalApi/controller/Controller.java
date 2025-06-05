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
            summary = "Obtiene información de un código postal",
            description = "Proporciona detalles del código postal solicitado."
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
            summary = "Busca códigos postales por entidad federativa",
            description = "Busca y devuelve una lista de códigos postales cuya entidad federativa contenga el término de búsqueda proporcionado. La búsqueda es insensible a mayúsculas, minúsculas y acentos."
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

        // Implementar paginación manual
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
            summary = "Busca códigos postales por municipio",
            description = "Busca códigos postales que pertenezcan al municipio especificado"
    )
    @GetMapping("/by-municipality")
    public ResponseEntity<List<ZipCode>> searchByMunicipality(
            @RequestParam("municipality")
            @NotBlank(message = "El municipio no puede estar vacío")
            String municipality
    ) {
        List<ZipCode> results = zipCodeService.searchByMunicipality(municipality);
        return ResponseEntity.ok(results);
    }

    @Operation(
            summary = "Obtiene estadísticas de códigos postales",
            description = "Devuelve información estadística sobre los códigos postales cargados"
    )
    @GetMapping("/stats")
    public ResponseEntity<ZipCodeStats> getStats() {
        ZipCodeStats stats = zipCodeService.getStatistics();
        return ResponseEntity.ok(stats);
    }
}