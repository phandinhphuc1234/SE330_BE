package com.vn.service.impl;

import com.vn.dto.ebook.request.BorrowEbookRequest;
import com.vn.dto.ebook.response.EbookLoanResponse;
import com.vn.entity.Book;
import com.vn.entity.EbookLoan;
import com.vn.entity.Member;
import com.vn.enums.EbookLoanStatus;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.repository.MemberRepository;
import com.vn.service.EbookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EbookServiceImpl implements EbookService {

    // Số ngày mượn mặc định và mỗi lần gia hạn
    private static final int LOAN_DURATION_DAYS = 14;

    // Số ebook tối đa được mượn đồng thời
    @Value("${app.ebook.max-concurrent-loans:3}")
    private int maxConcurrentLoans;

    // Số lần gia hạn tối đa mỗi lượt mượn
    @Value("${app.ebook.max-renewals:1}")
    private int maxRenewals;

    private final EbookLoanRepository ebookLoanRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;

    // ──────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────

    @Override
    @Transactional
    public EbookLoanResponse borrowEbook(Long memberId, BorrowEbookRequest request) {
        Member member = getMemberOrThrow(memberId);
        validateMemberActive(member);

        Book book = getBookOrThrow(request.getBookId());

        // Kiểm tra book có ebook không
        if (!book.hasEbook()) {
            throw new AppException(ErrorCode.BOOK_HAS_NO_EBOOK);
        }

        // Kiểm tra đang mượn rồi chưa
        ebookLoanRepository
                .findByMemberIdAndBookIdAndStatus(memberId, book.getId(), EbookLoanStatus.ACTIVE)
                .ifPresent(existing -> { throw new AppException(ErrorCode.EBOOK_ALREADY_BORROWED); });

        // Kiểm tra giới hạn concurrent
        long activeCount = ebookLoanRepository.countByMemberIdAndStatus(memberId, EbookLoanStatus.ACTIVE);
        if (activeCount >= maxConcurrentLoans) {
            throw new AppException(ErrorCode.EBOOK_LOAN_LIMIT_EXCEEDED);
        }

        Instant now = Instant.now();
        EbookLoan loan = EbookLoan.builder()
                .member(member)
                .book(book)
                .status(EbookLoanStatus.ACTIVE)
                .borrowedAt(now)
                .expiresAt(now.plus(LOAN_DURATION_DAYS, ChronoUnit.DAYS))
                .renewCount(0)
                .maxRenewals(maxRenewals)
                .build();

        loan = ebookLoanRepository.save(loan);
        log.info("Member {} borrowed ebook for book {} (loanId={})", memberId, book.getId(), loan.getId());
        return toResponse(loan, true);
    }

    @Override
    @Transactional
    public EbookLoanResponse returnEbook(Long memberId, Long loanId) {
        EbookLoan loan = getActiveLoanOrThrow(memberId, loanId);
        loan.setStatus(EbookLoanStatus.RETURNED);
        loan.setReturnedAt(Instant.now());
        ebookLoanRepository.save(loan);
        log.info("Member {} returned ebook loanId={}", memberId, loanId);
        return toResponse(loan, false);
    }

    @Override
    @Transactional
    public EbookLoanResponse renewEbook(Long memberId, Long loanId) {
        EbookLoan loan = getActiveLoanOrThrow(memberId, loanId);

        if (loan.getRenewCount() >= loan.getMaxRenewals()) {
            throw new AppException(ErrorCode.EBOOK_LOAN_NOT_RENEWABLE);
        }

        loan.setExpiresAt(loan.getExpiresAt().plus(LOAN_DURATION_DAYS, ChronoUnit.DAYS));
        loan.setRenewCount(loan.getRenewCount() + 1);
        ebookLoanRepository.save(loan);
        log.info("Member {} renewed ebook loanId={}, renewCount={}", memberId, loanId, loan.getRenewCount());
        return toResponse(loan, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EbookLoanResponse> getMyEbookLoans(Long memberId, Pageable pageable) {
        // Chỉ ACTIVE
        return ebookLoanRepository
                .findByMemberIdOrderByCreatedAtDesc(memberId, pageable)
                .map(loan -> toResponse(loan, loan.getStatus() == EbookLoanStatus.ACTIVE));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EbookLoanResponse> getMyEbookHistory(Long memberId, Pageable pageable) {
        return ebookLoanRepository
                .findByMemberIdOrderByCreatedAtDesc(memberId, pageable)
                .map(loan -> toResponse(loan, false)); // history: không expose ebookReadUrl
    }

    // ──────────────────────────────────────────────
    // SCHEDULER
    // ──────────────────────────────────────────────

    /**
     * Chạy mỗi giờ để expire các ebook loan đã hết hạn.
     * Thay đổi cron theo nhu cầu trong application.yml:
     *   app.ebook.expire-cron: "0 0 * * * *"
     */
    @Override
    @Transactional
    @Scheduled(cron = "${app.ebook.expire-cron:0 0 * * * *}")
    public int expireOverdueLoans() {
        int count = ebookLoanRepository.bulkExpireLoans(Instant.now());
        if (count > 0) {
            log.info("EbookExpireJob: expired {} ebook loans", count);
        }
        return count;
    }

    // ──────────────────────────────────────────────
    // PRIVATE HELPERS
    // ──────────────────────────────────────────────

    private EbookLoan getActiveLoanOrThrow(Long memberId, Long loanId) {
        EbookLoan loan = ebookLoanRepository.findById(loanId)
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_LOAN_NOT_FOUND));
        // Kiểm tra ownership
        if (!loan.getMember().getId().equals(memberId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (loan.getStatus() != EbookLoanStatus.ACTIVE) {
            throw new AppException(ErrorCode.EBOOK_LOAN_NOT_ACTIVE);
        }
        return loan;
    }

    private Member getMemberOrThrow(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void validateMemberActive(Member member) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.MEMBER_NOT_ACTIVE);
        }
        if (member.getMembershipExpiresAt() != null
                && member.getMembershipExpiresAt().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.MEMBERSHIP_EXPIRED);
        }
    }

    private Book getBookOrThrow(Long bookId) {
        return bookRepository.findById(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * Map entity → DTO.
     * @param exposeReadUrl true khi loan đang ACTIVE và client được phép đọc
     */
    private EbookLoanResponse toResponse(EbookLoan loan, boolean exposeReadUrl) {
        boolean canRenew = loan.getStatus() == EbookLoanStatus.ACTIVE
                && loan.getRenewCount() < loan.getMaxRenewals();

        return EbookLoanResponse.builder()
                .loanId(loan.getId())
                .bookId(loan.getBook().getId())
                .bookTitle(loan.getBook().getTitle())
                .isbn(loan.getBook().getIsbn())
                .status(loan.getStatus().name())
                .borrowedAt(loan.getBorrowedAt())
                .expiresAt(loan.getExpiresAt())
                .returnedAt(loan.getReturnedAt())
                .renewCount(loan.getRenewCount())
                .maxRenewals(loan.getMaxRenewals())
                .canRenew(canRenew)
                // Chỉ trả URL khi ACTIVE — bảo vệ nội dung
                .ebookReadUrl(exposeReadUrl ? loan.getBook().getEbookUrl() : null)
                .build();
    }
}
