package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
public class Settlements implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;

    @JsonProperty("zone_type")
    private String zoneType;

    @JsonProperty("settlement_type")
    private String settlementType;
}
