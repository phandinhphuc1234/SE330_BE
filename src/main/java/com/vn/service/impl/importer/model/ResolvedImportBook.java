package com.vn.service.impl.importer.model;

import com.vn.entity.Book;

// Kết quả resolve ISBN: book có thể là dữ liệu đã tồn tại hoặc vừa được tạo từ dòng CSV.
// Cờ created giúp import thống kê số đầu sách mới mà không cần đoán lại từ entity state.
public record ResolvedImportBook(
        Book book,
        boolean created
) {
}

