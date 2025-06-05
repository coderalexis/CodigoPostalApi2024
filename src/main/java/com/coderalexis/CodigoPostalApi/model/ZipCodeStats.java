package com.coderalexis.CodigoPostalApi.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ZipCodeStats {
    private int totalZipCodes;
    private int totalFederalEntities;
    private int totalMunicipalities;
    private long totalSettlements;
}