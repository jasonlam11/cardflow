package com.cardflow.authorization.common;

import java.util.List;

/** Stable JSON shape for paginated results. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        return new PageResponse<>(content, page, size, total, (int) Math.ceil(total / (double) size));
    }
}
