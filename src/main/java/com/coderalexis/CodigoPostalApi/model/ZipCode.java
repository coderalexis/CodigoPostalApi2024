package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
@NoArgsConstructor
@EqualsAndHashCode(of = "zipCode")
@JsonInclude(JsonInclude.Include.NON_NULL)
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
