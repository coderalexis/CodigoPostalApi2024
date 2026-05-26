package com.coderalexis.CodigoPostalApi.model;

import com.coderalexis.CodigoPostalApi.util.Util;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO con los filtros lógicos de la búsqueda avanzada. Sólo contiene los
 * criterios de filtrado; la paginación y el formato de respuesta se reciben
 * como parámetros separados del controlador para evitar duplicar
 * representaciones y reducir el riesgo de discrepancia entre el query string
 * y el cuerpo del request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Parámetros para búsqueda avanzada de códigos postales")
public class AdvancedSearchRequest {

    @Schema(description = "Entidad federativa (estado) a filtrar", example = "Ciudad de México")
    @JsonProperty("federal_entity")
    private String federalEntity;

    @Schema(description = "Municipio a filtrar", example = "Álvaro Obregón")
    private String municipality;

    @Schema(description = "Nombre de colonia/asentamiento a filtrar", example = "San Ángel")
    private String settlement;

    @Schema(description = "Tipo de asentamiento a filtrar", example = "Colonia")
    @JsonProperty("settlement_type")
    private String settlementType;

    @Schema(description = "Tipo de zona a filtrar (Urbano/Rural)", example = "Urbano")
    @JsonProperty("zone_type")
    private String zoneType;

    /**
     * Cache key estable basada exclusivamente en los filtros normalizados.
     * Por diseño no incluye paginación ni formato porque ambos se aplican a
     * posteriori sobre el conjunto de resultados.
     */
    public String normalizedFilterCacheKey() {
        return String.join("|",
                Util.normalizeCacheKey(federalEntity),
                Util.normalizeCacheKey(municipality),
                Util.normalizeCacheKey(settlement),
                Util.normalizeCacheKey(settlementType),
                Util.normalizeCacheKey(zoneType));
    }

    /**
     * Indica si el request tiene al menos un filtro útil. Usado por las
     * condiciones SpEL de {@code @Cacheable} para evitar entrar al cache con
     * requests inválidos que de todas formas terminarán en
     * IllegalArgumentException (#19).
     */
    public boolean hasAnyFilter() {
        return notBlank(federalEntity)
                || notBlank(municipality)
                || notBlank(settlement)
                || notBlank(settlementType)
                || notBlank(zoneType);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
