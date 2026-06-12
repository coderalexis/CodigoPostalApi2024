package com.coderalexis.CodigoPostalApi.util;

import com.coderalexis.CodigoPostalApi.model.PagedResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Utilidades genéricas de paginación, extraídas de {@code ZipCodeService} (#10)
 * para separar la mecánica de paginación de la lógica de negocio y poder
 * reutilizarlas/probarlas de forma aislada.
 *
 * <p>Todas las variantes usan aritmética en {@code long} para el offset, de modo
 * que {@code page * size} no desborde {@link Integer#MAX_VALUE}.</p>
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

    /**
     * Ordena el conjunto candidato con el comparador dado y materializa
     * únicamente la página solicitada.
     */
    public static <T> PagedResponse<T> paginateSorted(
            Set<T> candidates,
            Comparator<T> comparator,
            int page,
            int size) {
        validatePagination(page, size);

        int totalElements = candidates.size();
        int totalPages = calculateTotalPages(totalElements, size);
        long offset = (long) page * size;

        if (offset >= totalElements) {
            return buildPagedResponse(List.of(), page, size, totalElements, totalPages);
        }

        int start = (int) offset;
        int limit = Math.min(size, totalElements - start);
        List<T> pageContent = candidates.stream()
                .sorted(comparator)
                .skip(start)
                .limit(limit)
                .toList();

        return buildPagedResponse(pageContent, page, size, totalElements, totalPages);
    }

    /**
     * Pagina una colección aplicando un filtro al vuelo, sin materializar el
     * conjunto filtrado completo. Se preserva el orden de iteración de la colección.
     */
    public static <T> PagedResponse<T> paginateFiltered(
            Collection<T> candidates,
            Predicate<T> filter,
            int page,
            int size) {
        validatePagination(page, size);

        long offset = (long) page * size;
        List<T> pageContent = new ArrayList<>(size);
        int totalElements = 0;

        for (T candidate : candidates) {
            if (!filter.test(candidate)) {
                continue;
            }

            if (totalElements >= offset && pageContent.size() < size) {
                pageContent.add(candidate);
            }
            totalElements++;
        }

        return buildPagedResponse(
                pageContent,
                page,
                size,
                totalElements,
                calculateTotalPages(totalElements, size));
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
