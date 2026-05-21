package com.vn.service.impl.importer.model;

// Kết quả prepare một row hợp lệ: đã resolve/create Book, còn BookCopy sẽ được insert batch sau.
// createdBook dùng để thống kê, bookId dùng để update counter theo nhóm.
public record PreparedBookCopyImport(
        boolean createdBook,
        Long bookId,
        BookCopyInsertRow copy
) {
}
