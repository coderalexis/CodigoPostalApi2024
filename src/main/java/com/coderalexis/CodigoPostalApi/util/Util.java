package com.coderalexis.CodigoPostalApi.util;

import java.text.Normalizer;

/**
 * Clase de utilidades para operaciones comunes.
 */
public class Util {

    /**
     * Normaliza una cadena eliminando acentos y convirtiéndola a minúsculas.
     *
     * @param input La cadena a normalizar.
     * @return La cadena normalizada.
     */
    public static String normalizeString(String input) {
        if (input == null) {
            return null;
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
    }
}
