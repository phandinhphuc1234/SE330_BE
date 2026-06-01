package com.vn.service.impl;

import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.enums.BorrowStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.StaffCirculationMapper;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.StaffLoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
public class StaffLoanServiceImpl implements StaffLoanService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final BorrowRecordRepository borrowRecordRepository;
    private final StaffCirculationMapper staffCirculationMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<StaffLoanResponse> searchLoans(String q,
                                               String status,
                                               Boolean openOnly,
                                               Boolean overdue,
                                               String dueFrom,
                                               String dueTo,
                                               int page,
                                               int size) {
        Instant now = Instant.now();
        Instant parsedDueFrom = parseBoundaryInstant(dueFrom, false);
        Instant parsedDueTo = parseBoundaryInstant(dueTo, true);
        validateDateRange(parsedDueFrom, parsedDueTo);

        return searchLoansInternal(
                null,
                q,
                parseBorrowStatus(status),
                openOnly,
                overdue,
                parsedDueFrom,
                parsedDueTo,
                now,
                buildLoanPageable(page, size, openOnly)
        );
    }

    public Page<StaffLoanResponse> searchMemberLoans(Long memberId,
                                                     String status,
                                                     Boolean openOnly,
                                                     Boolean overdue,
                                                     int page,
                                                     int size) {
        Instant now = Instant.now();
        Boolean effectiveOpenOnly = openOnly == null ? Boolean.TRUE : openOnly;

        return searchLoansInternal(
                memberId,
                null,
                parseBorrowStatus(status),
                effectiveOpenOnly,
                overdue,
                null,
                null,
                now,
                buildMemberLoanPageable(page, size, effectiveOpenOnly)
        );
    }

    private Page<StaffLoanResponse> searchLoansInternal(Long memberId,
                                                       String q,
                                                       BorrowStatus status,
                                                       Boolean openOnly,
                                                       Boolean overdue,
                                                       Instant dueFrom,
                                                       Instant dueTo,
                                                       Instant now,
                                                       Pageable pageable) {
        String normalizedQuery = normalizeLikeQuery(q);
        Long numericId = parseOptionalLong(q);

        return borrowRecordRepository
                .searchStaffLoans(
                        memberId,
                        normalizedQuery,
                        numericId,
                        status,
                        openOnly,
                        BorrowStatus.openStatuses(),
                        overdue,
                        BorrowStatus.OVERDUE,
                        BorrowStatus.BORROWED,
                        dueFrom,
                        dueTo,
                        now,
                        pageable
                )
                .map(borrow -> staffCirculationMapper.toStaffLoanResponse(borrow, now));
    }

    private BorrowStatus parseBorrowStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return BorrowStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private Instant parseBoundaryInstant(String rawValue, boolean endOfDayForDateOnly) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String value = rawValue.trim();
        try {
            if (!value.contains("T")) {
                LocalDate date = LocalDate.parse(value);
                if (endOfDayForDateOnly) {
                    return date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1);
                }
                return date.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private void validateDateRange(Instant dueFrom, Instant dueTo) {
        if (dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo)) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private Pageable buildLoanPageable(int page, int size, Boolean openOnly) {
        Sort sort = Boolean.TRUE.equals(openOnly)
                ? Sort.by(Sort.Direction.ASC, "dueDate").and(Sort.by(Sort.Direction.DESC, "borrowedAt"))
                : Sort.by(Sort.Direction.ASC, "dueDate").and(Sort.by(Sort.Direction.DESC, "borrowedAt"));
        return PageRequest.of(normalizePage(page), normalizeSize(size), sort);
    }

    private Pageable buildMemberLoanPageable(int page, int size, Boolean openOnly) {
        Sort sort = Boolean.TRUE.equals(openOnly)
                ? Sort.by(Sort.Direction.ASC, "dueDate")
                : Sort.by(Sort.Direction.DESC, "borrowedAt");
        return PageRequest.of(normalizePage(page), normalizeSize(size), sort);
    }

    private int normalizePage(int page) {
        return Math.max(page, 0);
    }

    private int normalizeSize(int size) {
        int requestedSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }

    private String normalizeLikeQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase() + "%";
    }

    private Long parseOptionalLong(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(q.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
