package com.vn.service;

import com.vn.dto.catalog.request.CreateBookRequest;
import com.vn.dto.catalog.request.UpdateBookAuthorsRequest;
import com.vn.dto.catalog.request.UpdateBookRequest;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import org.springframework.data.domain.Page;

public interface BookService {
    // Phân trang trả về kết quả sách được lọc theo các tiêu chí
    Page<BookSummaryResponse> searchBooks(String q, String title, String isbn, Long authorId,
                                          String author, Long categoryId, Boolean availableOnly,
                                          String language, int page, int size, String sort);

    BookDetailResponse getBook(Long bookId);

    BookDetailResponse createBook(CreateBookRequest request);

    BookDetailResponse updateBook(Long bookId, UpdateBookRequest request);

    void deleteBook(Long bookId, Long deletedBy);

    BookDetailResponse updateBookAuthors(Long bookId, UpdateBookAuthorsRequest request);
}

