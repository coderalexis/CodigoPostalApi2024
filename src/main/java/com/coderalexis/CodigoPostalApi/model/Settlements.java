package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Representa un asentamiento (colonia, fraccionamiento, etc.) dentro de un
 * código postal. Se convirtió a record porque conceptualmente es un valor
 * inmutable: una vez cargado desde SEPOMEX al arrancar, sus campos no cambian.
 *
 * <p>Los campos {@code normalized*} se calculan una sola vez durante la carga
 * y se usan en el hot path de búsqueda avanzada para evitar reentrar a
 * {@link com.coderalexis.CodigoPostalApi.util.Util#normalizeString(String)}
 * en cada filtro.</p>
 */
public record Settlements(
        String name,
        @JsonProperty("zone_type") String zoneType,
        @JsonProperty("settlement_type") String settlementType,
        @JsonIgnore String normalizedName,
        @JsonIgnore String normalizedSettlementType,
        @JsonIgnore String normalizedZoneType
) {
}
