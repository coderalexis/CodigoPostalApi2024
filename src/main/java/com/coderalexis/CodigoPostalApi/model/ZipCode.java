package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ZipCode {
    private String zip_code;
    private String locality;
    private String federal_entity;
    // Eliminar normalizedFederalEntity - ya no es necesario
    private ArrayList<Settlements> settlements;
    private String municipality;
}