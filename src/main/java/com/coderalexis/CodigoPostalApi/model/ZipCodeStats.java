package com.coderalexis.CodigoPostalApi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZipCodeStats {
    private int totalZipCodes;
    private int totalFederalEntities;
    private int totalMunicipalities;
    private long totalSettlements;
}