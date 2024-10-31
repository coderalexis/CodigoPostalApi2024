package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ZipCode {
    private String zip_code;
    private String locality;
    private String federal_entity;
    private ArrayList<Settlements> settlements;
    private String municipality;
}