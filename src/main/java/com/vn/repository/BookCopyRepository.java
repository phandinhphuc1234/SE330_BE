package com.vn.repository;

import com.vn.entity.BookCopy;
import com.vn.enums.BookCopyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BookCopyRepository extends JpaRepository<BookCopy, Long> {

    // Tìm một bản sách còn hoạt động theo id, bỏ qua bản đã soft delete.
    Optional<BookCopy> findByIdAndDeletedAtIsNull(Long id);

    // Tìm bản sách theo barcode không phân biệt hoa/thường, dùng cho checkout/checkin/preview.
    @EntityGraph(attributePaths = {"book", "book.authors", "book.category"})
    Optional<BookCopy> findByBarcodeIgnoreCaseAndDeletedAtIsNull(String barcode);

    // Lấy toàn bộ bản sách vật lý của một đầu sách, dùng cho màn quản lý copy.
    List<BookCopy> findByBookIdAndDeletedAtIsNullOrderByIdAsc(Long bookId);

    // Lọc bản sách vật lý của một đầu sách theo trạng thái, barcode, tình trạng và vị trí.
    @EntityGraph(attributePaths = {"book"})
    @Query("""
            select copy
            from BookCopy copy
            where copy.book.id = :bookId
              and copy.deletedAt is null
              and (:status is null or copy.status = :status)
              and (:barcode is null or lower(copy.barcode) like :barcode)
              and (:conditionLike is null or lower(copy.condition) like :conditionLike)
              and (:locationLike is null or lower(copy.location) like :locationLike)
            order by copy.id asc
            """)
    List<BookCopy> searchActiveCopiesByBookId(@Param("bookId") Long bookId,
                                              @Param("status") BookCopyStatus status,
                                              @Param("barcode") String barcode,
                                              @Param("conditionLike") String condition,
                                              @Param("locationLike") String location);

    // Kiểm tra barcode đã tồn tại chưa khi tạo/cập nhật/import book copy.
    boolean existsByBarcodeIgnoreCase(String barcode);

    // Dùng khi import CSV để kiểm tra hàng loạt barcode đã tồn tại, tránh query từng dòng.
    @Query("""
            select lower(copy.barcode)
            from BookCopy copy
            where lower(copy.barcode) in :barcodes
            """)
    Set<String> findExistingLowerBarcodes(@Param("barcodes") Collection<String> barcodes);

    // Kiểm tra một đầu sách có copy thuộc các trạng thái nhất định hay không.
    boolean existsByBookIdAndStatusInAndDeletedAtIsNull(Long bookId, Collection<BookCopyStatus> statuses);

    // Đếm tổng số copy còn hoạt động của một đầu sách.
    long countByBookIdAndDeletedAtIsNull(Long bookId);

    // Đếm số copy theo trạng thái cụ thể, ví dụ AVAILABLE để đồng bộ availableCopies.
    long countByBookIdAndStatusAndDeletedAtIsNull(Long bookId, BookCopyStatus status);

    // Kiểm tra copy đã từng phát sinh lịch sử mượn chưa trước khi cho phép xóa.
    @Query(value = "select count(br.id) > 0 from borrow_records br where br.book_copy_id = :copyId", nativeQuery = true)
    boolean existsBorrowHistoryByCopyId(@Param("copyId") Long copyId);

    // Soft delete hàng loạt copy của một đầu sách mà không cần load từng entity lên memory.
    // Các copy đang ở trạng thái không được xóa sẽ được loại trừ bằng excludedStatuses.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookCopy copy
            set copy.deletedAt = CURRENT_TIMESTAMP,
                copy.deletedBy = :deletedBy
            where copy.book.id = :bookId
              and copy.deletedAt is null
              and copy.status not in :excludedStatuses
            """)
    int softDeleteByBookIdExcludingStatuses(@Param("bookId") Long bookId,
                                            @Param("deletedBy") Long deletedBy,
                                            @Param("excludedStatuses") Collection<BookCopyStatus> excludedStatuses);
}
