package com.vn.repository;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BorrowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BorrowRecordRepository extends JpaRepository<BorrowRecord, Long> {

    // Đếm số lượt mượn đang mở của member để kiểm tra giới hạn mượn.
    long countByMemberIdAndStatusIn(Long memberId, Collection<BorrowStatus> statuses);

    // Dashboard staff: đếm các lượt mượn còn tính là active theo nghiệp vụ.
    long countByStatusIn(Collection<BorrowStatus> statuses);

    // Dashboard staff: số lượt checkout phát sinh trong ngày nghiệp vụ hiện tại.
    long countByBorrowedAtGreaterThanEqualAndBorrowedAtLessThan(Instant startInclusive, Instant endExclusive);

    // Dashboard staff: số lượt check-in/returned phát sinh trong ngày nghiệp vụ hiện tại.
    long countByReturnedAtGreaterThanEqualAndReturnedAtLessThan(Instant startInclusive, Instant endExclusive);

    // Dashboard staff: đếm overdue theo cả status OVERDUE và trường hợp BORROWED nhưng dueDate đã qua.
    @Query("""
            select count(borrow.id)
            from BorrowRecord borrow
            where borrow.status = :overdueStatus
               or (borrow.status = :borrowedStatus and borrow.dueDate < :now)
            """)
    long countOverdueLoans(@Param("overdueStatus") BorrowStatus overdueStatus,
                           @Param("borrowedStatus") BorrowStatus borrowedStatus,
                           @Param("now") Instant now);

    // Dashboard staff: đếm các borrow record còn tiền phạt chưa paid/waived.
    @Query("""
            select count(borrow.id)
            from BorrowRecord borrow
            where borrow.fineAmount > 0
              and borrow.finePaidAt is null
              and borrow.fineWaivedBy is null
            """)
    long countUnpaidFineRecords();

    // Dashboard staff: tổng tiền phạt còn phải thu, bỏ qua fine đã paid hoặc waived.
    @Query("""
            select coalesce(sum(borrow.fineAmount), 0)
            from BorrowRecord borrow
            where borrow.fineAmount > 0
              and borrow.finePaidAt is null
              and borrow.fineWaivedBy is null
            """)
    BigDecimal sumUnpaidFineTotal();

    // Kiểm tra member có lượt mượn quá hạn không, dùng để chặn checkout/renewal.
    boolean existsByMemberIdAndStatus(Long memberId, BorrowStatus status);

    // Kiểm tra member đã có lượt mượn đang mở cho cùng một đầu sách hay chưa.
    @Query("""
            select count(borrow) > 0
            from BorrowRecord borrow
            where borrow.member.id = :memberId
              and borrow.bookCopy.book.id = :bookId
              and borrow.status in :statuses
            """)
    boolean existsOpenBorrowForMemberAndBook(@Param("memberId") Long memberId,
                                             @Param("bookId") Long bookId,
                                             @Param("statuses") Collection<BorrowStatus> statuses);

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

    // Lấy các lượt mượn đã phát sinh tiền phạt của member hiện tại.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    Page<BorrowRecord> findByMemberIdAndFineAmountGreaterThanOrderByFineCalculatedAtDesc(
            Long memberId,
            BigDecimal minimumFineAmount,
            Pageable pageable
    );

    // Lấy một lượt mượn kèm member, copy và book để xử lý gia hạn hoặc nghiệp vụ staff.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    Optional<BorrowRecord> findById(Long id);

    // Lock lượt mượn khi gia hạn từ job nền để tránh renewCount bị tăng trùng với user/staff renew.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    @Query("""
            select borrow
            from BorrowRecord borrow
            where borrow.id = :id
            """)
    Optional<BorrowRecord> findLockedForRenewalById(@Param("id") Long id);

    // Lock lượt mượn khi job overdue xử lý để không đụng với checkin/renewal cùng thời điểm.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    @Query("""
            select borrow
            from BorrowRecord borrow
            where borrow.id = :id
            """)
    Optional<BorrowRecord> findLockedForOverdueById(@Param("id") Long id);

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

    // Lấy các lượt mượn sắp đến hạn trong một ngày nghiệp vụ để job auto-renewal xử lý.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    @Query("""
            select borrow
            from BorrowRecord borrow
            where borrow.status = :status
              and borrow.dueDate >= :windowStart
              and borrow.dueDate < :windowEnd
            order by borrow.dueDate asc
            """)
    Page<BorrowRecord> findAutoRenewalCandidates(@Param("status") BorrowStatus status,
                                                 @Param("windowStart") Instant windowStart,
                                                 @Param("windowEnd") Instant windowEnd,
                                                 Pageable pageable);

    // Lấy các lượt mượn sắp đến hạn trong một ngày nghiệp vụ để job gửi email nhắc trả.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    @Query("""
            select borrow
            from BorrowRecord borrow
            where borrow.status = :status
              and borrow.dueDate >= :windowStart
              and borrow.dueDate < :windowEnd
            order by borrow.dueDate asc
            """)
    Page<BorrowRecord> findDueSoonReminderCandidates(@Param("status") BorrowStatus status,
                                                     @Param("windowStart") Instant windowStart,
                                                     @Param("windowEnd") Instant windowEnd,
                                                     Pageable pageable);

    // Đếm nhanh các loại borrow theo member để dựng danh sách/detail borrower cho staff.
    @Query("""
            select borrow.member.id,
                   sum(case when borrow.status in :activeStatuses then 1 else 0 end),
                   sum(case when borrow.status in :openStatuses then 1 else 0 end),
                   sum(case
                           when borrow.status = :overdueStatus
                                or (borrow.status = :borrowedStatus and borrow.dueDate < :now)
                           then 1 else 0
                       end),
                   count(borrow.id)
            from BorrowRecord borrow
            where borrow.member.id in :memberIds
            group by borrow.member.id
            """)
    List<Object[]> summarizeBorrowCountsByMemberIds(@Param("memberIds") Collection<Long> memberIds,
                                                    @Param("activeStatuses") Collection<BorrowStatus> activeStatuses,
                                                    @Param("openStatuses") Collection<BorrowStatus> openStatuses,
                                                    @Param("overdueStatus") BorrowStatus overdueStatus,
                                                    @Param("borrowedStatus") BorrowStatus borrowedStatus,
                                                    @Param("now") Instant now);

    // Tính tổng tiền phạt chưa thanh toán/chưa được miễn theo member.
    @Query("""
            select borrow.member.id,
                   coalesce(sum(borrow.fineAmount), 0)
            from BorrowRecord borrow
            where borrow.member.id in :memberIds
              and borrow.fineAmount > 0
              and borrow.finePaidAt is null
              and borrow.fineWaivedBy is null
            group by borrow.member.id
            """)
    List<Object[]> summarizeUnpaidFineTotalsByMemberIds(@Param("memberIds") Collection<Long> memberIds);

    @Query("""
            select count(borrow.id)
            from BorrowRecord borrow
            where borrow.member.id = :memberId
              and borrow.fineAmount > 0
              and borrow.finePaidAt is null
              and borrow.fineWaivedBy is null
            """)
    long countUnpaidFinesByMemberId(@Param("memberId") Long memberId);

    // Tìm kiếm loan cho màn staff loans và tab loans trong hồ sơ member.
    @EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
    @Query("""
            select borrow
            from BorrowRecord borrow
            join borrow.member member
            join borrow.bookCopy copy
            join copy.book book
            where (:memberId is null or member.id = :memberId)
              and (:status is null or borrow.status = :status)
              and (:openOnly is null or :openOnly = false or borrow.status in :openStatuses)
              and (
                    :overdue is null
                    or :overdue = false
                    or borrow.status = :overdueStatus
                    or (borrow.status = :borrowedStatus and borrow.dueDate < :now)
              )
              and borrow.dueDate >= coalesce(:dueFrom, borrow.dueDate)
              and borrow.dueDate <= coalesce(:dueTo, borrow.dueDate)
              and (
                    :qLike is null
                    or lower(member.fullName) like :qLike
                    or lower(member.email) like :qLike
                    or lower(coalesce(member.phone, '')) like :qLike
                    or lower(book.title) like :qLike
                    or lower(book.isbn) like :qLike
                    or lower(copy.barcode) like :qLike
                    or (
                        :numericId is not null
                        and (
                            borrow.id = :numericId
                            or member.id = :numericId
                            or book.id = :numericId
                            or copy.id = :numericId
                        )
                    )
              )
            """)
    Page<BorrowRecord> searchStaffLoans(@Param("memberId") Long memberId,
                                        @Param("qLike") String qLike,
                                        @Param("numericId") Long numericId,
                                        @Param("status") BorrowStatus status,
                                        @Param("openOnly") Boolean openOnly,
                                        @Param("openStatuses") Collection<BorrowStatus> openStatuses,
                                        @Param("overdue") Boolean overdue,
                                        @Param("overdueStatus") BorrowStatus overdueStatus,
                                        @Param("borrowedStatus") BorrowStatus borrowedStatus,
                                        @Param("dueFrom") Instant dueFrom,
                                        @Param("dueTo") Instant dueTo,
                                        @Param("now") Instant now,
                                        Pageable pageable);
}
