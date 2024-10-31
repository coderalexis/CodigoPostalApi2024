package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/zip-codes")
public class Controller {

    private final ZipCodeService zipCodeService;

    public Controller(ZipCodeService zipCodeService) {
        this.zipCodeService = zipCodeService;
    }

    
    /**
     * Obtiene un ZipCode por su código postal.
     *
     * @param zipcode El código postal a buscar.
     * @return Respuesta HTTP con el ZipCode o 404 si no se encuentra.
     */
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
                    responseCode = "404",
                    description = "Código postal no encontrado",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ZipCode.class))
            )
    })
    @GetMapping("/{zipcode}")
    public ResponseEntity<ZipCode> zipcodes(
            @Parameter(
                    description = "El código postal a buscar",
                    required = true,
                    example = "01000"
            )
            @PathVariable("zipcode") String zipcode
    ) {
        ZipCode response = zipCodeService.getZipCode(zipcode);
        return (response != null) ? ResponseEntity.ok(response) : ResponseEntity.notFound().build();
    }

    /**
     * Busca ZipCodes por coincidencia parcial en el nombre de la entidad federativa.
     *
     * @param federalEntity El término de búsqueda para la entidad federativa.
     * @return Respuesta HTTP con la lista de ZipCodes o 404 si no se encuentra ninguno.
     */
    @Operation(
            summary = "Busca códigos postales por entidad federativa",
            description = "Busca y devuelve una lista de códigos postales cuya entidad federativa contenga el término de búsqueda proporcionado. La búsqueda es insensible a mayúsculas, minúsculas y acentos."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de códigos postales que coinciden con la entidad federativa",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ZipCode.class)))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No se encontraron códigos postales para la entidad federativa proporcionada",
                    content = @Content(mediaType = "application/json")
            )
    })
    @GetMapping
    public ResponseEntity<List<ZipCode>> searchByFederalEntity(
            @Parameter(
                    description = "Término de búsqueda para la entidad federativa (puede ser parcial)",
                    required = true,
                    example = "juchitán"
            )
            @RequestParam("federal_entity") String federalEntity
    ) {
        List<ZipCode> results = zipCodeService.searchByFederalEntity(federalEntity);
        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok(results);
        }
    }
}