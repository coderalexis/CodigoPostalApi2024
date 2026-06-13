package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZipCodeStats {
    private int totalZipCodes;
    private int totalFederalEntities;
    private int totalMunicipalities;
    private long totalSettlements;

    /**
     * Metadata de frescura del catálogo: cuándo se cargó en este proceso, un
     * checksum SHA-256 del archivo fuente (identifica unívocamente la versión de
     * los datos) y el origen desde el que se leyó. Permite saber si el catálogo
     * está desactualizado sin redeployar a ciegas.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime loadedAt;

    private String catalogChecksum;

    private String catalogSource;
}
