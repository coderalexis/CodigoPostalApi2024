package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Código postal con sus asentamientos. Es <strong>inmutable</strong> (#11): se
 * construye una sola vez durante la carga del catálogo (vía {@code @Builder}) y
 * sus campos no cambian después. Esto elimina cualquier posibilidad de mutación
 * accidental del estado interno una vez publicado en los índices.
 *
 * <p>NOTA sobre @JsonInclude: la inclusión NON_NULL ya está configurada
 * globalmente en application.yml (spring.jackson.default-property-inclusion),
 * así que no hace falta repetirla aquí.</p>
 */
@Getter
@ToString
@Builder
@AllArgsConstructor
@EqualsAndHashCode(of = "zipCode")
public class ZipCode {
    @JsonProperty("zip_code")
    private final String zipCode;

    private final String locality;

    @JsonProperty("federal_entity")
    private final String federalEntity;

    private final String municipality;

    private final List<Settlements> settlements;

    @JsonIgnore
    private final String normalizedFederalEntity;

    @JsonIgnore
    private final String normalizedMunicipality;
}
