package com.vn.loan.service.borrow;

import com.vn.member.entity.Member;
import com.vn.loan.enums.BorrowStatus;
import com.vn.ebook.enums.EbookLoanStatus;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.ebook.repository.EbookLoanRepository;
import com.vn.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaBorrowLimitServiceTest {

    private BorrowRecordRepository borrowRecordRepository;
    private EbookLoanRepository ebookLoanRepository;
    private MemberRepository memberRepository;
    private MediaBorrowLimitService service;

    @BeforeEach
    void setUp() {
        borrowRecordRepository = mock(BorrowRecordRepository.class);
        ebookLoanRepository = mock(EbookLoanRepository.class);
        memberRepository = mock(MemberRepository.class);
        service = new MediaBorrowLimitService(
                borrowRecordRepository, ebookLoanRepository, memberRepository, com.vn.shared.testsupport.TestTime.CLOCK);
    }

    @Test
    void activeMediaLoanCountShouldIncludePhysicalBorrowsAndActiveEbookLoans() {
        Instant now = com.vn.shared.testsupport.TestTime.NOW;
        when(borrowRecordRepository.countByMemberIdAndStatusIn(10L, BorrowStatus.activeStatuses()))
                .thenReturn(3L);
        when(ebookLoanRepository.countByMemberIdAndStatusAndExpiredAtAfter(10L, EbookLoanStatus.ACTIVE, now))
                .thenReturn(2L);

        long count = service.activeMediaLoanCount(10L, now);

        assertThat(count).isEqualTo(5L);
    }

    @Test
    void assertCanBorrowMoreShouldRejectWhenTotalMediaLoansReachMemberLimit() {
        Member member = member();
        when(memberRepository.findById(10L)).thenReturn(Optional.of(member));
        when(borrowRecordRepository.countByMemberIdAndStatusIn(10L, BorrowStatus.activeStatuses()))
                .thenReturn(4L);
        when(ebookLoanRepository.countByMemberIdAndStatusAndExpiredAtAfter(
                eq(10L), eq(EbookLoanStatus.ACTIVE), any(Instant.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.assertCanBorrowMore(10L))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BORROW_LIMIT_EXCEEDED.getCode());
    }

    private Member member() {
        Member member = new Member();
        member.setId(10L);
        member.setMaxBorrowLimit(5);
        return member;
    }
}
