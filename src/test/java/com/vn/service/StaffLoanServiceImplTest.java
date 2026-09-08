package com.vn.service;

import com.vn.repository.EbookLoanRepository;
import com.vn.service.impl.StaffLoanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffLoanServiceImplTest {

    @Mock
    private EbookLoanRepository ebookLoanRepository;

    private StaffLoanService staffLoanService;

    @BeforeEach
    void setUp() {
        staffLoanService = new StaffLoanServiceImpl(ebookLoanRepository);
    }

    @Test
    void searchLoans_shouldInterpretDateOnlyFiltersInBusinessTimezone() {
        when(ebookLoanRepository.searchStaffLoansIncludingEbooks(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(Instant.class), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(Page.empty());

        staffLoanService.searchLoans(null, null, null, null, "2026-06-01", "2026-06-01", 0, 20);

        ArgumentCaptor<Instant> dueFrom = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> dueTo = ArgumentCaptor.forClass(Instant.class);
        verify(ebookLoanRepository).searchStaffLoansIncludingEbooks(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                dueFrom.capture(), dueTo.capture(), any(Instant.class), any(Pageable.class)
        );

        assertThat(dueFrom.getValue()).isEqualTo(Instant.parse("2026-05-31T17:00:00Z"));
        assertThat(dueTo.getValue()).isEqualTo(Instant.parse("2026-06-01T16:59:59.999999999Z"));
    }
}
