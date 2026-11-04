package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public record AddressValidationRequest(
        @JsonProperty("zip_code") @NotBlank @Pattern(regexp = "[0-9]{5}")
        @Schema(example = "01000") String zipCode,
        @JsonProperty("federal_entity") @NotBlank @Size(max = 120)
        @Schema(example = "Ciudad de México") String federalEntity,
        @NotBlank @Size(max = 120) @Schema(example = "Álvaro Obregón") String municipality) {
}
