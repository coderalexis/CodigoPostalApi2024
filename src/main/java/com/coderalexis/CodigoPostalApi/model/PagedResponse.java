package com.coderalexis.CodigoPostalApi.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class PagedResponse<T> {
    private List<T> content;
    private int pageNumber;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
}