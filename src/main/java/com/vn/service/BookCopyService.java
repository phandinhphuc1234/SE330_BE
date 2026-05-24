package com.vn.service;

import com.vn.dto.catalog.request.CreateBookCopyRequest;
import com.vn.dto.catalog.request.BulkCreateBookCopiesRequest;
import com.vn.dto.catalog.request.UpdateBookCopyRequest;
import com.vn.dto.catalog.response.BookCopyResponse;

import java.util.List;

public interface BookCopyService {

    List<BookCopyResponse> getBookCopies(Long bookId, String status, String barcode, String condition, String location);

    BookCopyResponse createBookCopy(Long bookId, CreateBookCopyRequest request);

    List<BookCopyResponse> createBookCopies(Long bookId, BulkCreateBookCopiesRequest request);

    BookCopyResponse updateBookCopy(Long copyId, UpdateBookCopyRequest request);

    void deleteBookCopy(Long copyId, Long deletedBy);
}

