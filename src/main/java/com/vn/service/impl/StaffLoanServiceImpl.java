package com.vn.service.impl;

import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.enums.BorrowStatus;
import com.vn.enums.EbookLoanStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.EbookLoanRepository;
import com.vn.repository.projection.StaffLoanRowProjection;
import com.vn.service.StaffLoanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class StaffLoanServiceImpl implements StaffLoanService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final EbookLoanRepository ebookLoanRepository;

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
                parseLoanStatus(status),
                openOnly,
                overdue,
                parsedDueFrom,
                parsedDueTo,
                now,
                buildLoanPageable(page, size, openOnly)
        );
    }

    @Override
    @Transactional(readOnly = true)
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
                parseLoanStatus(status),
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
                                                       String status,
                                                       Boolean openOnly,
                                                       Boolean overdue,
                                                       Instant dueFrom,
                                                       Instant dueTo,
                                                       Instant now,
                                                       Pageable pageable) {
        String normalizedQuery = normalizeLikeQuery(q);
        Long numericId = parseOptionalLong(q);

        return ebookLoanRepository
                .searchStaffLoansIncludingEbooks(
                        memberId,
                        normalizedQuery,
                        numericId,
                        status,
                        openOnly,
                        overdue,
                        dueFrom,
                        dueTo,
                        now,
                        pageable
                )
                .map(this::toStaffLoanResponse);
    }

    private String parseLoanStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        try {
            BorrowStatus.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException e) {
            try {
                EbookLoanStatus.valueOf(normalized);
                return normalized;
            } catch (IllegalArgumentException ignored) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
        }
    }

    private StaffLoanResponse toStaffLoanResponse(StaffLoanRowProjection row) {
        return new StaffLoanResponse(
                row.getBorrowId(),
                row.getMemberId(),
                row.getMemberName(),
                row.getMemberEmail(),
                row.getBookId(),
                row.getBookTitle(),
                row.getBookCopyId(),
                row.getItemBarcode(),
                row.getCopyStatus(),
                row.getBorrowedAt(),
                row.getDueDate(),
                row.getReturnedAt(),
                row.getStatus(),
                row.getRenewCount(),
                row.getMaxRenewals(),
                row.getFineAmount() == null ? BigDecimal.ZERO : row.getFineAmount(),
                row.getFineStatus(),
                Boolean.TRUE.equals(row.getOverdue()),
                row.getDaysOverdue() == null ? 0L : row.getDaysOverdue(),
                row.getLoanType(),
                row.getEbookLoanId(),
                row.getBookEbookId(),
                row.getPaymentId(),
                row.getExpiredAt()
        );
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
        return PageRequest.of(normalizePage(page), normalizeSize(size), Sort.unsorted());
    }

    private Pageable buildMemberLoanPageable(int page, int size, Boolean openOnly) {
        return PageRequest.of(normalizePage(page), normalizeSize(size), Sort.unsorted());
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
