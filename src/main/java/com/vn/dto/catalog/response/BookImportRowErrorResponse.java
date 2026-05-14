package com.vn.dto.catalog.response;
// DTO trả về số dòng lỗi khi import vào
public record BookImportRowErrorResponse(
        int rowNumber,
        String isbn,
        String barcode,
        String code,
        String message
) {
}

