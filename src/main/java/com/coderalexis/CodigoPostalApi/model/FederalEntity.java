package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representa una entidad federativa (estado) de México.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FederalEntity {
    private String name;

    @JsonProperty("zip_codes_count")
    private int zipCodesCount;

    @JsonProperty("municipalities_count")
    private int municipalitiesCount;
}
