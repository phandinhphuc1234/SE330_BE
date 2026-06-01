package com.vn.service.impl;

import com.vn.dto.staff.hold.response.StaffHoldResponse;
import com.vn.entity.Reservation;
import com.vn.enums.ReservationStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.StaffHoldMapper;
import com.vn.repository.ReservationRepository;
import com.vn.service.StaffHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StaffHoldServiceImpl implements StaffHoldService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReservationRepository reservationRepository;
    private final StaffHoldMapper staffHoldMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<StaffHoldResponse> searchHolds(String status, int page, int size) {
        ReservationStatus parsedStatus = parseStatus(status);
        Pageable pageable = buildPageable(page, size);

        Page<Reservation> holds = parsedStatus == null
                ? reservationRepository.findAllByOrderByReservedAtDesc(pageable)
                : reservationRepository.findByStatusOrderByReservedAtDesc(parsedStatus, pageable);

        return holds.map(staffHoldMapper::toResponse);
    }

    private ReservationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        try {
            return ReservationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private Pageable buildPageable(int page, int size) {
        int requestedSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        return PageRequest.of(Math.max(page, 0), Math.min(requestedSize, MAX_PAGE_SIZE));
    }
}
