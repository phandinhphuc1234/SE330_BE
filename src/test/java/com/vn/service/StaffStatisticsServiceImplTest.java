package com.vn.service;

import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.StaffStatisticsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffStatisticsServiceImplTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    private StaffStatisticsService statisticsService;

    @BeforeEach
    void setUp() {
        statisticsService = new StaffStatisticsServiceImpl(borrowRecordRepository);
    }

    @Test
    void getBorrowStatistics_shouldFillMissingDaysAndUseBusinessTimezone() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 3);
        Instant fromInstant = Instant.parse("2026-05-31T17:00:00Z");
        Instant toInstant = Instant.parse("2026-06-03T17:00:00Z");

        when(borrowRecordRepository.countBorrowedPerDay(fromInstant, toInstant, "title", "Clean Code", "Vietnamese"))
                .thenReturn(List.of(
                        new Object[]{LocalDate.of(2026, 6, 1), 2L},
                        new Object[]{LocalDate.of(2026, 6, 3), 4L}
                ));
        when(borrowRecordRepository.countReturnedPerDay(fromInstant, toInstant, "title", "Clean Code", "Vietnamese"))
                .thenReturn(List.<Object[]>of(new Object[]{LocalDate.of(2026, 6, 2), 1L}));

        var response = statisticsService.getBorrowStatistics(from, to, " TITLE ", " Clean Code ", " Vietnamese ");

        assertThat(response.days()).hasSize(3);
        assertThat(response.days().get(0).date()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(response.days().get(0).borrowed()).isEqualTo(2L);
        assertThat(response.days().get(1).borrowed()).isZero();
        assertThat(response.days().get(1).returned()).isEqualTo(1L);
        assertThat(response.days().get(2).borrowed()).isEqualTo(4L);
        assertThat(response.totalBorrowed()).isEqualTo(6L);
        assertThat(response.totalReturned()).isEqualTo(1L);
        assertThat(response.netOnLoan()).isEqualTo(5L);
        assertThat(response.peakBorrowDate()).isEqualTo(LocalDate.of(2026, 6, 3));
        assertThat(response.peakBorrowCount()).isEqualTo(4L);
        verify(borrowRecordRepository).countBorrowedPerDay(fromInstant, toInstant, "title", "Clean Code", "Vietnamese");
        verify(borrowRecordRepository).countReturnedPerDay(fromInstant, toInstant, "title", "Clean Code", "Vietnamese");
    }

    @Test
    void getBorrowStatistics_shouldRejectInvalidDateRangeBeforeQueryingRepository() {
        assertThatThrownBy(() -> statisticsService.getBorrowStatistics(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 22), null, null, null
        )).isInstanceOfSatisfying(AppException.class, ex ->
                assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_DATE_RANGE.getCode()));

        verifyNoInteractions(borrowRecordRepository);
    }

    @Test
    void getBorrowStatistics_shouldRejectUnsupportedOrIncompleteFilterBeforeQueryingRepository() {
        assertThatThrownBy(() -> statisticsService.getBorrowStatistics(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), "author", "Martin", null
        )).isInstanceOfSatisfying(AppException.class, ex ->
                assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STATISTICS_FILTER.getCode()));

        assertThatThrownBy(() -> statisticsService.getBorrowStatistics(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), "title", null, null
        )).isInstanceOfSatisfying(AppException.class, ex ->
                assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STATISTICS_FILTER.getCode()));

        verifyNoInteractions(borrowRecordRepository);
    }
}
