package com.vn.service.impl.importer.model;

import java.util.HashMap;
import java.util.Map;

// Cache nội bộ dùng riêng cho một lần import CSV.
//
// Lý do cần cache:
// - Một file CSV có thể có nhiều dòng cùng ISBN, cùng tác giả hoặc cùng thể loại.
// - Nếu mỗi dòng đều query DB lại từ đầu thì file vài nghìn dòng sẽ tạo ra rất nhiều query lặp.
// - Cache này giúp tái sử dụng kết quả đã resolve trong chính request import hiện tại.
//
// Vì sao không dùng Redis:
// - Dữ liệu này chỉ có ý nghĩa tạm thời trong lúc xử lý một file CSV.
// - Import xong là bỏ, không cần chia sẻ qua nhiều request hoặc nhiều instance.
// - Dùng HashMap local đơn giản hơn và đúng nhu cầu hơn.
//
// Vì sao cache Long ID thay vì cache JPA Entity:
// - Mỗi dòng import có thể chạy trong transaction riêng.
// - Nếu cache Entity, entity có thể bị detached khi sang transaction khác.
// - Cache ID giúp service lấy lại entity active từ DB khi cần, tránh lỗi persistence context.
public class BookImportCache {
    // Key nên được chuẩn hóa trước khi put/get, ví dụ ISBN lowercase/trim hoặc author/category name lowercase/trim.
    private final Map<String, Long> bookIdsByIsbn = new HashMap<>();
    private final Map<String, Long> authorIdsByName = new HashMap<>();
    private final Map<String, Long> categoryIdsByName = new HashMap<>();

    // Cache book theo ISBN để nhiều dòng copy của cùng một đầu sách không phải findByIsbn lặp lại.
    public Long getBookId(String isbn) {
        return bookIdsByIsbn.get(isbn);
    }

    public void putBookId(String isbn, Long bookId) {
        bookIdsByIsbn.put(isbn, bookId);
    }

    // Cache author theo tên đã normalize để nhiều sách cùng tác giả không phải find/create lặp lại.
    public Long getAuthorId(String authorName) {
        return authorIdsByName.get(authorName);
    }

    public void putAuthorId(String authorName, Long authorId) {
        authorIdsByName.put(authorName, authorId);
    }

    // Cache category theo tên đã normalize để tránh query lặp khi nhiều sách cùng thể loại.
    public Long getCategoryId(String categoryName) {
        return categoryIdsByName.get(categoryName);
    }

    public void putCategoryId(String categoryName, Long categoryId) {
        categoryIdsByName.put(categoryName, categoryId);
    }
}

