package com.vn.dto.catalog.response;

import java.util.List;
// DTO trả về response import thành công
public record BookImportResultResponse(
        int totalRows,
        int successRows,
        int failedRows,
        int createdBooks,
        int createdCopies,
        List<BookImportRowErrorResponse> errors
) {
}

