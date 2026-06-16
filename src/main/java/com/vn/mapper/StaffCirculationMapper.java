package com.vn.mapper;

import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.BorrowStatus;
import com.vn.service.impl.circulation.support.FineStatusResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class StaffCirculationMapper {

    private final FineStatusResolver fineStatusResolver;

    public StaffLoanResponse toStaffLoanResponse(BorrowRecord borrow, Instant now) {
        Member member = borrow.getMember();
        BookCopy copy = borrow.getBookCopy();
        Book book = copy.getBook();
        boolean overdue = isOverdue(borrow, now);

        return new StaffLoanResponse(
                borrow.getId(),
                member.getId(),
                member.getFullName(),
                member.getEmail(),
                book.getId(),
                book.getTitle(),
                copy.getId(),
                copy.getBarcode(),
                copy.getStatus().name(),
                borrow.getBorrowedAt(),
                borrow.getDueDate(),
                borrow.getReturnedAt(),
                borrow.getStatus().name(),
                borrow.getRenewCount(),
                borrow.getMaxRenewalsAtCheckout(),
                safeFineAmount(borrow),
                fineStatusResolver.resolve(borrow).name(),
                overdue,
                overdue ? daysOverdue(borrow.getDueDate(), now) : 0,
                "PHYSICAL",
                null,
                null,
                null,
                null
        );
    }

    private boolean isOverdue(BorrowRecord borrow, Instant now) {
        return borrow.getStatus() == BorrowStatus.OVERDUE
                || (borrow.getStatus() == BorrowStatus.BORROWED && borrow.getDueDate().isBefore(now));
    }

    private long daysOverdue(Instant dueDate, Instant now) {
        return Math.max(Duration.between(dueDate, now).toDays(), 0);
    }

    private BigDecimal safeFineAmount(BorrowRecord borrow) {
        return borrow.getFineAmount() == null ? BigDecimal.ZERO : borrow.getFineAmount();
    }
}
