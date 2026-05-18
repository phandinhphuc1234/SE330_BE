package com.vn.repository;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BorrowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface BorrowRecordRepository extends JpaRepository<BorrowRecord, Long> {

    // Đếm số lượt mượn đang mở của member để kiểm tra giới hạn mượn.
    long countByMemberIdAndStatusIn(Long memberId, Collection<BorrowStatus> statuses);

    // Kiểm tra member có lượt mượn quá hạn không, dùng để chặn checkout/renewal.
    boolean existsByMemberIdAndStatus(Long memberId, BorrowStatus status);

    // Lấy danh sách lượt mượn đang mở của member, kèm thông tin sách để hiển thị cho user.
    @EntityGraph(attributePaths = {"bookCopy", "bookCopy.book", "bookCopy.book.authors", "bookCopy.book.category"})
    Page<BorrowRecord> findByMemberIdAndStatusInOrderByBorrowedAtDesc(
            Long memberId,
            Collection<BorrowStatus> statuses,
            Pageable pageable
    );

    // Lấy lịch sử mượn của member, sắp xếp lượt mới nhất trước.
    @EntityGraph(attributePaths = {"bookCopy", "bookCopy.book", "bookCopy.book.authors", "bookCopy.book.category"})
    Page<BorrowRecord> findByMemberIdOrderByBorrowedAtDesc(Long memberId, Pageable pageable);

    // Lấy một lượt mượn kèm member, copy và book để xử lý gia hạn hoặc nghiệp vụ staff.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    Optional<BorrowRecord> findById(Long id);

    // Tìm lượt mượn đang mở gần nhất của một bản sách, dùng khi check-in bằng barcode.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    Optional<BorrowRecord> findFirstByBookCopyIdAndStatusInOrderByBorrowedAtDesc(
            Long bookCopyId,
            Collection<BorrowStatus> statuses
    );

    // Lấy các lượt mượn đã quá hạn để job chuyển trạng thái hoặc gửi thông báo.
    @Query("""
            select borrow
            from BorrowRecord borrow
            where borrow.status = :status
              and borrow.dueDate < :now
            """)
    Page<BorrowRecord> findOverdueCandidates(@Param("status") BorrowStatus status,
                                             @Param("now") Instant now,
                                             Pageable pageable);
}
