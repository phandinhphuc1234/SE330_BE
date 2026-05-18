package com.vn.repository;

import com.vn.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.lang.Nullable;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    // Lấy chi tiết đầu sách còn hoạt động, kèm category và authors để tránh N+1 query.
    @EntityGraph(attributePaths = {"category", "authors"})
    Optional<Book> findByIdAndDeletedAtIsNull(Long id);

    // Lock đầu sách khi tính queue giữ chỗ để hai request online không lấy cùng queuePosition.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Book> findLockedByIdAndDeletedAtIsNull(Long id);

    // Search/filter sách bằng Specification, load sẵn category và authors cho response list.
    @Override
    @EntityGraph(attributePaths = {"category", "authors"})
    Page<Book> findAll(@Nullable Specification<Book> spec, Pageable pageable);

    // Kiểm tra ISBN đã tồn tại chưa khi tạo/cập nhật sách.
    boolean existsByIsbnIgnoreCase(String isbn);

    // Tìm sách theo ISBN không phân biệt hoa/thường, dùng cho import và nghiệp vụ tra cứu.
    Optional<Book> findByIsbnIgnoreCase(String isbn);

    // Tìm sách còn hoạt động theo ISBN, bỏ qua sách đã soft delete.
    Optional<Book> findByIsbnIgnoreCaseAndDeletedAtIsNull(String isbn);

    // Cộng/trừ bộ đếm copy của sách sau khi tạo, xóa, mượn hoặc trả BookCopy.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Book book
            set book.totalCopies = book.totalCopies + :totalDelta,
                book.availableCopies = book.availableCopies + :availableDelta
            where book.id = :bookId
            """)
    int adjustCopyCounters(@Param("bookId") Long bookId,
                           @Param("totalDelta") int totalDelta,
                           @Param("availableDelta") int availableDelta);
}

