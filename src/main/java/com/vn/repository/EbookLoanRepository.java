package com.vn.repository;

import com.vn.entity.EbookLoan;
import com.vn.enums.EbookLoanStatus;
import com.vn.repository.projection.StaffLoanRowProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EbookLoanRepository extends JpaRepository<EbookLoan, Long> {

    // Idempotency cho IPN retry: một payment chỉ được cấp tối đa một ebook loan.
    boolean existsByPaymentId(Long paymentId);

    Optional<EbookLoan> findByPaymentId(Long paymentId);

    // Chặn user mua lại ebook khi quyền đọc hiện tại vẫn còn hạn.
    boolean existsByMemberIdAndBookEbookIdAndStatusAndExpiredAtAfter(
            Long memberId,
            Long bookEbookId,
            EbookLoanStatus status,
            Instant now
    );

    // Đếm license đang dùng sau khi đã lock book_ebooks.
    long countByBookEbookIdAndStatusAndExpiredAtAfter(
            Long bookEbookId,
            EbookLoanStatus status,
            Instant now
    );

    // Đếm ebook loan còn hạn để áp dụng chung maxBorrowLimit với sách vật lý.
    long countByMemberIdAndStatusAndExpiredAtAfter(
            Long memberId,
            EbookLoanStatus status,
            Instant now
    );

    Page<EbookLoan> findByMemberIdAndStatusOrderByBorrowedAtDesc(
            Long memberId,
            EbookLoanStatus status,
            Pageable pageable
    );

    Page<EbookLoan> findByMemberIdOrderByBorrowedAtDesc(
            Long memberId,
            Pageable pageable
    );

    // Reader session chỉ được tạo từ loan ACTIVE còn hạn của chính member và ebook hiện tại.
    Optional<EbookLoan> findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
            Long memberId,
            Long bookId,
            Long bookEbookId,
            EbookLoanStatus status,
            Instant now
    );

    @Query(
            value = """
                    select *
                    from (
                        select
                            br.id as "borrowId",
                            m.id as "memberId",
                            m.full_name as "memberName",
                            m.email as "memberEmail",
                            b.id as "bookId",
                            b.title as "bookTitle",
                            bc.id as "bookCopyId",
                            bc.barcode as "itemBarcode",
                            bc.status as "copyStatus",
                            br.borrowed_at as "borrowedAt",
                            br.due_date as "dueDate",
                            br.returned_at as "returnedAt",
                            br.status as "status",
                            br.renew_count as "renewCount",
                            br.max_renewals_at_checkout as "maxRenewals",
                            coalesce(br.fine_amount, 0) as "fineAmount",
                            case
                                when coalesce(br.fine_amount, 0) <= 0 then 'NONE'
                                when br.fine_paid_at is not null then 'PAID'
                                when br.fine_waived_by is not null then 'WAIVED'
                                else 'UNPAID'
                            end as "fineStatus",
                            (
                                br.status = 'OVERDUE'
                                or (br.status = 'BORROWED' and br.due_date < :now)
                            ) as "overdue",
                            case
                                when br.status = 'OVERDUE'
                                     or (br.status = 'BORROWED' and br.due_date < :now)
                                then greatest(floor(extract(epoch from (:now - br.due_date)) / 86400), 0)::bigint
                                else 0::bigint
                            end as "daysOverdue",
                            'PHYSICAL' as "loanType",
                            null::bigint as "ebookLoanId",
                            null::bigint as "bookEbookId",
                            null::bigint as "paymentId",
                            null::timestamp as "expiredAt",
                            br.due_date as sort_due_at,
                            br.borrowed_at as sort_borrowed_at
                        from borrow_records br
                        join members m on m.id = br.member_id
                        join book_copies bc on bc.id = br.book_copy_id
                        join books b on b.id = bc.book_id
                        where (:memberId is null or m.id = :memberId)
                          and (:status is null or br.status = :status)
                          and (:openOnly is null or :openOnly = false or br.status in ('BORROWED', 'OVERDUE', 'LOST'))
                          and (
                                :overdue is null
                                or :overdue = false
                                or br.status = 'OVERDUE'
                                or (br.status = 'BORROWED' and br.due_date < :now)
                          )
                          and (cast(:dueFrom as timestamp) is null or br.due_date >= cast(:dueFrom as timestamp))
                          and (cast(:dueTo as timestamp) is null or br.due_date <= cast(:dueTo as timestamp))
                          and (
                                :qLike is null
                                or lower(m.full_name) like :qLike
                                or lower(m.email) like :qLike
                                or lower(coalesce(m.phone, '')) like :qLike
                                or lower(b.title) like :qLike
                                or lower(b.isbn) like :qLike
                                or lower(bc.barcode) like :qLike
                                or (
                                    :numericId is not null
                                    and (
                                        br.id = :numericId
                                        or m.id = :numericId
                                        or b.id = :numericId
                                        or bc.id = :numericId
                                    )
                                )
                          )

                        union all

                        select
                            null::bigint as "borrowId",
                            m.id as "memberId",
                            m.full_name as "memberName",
                            m.email as "memberEmail",
                            b.id as "bookId",
                            b.title as "bookTitle",
                            null::bigint as "bookCopyId",
                            null::varchar as "itemBarcode",
                            null::varchar as "copyStatus",
                            el.borrowed_at as "borrowedAt",
                            el.expired_at as "dueDate",
                            el.returned_at as "returnedAt",
                            el.status as "status",
                            null::integer as "renewCount",
                            null::integer as "maxRenewals",
                            0::numeric as "fineAmount",
                            'NONE' as "fineStatus",
                            (el.status = 'ACTIVE' and el.expired_at < :now) as "overdue",
                            case
                                when el.status = 'ACTIVE' and el.expired_at < :now
                                then greatest(floor(extract(epoch from (:now - el.expired_at)) / 86400), 0)::bigint
                                else 0::bigint
                            end as "daysOverdue",
                            'EBOOK' as "loanType",
                            el.id as "ebookLoanId",
                            el.book_ebook_id as "bookEbookId",
                            el.payment_id as "paymentId",
                            el.expired_at as "expiredAt",
                            el.expired_at as sort_due_at,
                            el.borrowed_at as sort_borrowed_at
                        from ebook_loans el
                        join members m on m.id = el.member_id
                        join books b on b.id = el.book_id
                        left join book_ebooks be on be.id = el.book_ebook_id
                        left join payment_transactions pt on pt.id = el.payment_id
                        where (:memberId is null or m.id = :memberId)
                          and (:status is null or el.status = :status)
                          and (:openOnly is null or :openOnly = false or el.status = 'ACTIVE')
                          and (
                                :overdue is null
                                or :overdue = false
                                or (el.status = 'ACTIVE' and el.expired_at < :now)
                          )
                          and (cast(:dueFrom as timestamp) is null or el.expired_at >= cast(:dueFrom as timestamp))
                          and (cast(:dueTo as timestamp) is null or el.expired_at <= cast(:dueTo as timestamp))
                          and (
                                :qLike is null
                                or lower(m.full_name) like :qLike
                                or lower(m.email) like :qLike
                                or lower(coalesce(m.phone, '')) like :qLike
                                or lower(b.title) like :qLike
                                or lower(b.isbn) like :qLike
                                or lower(coalesce(be.original_filename, '')) like :qLike
                                or lower(coalesce(pt.payment_code, '')) like :qLike
                                or (
                                    :numericId is not null
                                    and (
                                        el.id = :numericId
                                        or m.id = :numericId
                                        or b.id = :numericId
                                        or el.book_ebook_id = :numericId
                                        or el.payment_id = :numericId
                                    )
                                )
                          )
                    ) staff_loans
                    order by sort_due_at asc nulls last, sort_borrowed_at desc
                    """,
            countQuery = """
                    select count(*)
                    from (
                        select br.id
                        from borrow_records br
                        join members m on m.id = br.member_id
                        join book_copies bc on bc.id = br.book_copy_id
                        join books b on b.id = bc.book_id
                        where (:memberId is null or m.id = :memberId)
                          and (:status is null or br.status = :status)
                          and (:openOnly is null or :openOnly = false or br.status in ('BORROWED', 'OVERDUE', 'LOST'))
                          and (
                                :overdue is null
                                or :overdue = false
                                or br.status = 'OVERDUE'
                                or (br.status = 'BORROWED' and br.due_date < :now)
                          )
                          and (cast(:dueFrom as timestamp) is null or br.due_date >= cast(:dueFrom as timestamp))
                          and (cast(:dueTo as timestamp) is null or br.due_date <= cast(:dueTo as timestamp))
                          and (
                                :qLike is null
                                or lower(m.full_name) like :qLike
                                or lower(m.email) like :qLike
                                or lower(coalesce(m.phone, '')) like :qLike
                                or lower(b.title) like :qLike
                                or lower(b.isbn) like :qLike
                                or lower(bc.barcode) like :qLike
                                or (
                                    :numericId is not null
                                    and (
                                        br.id = :numericId
                                        or m.id = :numericId
                                        or b.id = :numericId
                                        or bc.id = :numericId
                                    )
                                )
                          )

                        union all

                        select el.id
                        from ebook_loans el
                        join members m on m.id = el.member_id
                        join books b on b.id = el.book_id
                        left join book_ebooks be on be.id = el.book_ebook_id
                        left join payment_transactions pt on pt.id = el.payment_id
                        where (:memberId is null or m.id = :memberId)
                          and (:status is null or el.status = :status)
                          and (:openOnly is null or :openOnly = false or el.status = 'ACTIVE')
                          and (
                                :overdue is null
                                or :overdue = false
                                or (el.status = 'ACTIVE' and el.expired_at < :now)
                          )
                          and (cast(:dueFrom as timestamp) is null or el.expired_at >= cast(:dueFrom as timestamp))
                          and (cast(:dueTo as timestamp) is null or el.expired_at <= cast(:dueTo as timestamp))
                          and (
                                :qLike is null
                                or lower(m.full_name) like :qLike
                                or lower(m.email) like :qLike
                                or lower(coalesce(m.phone, '')) like :qLike
                                or lower(b.title) like :qLike
                                or lower(b.isbn) like :qLike
                                or lower(coalesce(be.original_filename, '')) like :qLike
                                or lower(coalesce(pt.payment_code, '')) like :qLike
                                or (
                                    :numericId is not null
                                    and (
                                        el.id = :numericId
                                        or m.id = :numericId
                                        or b.id = :numericId
                                        or el.book_ebook_id = :numericId
                                        or el.payment_id = :numericId
                                    )
                                )
                          )
                    ) staff_loans_count
                    """,
            nativeQuery = true
    )
    Page<StaffLoanRowProjection> searchStaffLoansIncludingEbooks(@Param("memberId") Long memberId,
                                                                 @Param("qLike") String qLike,
                                                                 @Param("numericId") Long numericId,
                                                                 @Param("status") String status,
                                                                 @Param("openOnly") Boolean openOnly,
                                                                 @Param("overdue") Boolean overdue,
                                                                 @Param("dueFrom") Instant dueFrom,
                                                                 @Param("dueTo") Instant dueTo,
                                                                 @Param("now") Instant now,
                                                                 Pageable pageable);

    @Query("""
            select loan.memberId,
                   sum(case when loan.status = :activeStatus then 1 else 0 end),
                   sum(case when loan.status = :activeStatus then 1 else 0 end),
                   sum(case when loan.status = :activeStatus and loan.expiredAt < :now then 1 else 0 end),
                   count(loan.id)
            from EbookLoan loan
            where loan.memberId in :memberIds
            group by loan.memberId
            """)
    List<Object[]> summarizeEbookLoanCountsByMemberIds(@Param("memberIds") Collection<Long> memberIds,
                                                       @Param("activeStatus") EbookLoanStatus activeStatus,
                                                       @Param("now") Instant now);

    long countByStatus(EbookLoanStatus status);

    long countByBorrowedAtGreaterThanEqualAndBorrowedAtLessThan(Instant startInclusive, Instant endExclusive);

    long countByReturnedAtGreaterThanEqualAndReturnedAtLessThan(Instant startInclusive, Instant endExclusive);

    @Query("""
            select count(loan.id)
            from EbookLoan loan
            where loan.status = :activeStatus
              and loan.expiredAt < :now
            """)
    long countOverdueEbookLoans(@Param("activeStatus") EbookLoanStatus activeStatus,
                                @Param("now") Instant now);
}
