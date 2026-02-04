package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request para búsqueda avanzada con múltiples filtros.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Parámetros para búsqueda avanzada de códigos postales")
public class AdvancedSearchRequest {

    @Schema(description = "Entidad federativa (estado) a filtrar", example = "Ciudad de México")
    @JsonProperty("federal_entity")
    private String federalEntity;

    @Schema(description = "Municipio a filtrar", example = "Álvaro Obregón")
    private String municipality;

    @Schema(description = "Nombre de colonia/asentamiento a filtrar", example = "San Ángel")
    private String settlement;

    @Schema(description = "Tipo de asentamiento a filtrar", example = "Colonia")
    @JsonProperty("settlement_type")
    private String settlementType;

    @Schema(description = "Tipo de zona a filtrar (Urbano/Rural)", example = "Urbano")
    @JsonProperty("zone_type")
    private String zoneType;

    @Schema(description = "Número de página (comienza en 0)", example = "0")
    @Min(value = 0, message = "La página debe ser mayor o igual a 0")
    @Builder.Default
    private int page = 0;

    @Schema(description = "Tamaño de página", example = "20")
    @Min(value = 1, message = "El tamaño debe ser mayor a 0")
    @Max(value = 100, message = "El tamaño máximo es 100")
    @Builder.Default
    private int size = 20;

    @Schema(description = "Si es true, devuelve formato simplificado sin lista de asentamientos", example = "false")
    @Builder.Default
    private boolean simplified = false;
}
