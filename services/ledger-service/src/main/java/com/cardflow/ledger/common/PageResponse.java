package com.cardflow.ledger.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Stable JSON shape for paginated results (instead of serializing Spring's Page internals). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
