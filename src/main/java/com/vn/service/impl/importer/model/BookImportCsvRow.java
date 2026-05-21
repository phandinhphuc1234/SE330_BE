package com.vn.service.impl.importer.model;

import java.time.LocalDate;
import java.util.List;

// Record nội bộ đại diện cho một dòng CSV đã parse.
// Không đặt ở dto/request vì object này không phải API contract public.
// Mỗi row tương ứng một physical copy; nhiều row có thể trỏ về cùng một ISBN/book.
public record BookImportCsvRow(
        int rowNumber,
        String title,
        String isbn,
        List<String> authors,
        String category,
        String barcode,
        String condition,
        String location,
        String language,
        LocalDate publishedDate,
        String edition
) {
}

