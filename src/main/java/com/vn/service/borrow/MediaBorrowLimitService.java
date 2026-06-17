package com.vn.service.borrow;

import com.vn.entity.Member;
import com.vn.enums.BorrowStatus;
import com.vn.enums.EbookLoanStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MediaBorrowLimitService {

    private static final int DEFAULT_MAX_BORROW_LIMIT = 5;

    private final BorrowRecordRepository borrowRecordRepository;
    private final EbookLoanRepository ebookLoanRepository;
    private final MemberRepository memberRepository;

    // Dùng cho preview/validation nhẹ: đếm cả borrow_records và ebook_loans còn hiệu lực.
    public boolean hasReachedLimit(Member member) {
        return member != null && activeMediaLoanCount(member.getId(), Instant.now()) >= maxBorrowLimit(member);
    }

    public boolean hasUnpaidFines(Long memberId) {
        return borrowRecordRepository.countUnpaidFinesByMemberId(memberId) > 0;
    }

    // Dùng khi tạo payment ebook: chặn sớm nếu member đã đạt hạn mức tổng media hoặc còn nợ tiền phạt.
    public void assertCanBorrowMore(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        assertCanBorrowMore(member);
    }

    // Dùng khi thật sự tạo loan: lock member row để physical/ebook không vượt hạn mức do chạy đồng thời.
    public Member lockMember(Long memberId) {
        return memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public void assertCanBorrowMore(Member member) {
        if (hasUnpaidFines(member.getId())) {
            throw new AppException(ErrorCode.MEMBER_HAS_UNPAID_FINES);
        }
        if (hasReachedLimit(member)) {
            throw new AppException(ErrorCode.BORROW_LIMIT_EXCEEDED);
        }
    }

    // Tổng số media đang chiếm hạn mức của member: sách vật lý đang mượn + ebook loan ACTIVE còn hạn.
    public long activeMediaLoanCount(Long memberId, Instant now) {
        long physicalBorrows = borrowRecordRepository.countByMemberIdAndStatusIn(
                memberId,
                BorrowStatus.activeStatuses()
        );
        long ebookLoans = ebookLoanRepository.countByMemberIdAndStatusAndExpiredAtAfter(
                memberId,
                EbookLoanStatus.ACTIVE,
                now
        );
        return physicalBorrows + ebookLoans;
    }

    private int maxBorrowLimit(Member member) {
        return member.getMaxBorrowLimit() == null ? DEFAULT_MAX_BORROW_LIMIT : member.getMaxBorrowLimit();
    }
}
