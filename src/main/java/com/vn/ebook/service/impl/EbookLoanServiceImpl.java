package com.vn.ebook.service.impl;

import com.vn.ebook.dto.response.EbookLoanResponse;
import com.vn.ebook.entity.BookEbook;
import com.vn.ebook.entity.EbookLoan;
import com.vn.member.entity.Member;
import com.vn.ebook.enums.BookEbookStatus;
import com.vn.ebook.enums.EbookAccessType;
import com.vn.ebook.enums.EbookLoanStatus;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.ebook.mapper.EbookMapper;
import com.vn.ebook.repository.BookEbookRepository;
import com.vn.ebook.repository.EbookLoanRepository;
import com.vn.ebook.service.EbookLoanService;
import com.vn.loan.service.borrow.MediaBorrowLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class EbookLoanServiceImpl implements EbookLoanService {

    private static final int MAX_PAGE_SIZE = 50;

    private final EbookLoanRepository ebookLoanRepository;
    private final BookEbookRepository bookEbookRepository;
    private final EbookMapper ebookMapper;
    private final MediaBorrowLimitService mediaBorrowLimitService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Page<EbookLoanResponse> getMyEbookLoans(Long memberId, boolean history, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        if (history) {
            // Lấy tất cả lịch sử mượn ebook.
            return ebookLoanRepository.findByMemberIdOrderByBorrowedAtDesc(memberId, pageable)
                    .map(ebookMapper::toEbookLoanResponse);
        } else {
            // Chỉ lấy các ebook đang ACTIVE (còn quyền đọc).
            return ebookLoanRepository.findByMemberIdAndStatusOrderByBorrowedAtDesc(memberId, EbookLoanStatus.ACTIVE, pageable)
                    .map(ebookMapper::toEbookLoanResponse);
        }
    }

    @Override
    @Transactional
    public EbookLoanResponse borrowFreeEbook(Long memberId, Long bookId) {
        Instant now = clock.instant();

        // 1. Resolve ebook ACTIVE mới nhất của book.
        BookEbook ebook = bookEbookRepository.findFirstByBookIdAndStatusOrderByIdDesc(bookId, BookEbookStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));

        if (ebook.getAccessType() != EbookAccessType.FREE) {
            throw new AppException(ErrorCode.EBOOK_REQUIRES_PAYMENT);
        }

        // 2. Lock member và check hạn mức media.
        Member lockedMember = mediaBorrowLimitService.lockMember(memberId);
        mediaBorrowLimitService.assertCanBorrowMore(lockedMember);

        // 3. Lock ebook và check license.
        BookEbook lockedEbook = bookEbookRepository.findLockedById(ebook.getId())
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));

        if (lockedEbook.getStatus() != BookEbookStatus.ACTIVE) {
            throw new AppException(ErrorCode.EBOOK_NOT_AVAILABLE);
        }

        // 4. Check nếu đã có loan ACTIVE rồi thì trả về loan đó (idempotent).
        return ebookLoanRepository.findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
                memberId, bookId, lockedEbook.getId(), EbookLoanStatus.ACTIVE, now)
                .map(ebookMapper::toEbookLoanResponse)
                .orElseGet(() -> createNewFreeLoan(lockedMember, lockedEbook, now));
    }

    private EbookLoanResponse createNewFreeLoan(Member member, BookEbook ebook, Instant now) {
        long activeLoanCount = ebookLoanRepository.countByBookEbookIdAndStatusAndExpiredAtAfter(
                ebook.getId(), EbookLoanStatus.ACTIVE, now);

        if (activeLoanCount >= ebook.getMaxConcurrentLoans()) {
            throw new AppException(ErrorCode.EBOOK_LICENSE_NOT_AVAILABLE);
        }

        EbookLoan loan = new EbookLoan();
        loan.setMemberId(member.getId());
        loan.setBookId(ebook.getBook().getId());
        loan.setBookEbookId(ebook.getId());
        loan.setStatus(EbookLoanStatus.ACTIVE);
        loan.setBorrowedAt(now);
        loan.setExpiredAt(now.plus(ebook.getLoanDurationDays(), ChronoUnit.DAYS));

        return ebookMapper.toEbookLoanResponse(ebookLoanRepository.save(loan));
    }
}
