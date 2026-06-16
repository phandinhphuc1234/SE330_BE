package com.vn.repository;

import com.vn.entity.BookEbook;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.MediaProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookEbookRepository extends JpaRepository<BookEbook, Long> {

    // publicId được build cố định theo pdf/{isbn}/main.pdf nên upload lại sẽ update metadata row hiện có.
    Optional<BookEbook> findByProviderAndPublicId(MediaProvider provider, String publicId);

    // Public catalog chỉ lấy ebook ACTIVE mới nhất của một đầu sách để render trạng thái đọc/mượn.
    @EntityGraph(attributePaths = {"book"})
    Optional<BookEbook> findFirstByBookIdAndStatusOrderByIdDesc(Long bookId, BookEbookStatus status);

    // Staff/admin thao tác theo cả bookId và ebookId để tránh sửa nhầm ebook của sách khác.
    @EntityGraph(attributePaths = {"book"})
    Optional<BookEbook> findByIdAndBookId(Long id, Long bookId);

    // Lock row ebook khi cấp loan để số active loan không vượt max_concurrent_loans.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"book"})
    @Query("""
            select ebook
            from BookEbook ebook
            where ebook.id = :id
            """)
    Optional<BookEbook> findLockedById(@Param("id") Long id);
}
