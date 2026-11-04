package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.model.*;
import com.coderalexis.CodigoPostalApi.service.AddressService;
import com.coderalexis.CodigoPostalApi.exceptions.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/zip-codes")
@Validated
public class AddressController {
    private final AddressService service;
    public AddressController(AddressService service) { this.service = service; }

    @PostMapping("/validate-address")
    @Operation(operationId = "validateAddress", summary = "Validar CP, estado y municipio",
            description = "Coincidencia exacta ignorando acentos, mayúsculas y espacios exteriores. " +
                    "Una inconsistencia devuelve 200 con valid=false y hasta cinco alternativas. " +
                    "No verifica calles ni números exteriores.", tags = "Domicilios")
    @ApiResponse(responseCode = "200", description = "Resultado de validación",
            content = @Content(schema = @Schema(implementation = AddressValidationResult.class)))
    @ApiResponse(responseCode = "400", description = "Solicitud inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<AddressValidationResult> validateAddress(@Valid @RequestBody AddressValidationRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.validate(request));
    }

    @GetMapping("/{zipcode}/address")
    @Operation(operationId = "getAddressOptions", summary = "Opciones compactas para un formulario de domicilio",
            tags = "Domicilios")
    @ApiResponse(responseCode = "200", description = "Estado, municipio y colonias",
            content = @Content(schema = @Schema(implementation = AddressOptions.class)))
    @ApiResponse(responseCode = "400", description = "Formato de CP inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "CP no encontrado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public AddressOptions getAddressOptions(@PathVariable("zipcode") @Pattern(regexp = "[0-9]{5}") String code) {
        return service.options(code);
    }
}
