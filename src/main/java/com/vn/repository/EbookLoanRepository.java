package com.vn.repository;

import com.vn.entity.EbookLoan;
import com.vn.enums.EbookLoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EbookLoanRepository extends JpaRepository<EbookLoan, Long> {

    /**
     * Lấy tất cả loan đang ACTIVE của một member.
     */
    List<EbookLoan> findByMemberIdAndStatus(Long memberId, EbookLoanStatus status);

    /**
     * Đếm số loan đang ACTIVE của member (giới hạn concurrent borrow).
     */
    long countByMemberIdAndStatus(Long memberId, EbookLoanStatus status);

    /**
     * Kiểm tra member đang mượn ebook cụ thể hay chưa.
     */
    Optional<EbookLoan> findByMemberIdAndBookIdAndStatus(Long memberId, Long bookId, EbookLoanStatus status);

    /**
     * Lịch sử mượn ebook của member (tất cả status), sort mới nhất trước.
     */
    Page<EbookLoan> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);

    /**
     * Dành cho scheduler: tìm các loan ACTIVE đã hết hạn để chuyển sang EXPIRED.
     */
    @Query("SELECT e FROM EbookLoan e WHERE e.status = 'ACTIVE' AND e.expiresAt < :now")
    List<EbookLoan> findExpiredActiveLoans(@Param("now") Instant now);

    /**
     * Bulk expire — dùng trong scheduler để update hàng loạt.
     */
    @Modifying
    @Query("UPDATE EbookLoan e SET e.status = 'EXPIRED', e.updatedAt = :now " +
           "WHERE e.status = 'ACTIVE' AND e.expiresAt < :now")
    int bulkExpireLoans(@Param("now") Instant now);

    /**
     * Staff: xem tất cả ebook loans có phân trang + filter.
     */
    @Query("SELECT e FROM EbookLoan e JOIN FETCH e.member m JOIN FETCH e.book b " +
           "WHERE (:status IS NULL OR e.status = :status) " +
           "AND (:memberId IS NULL OR m.id = :memberId) " +
           "ORDER BY e.createdAt DESC")
    Page<EbookLoan> findAllWithFilters(
            @Param("status") EbookLoanStatus status,
            @Param("memberId") Long memberId,
            Pageable pageable);
}
