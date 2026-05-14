package com.vn.service.impl.importer;

import com.vn.entity.Book;

// Kết quả resolve ISBN: book có thể là dữ liệu đã tồn tại hoặc vừa được tạo từ dòng CSV.
record ResolvedImportBook(
        Book book,
        boolean created
) {
}

