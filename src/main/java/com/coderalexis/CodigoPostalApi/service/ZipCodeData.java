package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.model.ZipCode;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Resultado inmutable de la carga del catálogo SEPOMEX: las cuatro estructuras
 * de índice que {@link ZipCodeService} sirve en modo solo-lectura. Producirlas
 * en {@link SepomexZipCodeLoader} y entregarlas ya construidas (#10) separa la
 * carga/parseo de la lógica de consulta.
 *
 * <ul>
 *   <li>{@code byCode} - lookup directo O(1) por código postal</li>
 *   <li>{@code sorted} - búsqueda por prefijo O(log n + k) vía {@code subMap()}</li>
 *   <li>{@code byNormalizedEntity} - índice invertido por entidad federativa normalizada;
 *       cada bucket es una lista inmutable pre-ordenada por código postal</li>
 *   <li>{@code byNormalizedMunicipality} - índice invertido por municipio normalizado,
 *       con buckets igualmente pre-ordenados</li>
 * </ul>
 *
 * <p>Incluye además metadata de frescura: {@code checksum} (SHA-256 del archivo
 * fuente) y {@code source} (origen legible), para exponer qué versión del
 * catálogo está sirviendo la API.</p>
 */
public record ZipCodeData(
        Map<String, ZipCode> byCode,
        NavigableMap<String, ZipCode> sorted,
        Map<String, List<ZipCode>> byNormalizedEntity,
        Map<String, List<ZipCode>> byNormalizedMunicipality,
        String checksum,
        String source) {
}
