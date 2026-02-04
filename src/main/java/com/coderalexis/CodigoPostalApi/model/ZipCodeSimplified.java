package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Versión simplificada de ZipCode para respuestas más ligeras.
 * Solo incluye los campos esenciales sin la lista de asentamientos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZipCodeSimplified {
    @JsonProperty("zip_code")
    private String zipCode;

    private String locality;

    @JsonProperty("federal_entity")
    private String federalEntity;

    private String municipality;

    @JsonProperty("settlements_count")
    private int settlementsCount;

    public static ZipCodeSimplified fromZipCode(ZipCode zipCode) {
        return ZipCodeSimplified.builder()
                .zipCode(zipCode.getZipCode())
                .locality(zipCode.getLocality())
                .federalEntity(zipCode.getFederalEntity())
                .municipality(zipCode.getMunicipality())
                .settlementsCount(zipCode.getSettlements() != null ? zipCode.getSettlements().size() : 0)
                .build();
    }
}
