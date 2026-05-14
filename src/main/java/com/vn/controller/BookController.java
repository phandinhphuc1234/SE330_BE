package com.vn.controller;

import com.vn.controller.docs.BookApiDocs;
import com.vn.dto.catalog.request.BulkCreateBookCopiesRequest;
import com.vn.dto.catalog.request.CreateBookCopyRequest;
import com.vn.dto.catalog.request.CreateBookRequest;
import com.vn.dto.catalog.request.UpdateBookAuthorsRequest;
import com.vn.dto.catalog.request.UpdateBookRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookImportResultResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import com.vn.dto.common.PageMeta;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.BookCopyService;
import com.vn.service.BookImportService;
import com.vn.service.BookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController implements BookApiDocs {

    private final BookService bookService;
    private final BookCopyService bookCopyService;
    private final BookImportService bookImportService;

    @GetMapping
    @Override
    public ResponseEntity<ApiResponse<List<BookSummaryResponse>>> searchBooks(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String isbn,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean availableOnly,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort) {
        Page<BookSummaryResponse> books = bookService.searchBooks(
                q, title, isbn, authorId, author, categoryId, availableOnly, language, page, size, sort
        );

        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách sách thành công",
                books.getContent(),
                PageMeta.from(books)
        ));
    }
    // Lấy chi tiêt thông tin sách
    @GetMapping("/{bookId}")
    @Override
    public ResponseEntity<ApiResponse<BookDetailResponse>> getBook(@PathVariable Long bookId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy chi tiết sách thành công",
                bookService.getBook(bookId)
        ));
    }
    // Tạo sách mới thành công
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<BookDetailResponse>> createBook(@Valid @RequestBody CreateBookRequest request) {
        BookDetailResponse book = bookService.createBook(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo sách thành công", book));
    }
    // Cập nhật thông tin sách
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PatchMapping("/{bookId}")
    @Override
    public ResponseEntity<ApiResponse<BookDetailResponse>> updateBook(
            @PathVariable Long bookId,
            @Valid @RequestBody UpdateBookRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật sách thành công",
                bookService.updateBook(bookId, request)
        ));
    }
    // Xóa đầu sách trong hệ thống
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{bookId}")
    @Override
    public ResponseEntity<ApiResponse<Void>> deleteBook(
            @PathVariable Long bookId,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        bookService.deleteBook(bookId, getCurrentMemberId(userDetails));
        return ResponseEntity.ok(ApiResponse.success("Xóa sách thành công", null));
    }
    // Lấy danh sách bản sao của đầu sách đó
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @GetMapping("/{bookId}/copies")
    @Override
    public ResponseEntity<ApiResponse<List<BookCopyResponse>>> getBookCopies(@PathVariable Long bookId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách bản sao sách thành công",
                bookCopyService.getBookCopies(bookId)
        ));
    }
    // Tạo 1 bản copy mới cho sách đó
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping("/{bookId}/copies")
    @Override
    public ResponseEntity<ApiResponse<BookCopyResponse>> createBookCopy(
            @PathVariable Long bookId,
            @Valid @RequestBody CreateBookCopyRequest request) {
        BookCopyResponse copy = bookCopyService.createBookCopy(bookId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo bản sao sách thành công", copy));
    }
    // Tạo nhiều bản copy vật lý cho cùng một sách bằng danh sách barcode rõ ràng
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping("/{bookId}/copies/bulk")
    @Override
    public ResponseEntity<ApiResponse<List<BookCopyResponse>>> createBookCopies(
            @PathVariable Long bookId,
            @Valid @RequestBody BulkCreateBookCopiesRequest request) {
        List<BookCopyResponse> copies = bookCopyService.createBookCopies(bookId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo danh sách bản sao sách thành công", copies));
    }
    // Import sách và bản copy từ CSV. Mỗi dòng CSV tương ứng một bản copy vật lý.
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Override
    public ResponseEntity<ApiResponse<BookImportResultResponse>> importBooksFromCsv(
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(
                "Import sách từ CSV hoàn tất",
                bookImportService.importBooksFromCsv(file)
        ));
    }
    // Cập nhật tác giả cho sách
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PutMapping("/{bookId}/authors")
    @Override
    public ResponseEntity<ApiResponse<BookDetailResponse>> updateBookAuthors(
            @PathVariable Long bookId,
            @Valid @RequestBody UpdateBookAuthorsRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật tác giả của sách thành công",
                bookService.updateBookAuthors(bookId, request)
        ));
    }
    // Kiểm tra đăng nhập và trả về memberId của user hiện tại
    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}

