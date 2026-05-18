package com.vn.mapper;

import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.dto.circulation.response.HoldResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Reservation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class CirculationMapper {

    public BorrowResponse toBorrowResponse(BorrowRecord borrow) {
        BookCopy copy = borrow.getBookCopy();
        Book book = copy.getBook();
        return new BorrowResponse(
                borrow.getId(),
                borrow.getMember().getId(),
                book.getId(),
                book.getTitle(),
                copy.getId(),
                copy.getBarcode(),
                borrow.getBorrowedAt(),
                borrow.getDueDate(),
                borrow.getReturnedAt(),
                borrow.getStatus().name(),
                borrow.getRenewCount(),
                borrow.getMaxRenewalsAtCheckout(),
                borrow.getFineAmount()
        );
    }

    public CheckinResponse toCheckinResponse(BorrowRecord borrow, long overdueDays) {
        return toCheckinResponse(borrow, overdueDays, null);
    }

    public CheckinResponse toCheckinResponse(BorrowRecord borrow, long overdueDays, Reservation nextHold) {
        BookCopy copy = borrow.getBookCopy();
        Book book = copy.getBook();
        return new CheckinResponse(
                borrow.getId(),
                borrow.getMember().getId(),
                book.getId(),
                book.getTitle(),
                copy.getId(),
                copy.getBarcode(),
                borrow.getReturnedAt(),
                overdueDays,
                safeFineAmount(borrow),
                borrow.getStatus().name(),
                copy.getStatus().name(),
                nextHold == null ? null : nextHold.getId(),
                nextHold == null ? null : nextHold.getStatus().name()
        );
    }

    public HoldResponse toHoldResponse(Reservation reservation) {
        BookCopy assignedCopy = reservation.getAssignedCopy();
        return new HoldResponse(
                reservation.getId(),
                reservation.getMember().getId(),
                reservation.getBook().getId(),
                reservation.getBook().getTitle(),
                reservation.getStatus().name(),
                reservation.getQueuePosition(),
                assignedCopy == null ? null : assignedCopy.getId(),
                assignedCopy == null ? null : assignedCopy.getBarcode(),
                reservation.getReservedAt(),
                reservation.getNotifiedAt(),
                reservation.getExpiresAt()
        );
    }

    private BigDecimal safeFineAmount(BorrowRecord borrow) {
        return borrow.getFineAmount() == null ? BigDecimal.ZERO : borrow.getFineAmount();
    }
}
