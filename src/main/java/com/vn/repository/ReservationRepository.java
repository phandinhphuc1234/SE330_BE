package com.vn.repository;

import com.vn.entity.Reservation;
import com.vn.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // Kiểm tra một đầu sách có hold/reservation đang hoạt động không, dùng để chặn renewal khi có người chờ.
    boolean existsByBookIdAndStatusIn(Long bookId, Collection<ReservationStatus> statuses);

    // Kiểm tra member đã có hold đang hoạt động cho đầu sách chưa.
    boolean existsByMemberIdAndBookIdAndStatusIn(Long memberId, Long bookId, Collection<ReservationStatus> statuses);

    // Lấy hold theo id kèm member, book và assignedCopy để xử lý cancel/checkout.
    @Override
    @EntityGraph(attributePaths = {"member", "book", "assignedCopy"})
    Optional<Reservation> findById(Long id);

    // Lấy danh sách hold của member để hiển thị trong trang tài khoản.
    @EntityGraph(attributePaths = {"book", "assignedCopy"})
    Page<Reservation> findByMemberIdOrderByReservedAtDesc(Long memberId, Pageable pageable);

    // Lọc danh sách hold của member theo trạng thái.
    @EntityGraph(attributePaths = {"book", "assignedCopy"})
    Page<Reservation> findByMemberIdAndStatusOrderByReservedAtDesc(Long memberId, ReservationStatus status, Pageable pageable);

    // Lấy queuePosition lớn nhất từng được dùng của một đầu sách để sinh vị trí queue tiếp theo.
    @Query("""
            select coalesce(max(reservation.queuePosition), 0)
            from Reservation reservation
            where reservation.book.id = :bookId
            """)
    int findMaxQueuePositionByBookId(@Param("bookId") Long bookId);

    // Lấy người đầu tiên đang WAITING trong queue của một đầu sách.
    @Query("""
            select reservation
            from Reservation reservation
            join fetch reservation.member
            join fetch reservation.book
            where reservation.book.id = :bookId
              and reservation.status = :status
            order by reservation.queuePosition asc nulls last, reservation.reservedAt asc
            """)
    List<Reservation> findQueueHead(@Param("bookId") Long bookId,
                                    @Param("status") ReservationStatus status,
                                    Pageable pageable);

    // Lấy các hold đã được báo sẵn sàng nhưng quá hạn lấy sách để scheduled job expire.
    @EntityGraph(attributePaths = {"member", "book", "assignedCopy", "assignedCopy.book"})
    @Query("""
            select reservation
            from Reservation reservation
            where reservation.status in :statuses
              and reservation.expiresAt < :now
            order by reservation.expiresAt asc
            """)
    Page<Reservation> findExpiredReadyHoldCandidates(@Param("statuses") Collection<ReservationStatus> statuses,
                                                     @Param("now") Instant now,
                                                     Pageable pageable);

    // Lock một hold trước khi expire để không đụng với staff checkout/cancel cùng thời điểm.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"member", "book", "assignedCopy", "assignedCopy.book"})
    @Query("""
            select reservation
            from Reservation reservation
            where reservation.id = :id
            """)
    Optional<Reservation> findLockedForExpiryById(@Param("id") Long id);
}
