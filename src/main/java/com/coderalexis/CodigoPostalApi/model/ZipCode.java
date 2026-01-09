package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ZipCode {
    @JsonProperty("zip_code")
    private String zipCode;

    private String locality;

    @JsonProperty("federal_entity")
    private String federalEntity;

    private String municipality;

    private List<Settlements> settlements;
}