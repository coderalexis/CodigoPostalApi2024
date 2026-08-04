package com.coderalexis.CodigoPostalApi.util;

import com.coderalexis.CodigoPostalApi.model.PagedResponse;

import java.util.List;

/**
 * Utilidades genéricas de paginación, extraídas de {@code ZipCodeService} (#10)
 * para separar la mecánica de paginación de la lógica de negocio y poder
 * reutilizarlas/probarlas de forma aislada.
 *
 * <p>Se usa aritmética en {@code long} para el offset, de modo que
 * {@code page * size} no desborde {@link Integer#MAX_VALUE}.</p>
 */
public final class PaginationSupport {

    private PaginationSupport() {
    }

    /**
     * Pagina una lista ya materializada tomando una sublista de la página pedida.
     */
    public static <T> PagedResponse<T> paginate(List<T> allResults, int page, int size) {
        validatePagination(page, size);

        int totalElements = allResults.size();
        int totalPages = calculateTotalPages(totalElements, size);
        long offset = (long) page * size;

        if (offset >= totalElements) {
            return buildPagedResponse(List.of(), page, size, totalElements, totalPages);
        }

        int start = (int) offset; // Safe: offset < totalElements which is an int
        int end = Math.min(start + size, totalElements);
        return buildPagedResponse(allResults.subList(start, end), page, size, totalElements, totalPages);
    }

    public static void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("La pagina debe ser mayor o igual a 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("El tamaño debe ser mayor a 0");
        }
    }

    private static <T> PagedResponse<T> buildPagedResponse(
            List<T> content,
            int page,
            int size,
            int totalElements,
            int totalPages) {
        return PagedResponse.<T>builder()
                .content(content)
                .pageNumber(page)
                .pageSize(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(page == 0)
                .last(totalPages == 0 || page >= totalPages - 1)
                .build();
    }

    private static int calculateTotalPages(int totalElements, int size) {
        return totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }
}
