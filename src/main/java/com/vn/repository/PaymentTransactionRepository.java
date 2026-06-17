package com.vn.repository;

import com.vn.entity.PaymentTransaction;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentStatus;
import com.vn.enums.PaymentTargetType;
import com.vn.repository.projection.PaymentReceiptRowProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    // Tránh trùng payment_code khi generator dùng timestamp + random suffix.
    boolean existsByPaymentCode(String paymentCode);

    // Chặn user tạo nhiều payment PENDING/SUCCESS cho cùng một ebook.
    boolean existsByMemberIdAndPurposeAndTargetTypeAndTargetIdAndStatus(
            Long memberId,
            PaymentPurpose purpose,
            PaymentTargetType targetType,
            Long targetId,
            PaymentStatus status
    );

    Optional<PaymentTransaction> findByIdAndMemberId(Long id, Long memberId);

    Optional<PaymentTransaction> findByPaymentCodeAndMemberId(String paymentCode, Long memberId);

    long countByStatus(PaymentStatus status);

    long countByStatusAndPaidAtGreaterThanEqualAndPaidAtLessThan(PaymentStatus status,
                                                                 Instant startInclusive,
                                                                 Instant endExclusive);

    @Query("""
            select coalesce(sum(payment.amount), 0)
            from PaymentTransaction payment
            where payment.status = :status
            """)
    Long sumAmountByStatus(@Param("status") PaymentStatus status);

    @Query("""
            select coalesce(sum(payment.amount), 0)
            from PaymentTransaction payment
            where payment.status = :status
              and payment.paidAt >= :startInclusive
              and payment.paidAt < :endExclusive
            """)
    Long sumAmountByStatusAndPaidAtBetween(@Param("status") PaymentStatus status,
                                           @Param("startInclusive") Instant startInclusive,
                                           @Param("endExclusive") Instant endExclusive);

    @Query(
            value = """
                    select
                        p.id as "paymentId",
                        p.payment_code as "receiptNumber",
                        p.payment_code as "paymentCode",
                        m.id as "memberId",
                        m.full_name as "memberName",
                        m.email as "memberEmail",
                        p.provider as "provider",
                        p.provider_transaction_id as "providerTransactionId",
                        p.provider_response_code as "providerResponseCode",
                        p.provider_transaction_status as "providerTransactionStatus",
                        p.purpose as "purpose",
                        p.target_type as "targetType",
                        p.target_id as "targetId",
                        coalesce(
                            b_ebook.title,
                            b_fine.title,
                            p.target_type || ' #' || p.target_id
                        ) as "itemTitle",
                        p.amount as "amount",
                        p.currency as "currency",
                        p.status as "status",
                        p.paid_at as "paidAt",
                        p.expired_at as "expiredAt",
                        p.created_at as "createdAt",
                        p.updated_at as "updatedAt"
                    from payment_transactions p
                    join members m on m.id = p.member_id
                    left join book_ebooks be
                        on p.target_type = 'BOOK_EBOOK'
                       and be.id = p.target_id
                    left join books b_ebook on b_ebook.id = be.book_id
                    left join borrow_records br
                        on p.target_type = 'BORROW_RECORD'
                       and br.id = p.target_id
                    left join book_copies bc on bc.id = br.book_copy_id
                    left join books b_fine on b_fine.id = bc.book_id
                    where p.member_id = :memberId
                      and p.status = 'SUCCESS'
                    order by p.paid_at desc nulls last, p.created_at desc
                    """,
            countQuery = """
                    select count(*)
                    from payment_transactions p
                    where p.member_id = :memberId
                      and p.status = 'SUCCESS'
                    """,
            nativeQuery = true
    )
    Page<PaymentReceiptRowProjection> findSuccessfulReceiptsByMember(@Param("memberId") Long memberId,
                                                                     Pageable pageable);

    @Query(
            value = """
                    select
                        p.id as "paymentId",
                        p.payment_code as "receiptNumber",
                        p.payment_code as "paymentCode",
                        m.id as "memberId",
                        m.full_name as "memberName",
                        m.email as "memberEmail",
                        p.provider as "provider",
                        p.provider_transaction_id as "providerTransactionId",
                        p.provider_response_code as "providerResponseCode",
                        p.provider_transaction_status as "providerTransactionStatus",
                        p.purpose as "purpose",
                        p.target_type as "targetType",
                        p.target_id as "targetId",
                        coalesce(
                            b_ebook.title,
                            b_fine.title,
                            p.target_type || ' #' || p.target_id
                        ) as "itemTitle",
                        p.amount as "amount",
                        p.currency as "currency",
                        p.status as "status",
                        p.paid_at as "paidAt",
                        p.expired_at as "expiredAt",
                        p.created_at as "createdAt",
                        p.updated_at as "updatedAt"
                    from payment_transactions p
                    join members m on m.id = p.member_id
                    left join book_ebooks be
                        on p.target_type = 'BOOK_EBOOK'
                       and be.id = p.target_id
                    left join books b_ebook on b_ebook.id = be.book_id
                    left join borrow_records br
                        on p.target_type = 'BORROW_RECORD'
                       and br.id = p.target_id
                    left join book_copies bc on bc.id = br.book_copy_id
                    left join books b_fine on b_fine.id = bc.book_id
                    where p.member_id = :memberId
                      and p.payment_code = :paymentCode
                      and p.status = 'SUCCESS'
                    """,
            nativeQuery = true
    )
    Optional<PaymentReceiptRowProjection> findSuccessfulReceiptByMemberAndCode(@Param("memberId") Long memberId,
                                                                              @Param("paymentCode") String paymentCode);

    @Query(
            value = """
                    select
                        p.id as "paymentId",
                        p.payment_code as "receiptNumber",
                        p.payment_code as "paymentCode",
                        m.id as "memberId",
                        m.full_name as "memberName",
                        m.email as "memberEmail",
                        p.provider as "provider",
                        p.provider_transaction_id as "providerTransactionId",
                        p.provider_response_code as "providerResponseCode",
                        p.provider_transaction_status as "providerTransactionStatus",
                        p.purpose as "purpose",
                        p.target_type as "targetType",
                        p.target_id as "targetId",
                        coalesce(
                            b_ebook.title,
                            b_fine.title,
                            p.target_type || ' #' || p.target_id
                        ) as "itemTitle",
                        p.amount as "amount",
                        p.currency as "currency",
                        p.status as "status",
                        p.paid_at as "paidAt",
                        p.expired_at as "expiredAt",
                        p.created_at as "createdAt",
                        p.updated_at as "updatedAt"
                    from payment_transactions p
                    join members m on m.id = p.member_id
                    left join book_ebooks be
                        on p.target_type = 'BOOK_EBOOK'
                       and be.id = p.target_id
                    left join books b_ebook on b_ebook.id = be.book_id
                    left join borrow_records br
                        on p.target_type = 'BORROW_RECORD'
                       and br.id = p.target_id
                    left join book_copies bc on bc.id = br.book_copy_id
                    left join books b_fine on b_fine.id = bc.book_id
                    where (:status is null or p.status = :status)
                      and (cast(:paidFrom as timestamp) is null or p.paid_at >= cast(:paidFrom as timestamp))
                      and (cast(:paidTo as timestamp) is null or p.paid_at <= cast(:paidTo as timestamp))
                      and (
                            :qLike is null
                            or lower(p.payment_code) like :qLike
                            or lower(coalesce(p.provider_transaction_id, '')) like :qLike
                            or lower(m.full_name) like :qLike
                            or lower(m.email) like :qLike
                            or lower(coalesce(b_ebook.title, '')) like :qLike
                            or lower(coalesce(b_fine.title, '')) like :qLike
                            or (
                                :numericId is not null
                                and (
                                    p.id = :numericId
                                    or m.id = :numericId
                                    or p.target_id = :numericId
                                )
                            )
                      )
                    order by p.created_at desc
                    """,
            countQuery = """
                    select count(*)
                    from payment_transactions p
                    join members m on m.id = p.member_id
                    left join book_ebooks be
                        on p.target_type = 'BOOK_EBOOK'
                       and be.id = p.target_id
                    left join books b_ebook on b_ebook.id = be.book_id
                    left join borrow_records br
                        on p.target_type = 'BORROW_RECORD'
                       and br.id = p.target_id
                    left join book_copies bc on bc.id = br.book_copy_id
                    left join books b_fine on b_fine.id = bc.book_id
                    where (:status is null or p.status = :status)
                      and (cast(:paidFrom as timestamp) is null or p.paid_at >= cast(:paidFrom as timestamp))
                      and (cast(:paidTo as timestamp) is null or p.paid_at <= cast(:paidTo as timestamp))
                      and (
                            :qLike is null
                            or lower(p.payment_code) like :qLike
                            or lower(coalesce(p.provider_transaction_id, '')) like :qLike
                            or lower(m.full_name) like :qLike
                            or lower(m.email) like :qLike
                            or lower(coalesce(b_ebook.title, '')) like :qLike
                            or lower(coalesce(b_fine.title, '')) like :qLike
                            or (
                                :numericId is not null
                                and (
                                    p.id = :numericId
                                    or m.id = :numericId
                                    or p.target_id = :numericId
                                )
                            )
                      )
                    """,
            nativeQuery = true
    )
    Page<PaymentReceiptRowProjection> searchAdminPayments(@Param("qLike") String qLike,
                                                          @Param("numericId") Long numericId,
                                                          @Param("status") String status,
                                                          @Param("paidFrom") Instant paidFrom,
                                                          @Param("paidTo") Instant paidTo,
                                                          Pageable pageable);

    @Query(
            value = """
                    select
                        p.id as "paymentId",
                        p.payment_code as "receiptNumber",
                        p.payment_code as "paymentCode",
                        m.id as "memberId",
                        m.full_name as "memberName",
                        m.email as "memberEmail",
                        p.provider as "provider",
                        p.provider_transaction_id as "providerTransactionId",
                        p.provider_response_code as "providerResponseCode",
                        p.provider_transaction_status as "providerTransactionStatus",
                        p.purpose as "purpose",
                        p.target_type as "targetType",
                        p.target_id as "targetId",
                        coalesce(
                            b_ebook.title,
                            b_fine.title,
                            p.target_type || ' #' || p.target_id
                        ) as "itemTitle",
                        p.amount as "amount",
                        p.currency as "currency",
                        p.status as "status",
                        p.paid_at as "paidAt",
                        p.expired_at as "expiredAt",
                        p.created_at as "createdAt",
                        p.updated_at as "updatedAt"
                    from payment_transactions p
                    join members m on m.id = p.member_id
                    left join book_ebooks be
                        on p.target_type = 'BOOK_EBOOK'
                       and be.id = p.target_id
                    left join books b_ebook on b_ebook.id = be.book_id
                    left join borrow_records br
                        on p.target_type = 'BORROW_RECORD'
                       and br.id = p.target_id
                    left join book_copies bc on bc.id = br.book_copy_id
                    left join books b_fine on b_fine.id = bc.book_id
                    where p.payment_code = :paymentCode
                    """,
            nativeQuery = true
    )
    Optional<PaymentReceiptRowProjection> findAdminPaymentByCode(@Param("paymentCode") String paymentCode);

    // IPN xử lý theo transaction lock để hai callback/retry không cùng chuyển trạng thái.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select payment
            from PaymentTransaction payment
            where payment.provider = :provider
              and payment.paymentCode = :paymentCode
            """)
    Optional<PaymentTransaction> findLockedByProviderAndPaymentCode(
            @Param("provider") PaymentProvider provider,
            @Param("paymentCode") String paymentCode
    );
}
