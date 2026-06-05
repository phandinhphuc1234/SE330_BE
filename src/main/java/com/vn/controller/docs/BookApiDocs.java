package com.vn.controller.docs;

import com.vn.dto.catalog.request.CreateBookCopyRequest;
import com.vn.dto.catalog.request.BulkCreateBookCopiesRequest;
import com.vn.dto.catalog.request.CreateBookRequest;
import com.vn.dto.catalog.request.UpdateBookAuthorsRequest;
import com.vn.dto.catalog.request.UpdateBookRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookImportJobResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Books", description = "APIs for searching, viewing and managing book catalog")
public interface BookApiDocs {

    @SecurityRequirements
    @Operation(
            summary = "Search books",
            description = """
                    Public API for searching and filtering active books.
                    Supports keyword, title, ISBN, author, category, availability, language, pagination and sorting.
                    Each book item includes coverImage for frontend rendering.
                    Sort format: field,direction. Allowed fields: title, publishedDate, createdAt, availableCopies.
                    """
    )
    ResponseEntity<ApiResponse<List<BookSummaryResponse>>> searchBooks(
            @Parameter(description = "Quick keyword. Searches by title or ISBN") String q,
            @Parameter(description = "Filter by book title") String title,
            @Parameter(description = "Filter by ISBN") String isbn,
            @Parameter(description = "Filter by author ID") Long authorId,
            @Parameter(description = "Filter by author name") String author,
            @Parameter(description = "Filter by category ID") Long categoryId,
            @Parameter(description = "Only return books with available copies") Boolean availableOnly,
            @Parameter(description = "Filter by language, for example vi or en") String language,
            @Parameter(description = "Page number, starts from 0") int page,
            @Parameter(description = "Page size, maximum allowed size is 100") int size,
            @Parameter(description = "Sort format: title,asc or createdAt,desc") String sort
    );

    @SecurityRequirements
    @Operation(
            summary = "Get book detail",
            description = "Public API for getting detailed information of an active book, including coverImage when configured."
    )
    ResponseEntity<ApiResponse<BookDetailResponse>> getBook(
            @Parameter(description = "Book ID", required = true) Long bookId
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Create book",
            description = """
                    Create a new book metadata record. This API only creates the book information.
                    Request imageUrl can attach a Cloudinary cover URL as the book's primary cover image metadata.
                    Physical copies must be created separately using POST /api/books/{bookId}/copies.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<BookDetailResponse>> createBook(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Book metadata")
            CreateBookRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update book",
            description = """
                    Partially update book metadata such as title, published date, language, edition or category.
                    Request imageUrl can be updated independently; blank value clears the primary cover image.
                    ISBN is intentionally immutable here because CSV import and copy management use it as a catalog identity.
                    """
    )
    ResponseEntity<ApiResponse<BookDetailResponse>> updateBook(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Book fields to update")
            UpdateBookRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Delete book",
            description = """
                    Soft delete a book and its non-active copies.
                    The book cannot be deleted if it has BORROWED or RESERVED copies.
                    Only Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<Void>> deleteBook(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get book copies",
            description = """
                    Get active physical copies of a book.
                    Optional filters: status, barcode, condition and location.
                    Status values: AVAILABLE, BORROWED, RESERVED, OVERDUE, ON_HOLD_SHELF, LOST, DAMAGED, REMOVED.
                    Intended for Librarian/Admin because it exposes barcode, status, condition and location.
                    """
    )
    ResponseEntity<ApiResponse<List<BookCopyResponse>>> getBookCopies(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "Filter by copy status, for example AVAILABLE") String status,
            @Parameter(description = "Case-insensitive partial barcode search") String barcode,
            @Parameter(description = "Case-insensitive partial condition search") String condition,
            @Parameter(description = "Case-insensitive partial location search") String location
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Create book copy",
            description = """
                    Create one physical copy for an existing active book.
                    Barcode must be unique. New copies are created with AVAILABLE status.
                    """
    )
    ResponseEntity<ApiResponse<BookCopyResponse>> createBookCopy(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Physical copy information")
            CreateBookCopyRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Bulk create book copies",
            description = """
                    Create multiple physical copies for one existing active book.
                    Each copy must provide its own unique barcode.
                    This endpoint is useful when the library receives several physical copies of the same title.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<List<BookCopyResponse>>> createBookCopies(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "List of physical copies")
            BulkCreateBookCopiesRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Import books from CSV",
            description = """
                    Start an asynchronous CSV import job for books and physical copies.
                    Required headers: title,isbn,authors,category,barcode.
                    Optional headers: condition,location,language,published_date,edition.
                    Each row creates one physical copy. Existing books are matched by active ISBN;
                    missing authors and categories are created automatically.
                    The response returns a jobId. Poll GET /api/books/import-csv/{jobId} for progress and row errors.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<BookImportJobResponse>> importBooksFromCsv(
            @Parameter(description = "CSV file to import", required = true)
            MultipartFile file
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get CSV import job status",
            description = """
                    Get current status, progress counters and row-level errors of a CSV import job.
                    Status values: PENDING, PROCESSING, COMPLETED, FAILED.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<BookImportJobResponse>> getBookImportJob(
            @Parameter(description = "CSV import job ID", required = true) UUID jobId
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update book authors",
            description = """
                    Replace the entire author list of a book.
                    If the current authors are [1, 2] and the request authorIds are [3, 4],
                    the final author list will be [3, 4].
                    """
    )
    ResponseEntity<ApiResponse<BookDetailResponse>> updateBookAuthors(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "New author ID list")
            UpdateBookAuthorsRequest request
    );
}

