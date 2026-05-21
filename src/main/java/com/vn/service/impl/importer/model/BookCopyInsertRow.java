package com.vn.service.impl.importer.model;

import java.time.Instant;

// Dữ liệu tối thiểu để JDBC batch insert một dòng vào bảng book_copies.
// Dùng record này thay vì BookCopy entity để tránh Hibernate dirty checking khi import nhiều bản copy.
public record BookCopyInsertRow(
        Long bookId,
        String barcode,
        String status,
        String condition,
        String location,
        Instant createdAt,
        Instant updatedAt
) {
}
