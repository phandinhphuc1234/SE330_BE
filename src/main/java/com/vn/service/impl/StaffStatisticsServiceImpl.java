package com.vn.service.impl;

import com.vn.dto.staff.statistics.response.StaffBorrowStatisticsDayResponse;
import com.vn.dto.staff.statistics.response.StaffBorrowStatisticsResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.StaffStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StaffStatisticsServiceImpl implements StaffStatisticsService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_RANGE_DAYS = 21;
    private static final Set<String> SUPPORTED_FILTER_TYPES = Set.of("category", "isbn", "title");

    private final BorrowRecordRepository borrowRecordRepository;

    @Override
    @Transactional(readOnly = true)
    public StaffBorrowStatisticsResponse getBorrowStatistics(
            LocalDate from,
            LocalDate to,
            String filterType,
            String filterValue,
            String language) {

        validateDateRange(from, to);
        String normalizedFilterType = normalizeFilterType(filterType, filterValue);
        String normalizedFilterValue = normalizeOptional(filterValue);
        String normalizedLanguage = normalizeOptional(language);

        Instant fromInstant = from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        Map<LocalDate, Long> borrowedByDay = toCountByDay(borrowRecordRepository.countBorrowedPerDay(
                fromInstant, toInstant, normalizedFilterType, normalizedFilterValue, normalizedLanguage
        ));
        Map<LocalDate, Long> returnedByDay = toCountByDay(borrowRecordRepository.countReturnedPerDay(
                fromInstant, toInstant, normalizedFilterType, normalizedFilterValue, normalizedLanguage
        ));

        return buildResponse(from, to, borrowedByDay, returnedByDay);
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new AppException(ErrorCode.INVALID_DATE_RANGE);
        }
    }

    private String normalizeFilterType(String filterType, String filterValue) {
        String normalizedFilterType = normalizeOptional(filterType);
        if (normalizedFilterType == null) {
            if (normalizeOptional(filterValue) != null) {
                throw new AppException(ErrorCode.INVALID_STATISTICS_FILTER);
            }
            return null;
        }

        normalizedFilterType = normalizedFilterType.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FILTER_TYPES.contains(normalizedFilterType) || normalizeOptional(filterValue) == null) {
            throw new AppException(ErrorCode.INVALID_STATISTICS_FILTER);
        }
        return normalizedFilterType;
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Map<LocalDate, Long> toCountByDay(List<Object[]> rows) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            LocalDate date = row[0] instanceof LocalDate localDate
                    ? localDate
                    : LocalDate.parse(row[0].toString());
            counts.merge(date, ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
    }

    private StaffBorrowStatisticsResponse buildResponse(
            LocalDate from,
            LocalDate to,
            Map<LocalDate, Long> borrowedByDay,
            Map<LocalDate, Long> returnedByDay) {

        List<StaffBorrowStatisticsDayResponse> days = new ArrayList<>();
        long totalBorrowed = 0;
        long totalReturned = 0;
        LocalDate peakBorrowDate = null;
        long peakBorrowCount = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            long borrowed = borrowedByDay.getOrDefault(date, 0L);
            long returned = returnedByDay.getOrDefault(date, 0L);
            totalBorrowed += borrowed;
            totalReturned += returned;
            if (borrowed > peakBorrowCount) {
                peakBorrowCount = borrowed;
                peakBorrowDate = date;
            }
            days.add(new StaffBorrowStatisticsDayResponse(date, borrowed, returned));
        }

        return new StaffBorrowStatisticsResponse(
                days,
                totalBorrowed,
                totalReturned,
                totalBorrowed - totalReturned,
                peakBorrowDate,
                peakBorrowCount
        );
    }
}
