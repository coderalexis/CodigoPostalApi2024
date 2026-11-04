package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Opciones de domicilio; las claves oficiales conservan los ceros iniciales. */
public record AddressOptions(
        @JsonProperty("zip_code") String zipCode,
        @JsonProperty("federal_entity") String federalEntity,
        @JsonProperty("federal_entity_code") String federalEntityCode,
        String municipality,
        @JsonProperty("municipality_code") String municipalityCode,
        List<String> settlements) {
    public static AddressOptions from(ZipCode code) {
        return new AddressOptions(code.getZipCode(), code.getFederalEntity(), code.getFederalEntityCode(),
                code.getMunicipality(), code.getMunicipalityCode(), code.getSettlements().stream()
                .map(Settlements::name).distinct().sorted().toList());
    }
}
