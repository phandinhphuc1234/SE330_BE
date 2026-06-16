package com.vn.service.borrow;

import com.vn.entity.Member;
import com.vn.enums.BorrowStatus;
import com.vn.enums.EbookLoanStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.repository.MemberRepository;
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
        service = new MediaBorrowLimitService(borrowRecordRepository, ebookLoanRepository, memberRepository);
    }

    @Test
    void activeMediaLoanCountShouldIncludePhysicalBorrowsAndActiveEbookLoans() {
        Instant now = Instant.now();
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
