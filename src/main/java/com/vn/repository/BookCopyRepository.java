package com.vn.repository;

import com.vn.entity.BookCopy;
import com.vn.enums.BookCopyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BookCopyRepository extends JpaRepository<BookCopy, Long> {

    Optional<BookCopy> findByIdAndDeletedAtIsNull(Long id);

    List<BookCopy> findByBookIdAndDeletedAtIsNullOrderByIdAsc(Long bookId);

    boolean existsByBarcodeIgnoreCase(String barcode);
    // // Dùng khi import CSV để kiểm tra hàng loạt barcode đã tồn tại, tránh query từng dòng
    @Query("""
            select lower(copy.barcode)
            from BookCopy copy
            where lower(copy.barcode) in :barcodes
            """)
    Set<String> findExistingLowerBarcodes(@Param("barcodes") Collection<String> barcodes);

    boolean existsByBookIdAndStatusInAndDeletedAtIsNull(Long bookId, Collection<BookCopyStatus> statuses);

    long countByBookIdAndDeletedAtIsNull(Long bookId);

    long countByBookIdAndStatusAndDeletedAtIsNull(Long bookId, BookCopyStatus status);

    @Query(value = "select count(br.id) > 0 from borrow_records br where br.book_copy_id = :copyId", nativeQuery = true)
    boolean existsBorrowHistoryByCopyId(@Param("copyId") Long copyId);
    // Bulk soft delete các copy của book để tránh load từng entity lên memory

    // Các copy đang BORROWED/RESERVED sẽ được loại trừ bằng excludedStatuses
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

