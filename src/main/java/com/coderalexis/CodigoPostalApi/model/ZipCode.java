package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * NOTA sobre @JsonInclude: la inclusión NON_NULL ya está configurada
 * globalmente en application.yml (spring.jackson.default-property-inclusion),
 * así que no hace falta repetirla aquí.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@EqualsAndHashCode(of = "zipCode")
public class ZipCode {
    @JsonProperty("zip_code")
    private String zipCode;

    private String locality;

    @JsonProperty("federal_entity")
    private String federalEntity;

    private String municipality;

    private List<Settlements> settlements;

    @JsonIgnore
    private String normalizedFederalEntity;

    @JsonIgnore
    private String normalizedMunicipality;
}
