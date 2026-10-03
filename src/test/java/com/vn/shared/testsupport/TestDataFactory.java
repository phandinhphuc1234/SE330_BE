package com.vn.shared.testsupport;

import com.vn.auth.entity.EmailVerification;
import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.loan.entity.Reservation;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.member.enums.MemberRole;
import com.vn.member.enums.MemberStatus;
import com.vn.loan.enums.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;

public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static Member activeMember(Long id) {
        return member(id, MemberStatus.ACTIVE);
    }

    public static Member pendingMember(Long id) {
        return member(id, MemberStatus.PENDING_VERIFICATION);
    }

    public static Member inactiveMember(Long id) {
        return member(id, MemberStatus.INACTIVE);
    }

    public static Member bannedMember(Long id) {
        return member(id, MemberStatus.BANNED);
    }

    public static EmailVerification activeEmailVerification(Long id, Member member, String token) {
        return EmailVerification.builder()
                .id(id)
                .member(member)
                .token(token)
                .expiresAt(TestTime.NOW.plusSeconds(3600))
                .isUsed(false)
                .createdAt(TestTime.NOW)
                .updatedAt(TestTime.NOW)
                .lastSentAt(TestTime.NOW)
                .build();
    }

    public static EmailVerification expiredEmailVerification(Long id, Member member, String token) {
        return EmailVerification.builder()
                .id(id)
                .member(member)
                .token(token)
                .expiresAt(TestTime.NOW.minusSeconds(60))
                .isUsed(false)
                .createdAt(TestTime.NOW.minusSeconds(3600))
                .updatedAt(TestTime.NOW.minusSeconds(3600))
                .lastSentAt(TestTime.NOW.minusSeconds(3600))
                .build();
    }

    public static Book book(Long id, int availableCopies) {
        return Book.builder()
                .id(id)
                .title("Clean Code")
                .isbn("ISBN-" + id)
                .totalCopies(3)
                .availableCopies(availableCopies)
                .language("en")
                .build();
    }

    public static BookCopy bookCopy(Long id, Book book, BookCopyStatus status) {
        return BookCopy.builder()
                .id(id)
                .book(book)
                .barcode("BC-" + id)
                .status(status)
                .build();
    }

    public static BorrowRecord borrowRecord(Long id, Member member, BookCopy copy, BorrowStatus status) {
        return BorrowRecord.builder()
                .id(id)
                .member(member)
                .bookCopy(copy)
                .borrowedAt(Instant.parse("2026-05-01T10:00:00Z"))
                .dueDate(Instant.parse("2026-05-15T10:00:00Z"))
                .status(status)
                .renewCount(0)
                .maxRenewalsAtCheckout(1)
                .fineAmount(BigDecimal.ZERO)
                .build();
    }

    public static Reservation reservation(Long id,
                                          Member member,
                                          Book book,
                                          ReservationStatus status,
                                          BookCopy assignedCopy) {
        return Reservation.builder()
                .id(id)
                .member(member)
                .book(book)
                .status(status)
                .queuePosition(1)
                .reservedAt(Instant.parse("2026-05-17T10:00:00Z"))
                .expiresAt(Instant.parse("2026-05-20T10:00:00Z"))
                .assignedCopy(assignedCopy)
                .build();
    }

    private static Member member(Long id, MemberStatus status) {
        return Member.builder()
                .id(id)
                .fullName("Member " + id)
                .email("member" + id + "@example.com")
                .password("hashed-password")
                .phone("0123456789")
                .role(MemberRole.MEMBER)
                .status(status)
                .maxBorrowLimit(5)
                .createdAt(TestTime.NOW)
                .updatedAt(TestTime.NOW)
                .build();
    }
}

