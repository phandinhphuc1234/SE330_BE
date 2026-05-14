package com.vn.dto.common;

import org.springframework.data.domain.Page;
// // Chứa thông tin phân trang trả về cho client
public record PageMeta(
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
)
{
    // Chuyển Page của Spring Data thành metadata phân trang
    public static PageMeta from(Page<?> page) {
        return new PageMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}

