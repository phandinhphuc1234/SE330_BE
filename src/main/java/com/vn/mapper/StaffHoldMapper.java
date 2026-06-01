package com.vn.mapper;

import com.vn.dto.staff.hold.response.StaffHoldResponse;
import com.vn.entity.BookCopy;
import com.vn.entity.Reservation;
import org.springframework.stereotype.Component;

@Component
public class StaffHoldMapper {

    public StaffHoldResponse toResponse(Reservation reservation) {
        BookCopy assignedCopy = reservation.getAssignedCopy();
        return new StaffHoldResponse(
                reservation.getId(),
                reservation.getMember().getId(),
                reservation.getMember().getFullName(),
                reservation.getMember().getEmail(),
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
}
