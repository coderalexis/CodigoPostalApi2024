package com.coderalexis.CodigoPostalApi.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

public class Util {

    private static final Pattern DIACRITICS_PATTERN =
        Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * Normalizes a string by removing accents and converting to lowercase.
     * Uses Unicode NFD normalization for proper UTF-8 handling.
     */
    public static String normalizeString(String input) {
        if (input == null) {
            return null;
        }

        // Decompose accented characters into base + diacritic
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        // Remove diacritics, keep only base characters
        return DIACRITICS_PATTERN.matcher(normalized).replaceAll("").toLowerCase();
    }

    /**
     * Normalizes user-entered search text by trimming whitespace before removing
     * accents and lowercasing, so semantically equivalent searches share the same value.
     */
    public static String normalizeSearchTerm(String input) {
        if (input == null) {
            return null;
        }

        return normalizeString(input.trim());
    }

    /**
     * Builds a null-safe cache key for search terms. Invalid null/blank values still
     * reach method validation instead of failing during Spring cache key evaluation.
     */
    public static String normalizeCacheKey(String input) {
        String normalized = normalizeSearchTerm(input);
        return normalized == null ? "" : normalized;
    }
}
