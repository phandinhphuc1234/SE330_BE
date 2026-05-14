package com.vn.service.impl.importer;

// Kết quả ghi DB của một dòng CSV, dùng để cộng thống kê import.
record BookImportRowResult(
        boolean createdBook,
        Long bookId,
        Long copyId
) {
}

