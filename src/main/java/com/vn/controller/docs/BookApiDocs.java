package com.vn.controller.docs;

import com.vn.dto.catalog.request.CreateBookCopyRequest;
import com.vn.dto.catalog.request.BulkCreateBookCopiesRequest;
import com.vn.dto.catalog.request.CreateBookRequest;
import com.vn.dto.catalog.request.UpdateBookAuthorsRequest;
import com.vn.dto.catalog.request.UpdateBookRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.dto.catalog.response.BookCoverManagementResponse;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.ebook.request.UpdateBookEbookRequest;
import com.vn.dto.ebook.response.BookEbookManagementResponse;
import com.vn.dto.ebook.response.BookEbookPublicResponse;
import com.vn.dto.ebook.response.BookEbookUploadResponse;
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
                    Create a new book metadata record.
                    Cover image upload is handled separately by POST /api/books/{bookId}/cover.
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
                    Cover image replacement is handled separately by PUT /api/books/{bookId}/cover.
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
            summary = "Add book cover",
            description = """
                    Upload a new primary cover image for a book that does not already have an active cover.
                    The file is uploaded to Cloudinary first, then metadata is stored in book_images.
                    Supported file types: JPG, PNG, WEBP. Maximum size: 5MB.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<BookCoverManagementResponse>> addBookCover(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "Cover image file", required = true) MultipartFile file
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update book cover",
            description = """
                    Replace the active primary cover image of a book.
                    Backend uploads the new image before changing database metadata, then marks the old image DELETE_PENDING
                    and attempts to purge it from Cloudinary after the database transaction succeeds.
                    Supported file types: JPG, PNG, WEBP. Maximum size: 5MB.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<BookCoverManagementResponse>> updateBookCover(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "New cover image file", required = true) MultipartFile file
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Upload ebook PDF",
            description = """
                    Upload an ebook PDF for a book to Cloudinary as a protected raw/authenticated asset.
                    The Cloudinary public ID is built as pdf/{isbn}/main.pdf, for example pdf/9780132350884/main.pdf.
                    The response returns metadata only; it does not return a public PDF URL.
                    Supported file type: PDF. Maximum size: 100MB.
                    Librarian and Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<BookEbookUploadResponse>> uploadBookEbook(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "PDF file", required = true) MultipartFile file
    );

    @SecurityRequirements
    @Operation(
            summary = "Get ebook info for book detail page",
            description = """
                    Public read API for rendering ebook availability, access type, fee, currency and duration on a book page.
                    This response intentionally does not expose Cloudinary publicId, signed URL or any direct PDF URL.
                    """
    )
    ResponseEntity<ApiResponse<BookEbookPublicResponse>> getBookEbookForCatalog(
            @Parameter(description = "Book ID", required = true) Long bookId
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get ebook management detail",
            description = """
                    Get full ebook metadata for staff/admin edit screens.
                    Includes storage metadata such as provider, publicId, deliveryType and file metadata.
                    It still does not return a readable PDF URL.
                    """
    )
    ResponseEntity<ApiResponse<BookEbookManagementResponse>> getBookEbookForManagement(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "Book ebook ID", required = true) Long bookEbookId
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update ebook metadata",
            description = """
                    Partially update ebook policy fields for one book ebook.
                    Editable fields: maxConcurrentLoans, loanDurationDays, accessType, accessFee, currency,
                    accessDurationDays and status. Use POST /api/books/{bookId}/ebooks to replace the PDF file itself.
                    accessType supports FREE and PAID only.
                    """
    )
    ResponseEntity<ApiResponse<BookEbookManagementResponse>> updateBookEbook(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "Book ebook ID", required = true) Long bookEbookId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Ebook metadata fields to update")
            UpdateBookEbookRequest request
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

