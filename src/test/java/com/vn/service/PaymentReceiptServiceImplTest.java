package com.vn.service;

import com.vn.repository.PaymentTransactionRepository;
import com.vn.service.impl.PaymentReceiptServiceImpl;
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
class PaymentReceiptServiceImplTest {

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    private PaymentReceiptService paymentReceiptService;

    @BeforeEach
    void setUp() {
        paymentReceiptService = new PaymentReceiptServiceImpl(paymentTransactionRepository);
    }

    @Test
    void searchAdminPayments_shouldInterpretDateOnlyFiltersInBusinessTimezone() {
        when(paymentTransactionRepository.searchAdminPayments(
                isNull(), isNull(), isNull(), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(Page.empty());

        paymentReceiptService.searchAdminPayments(null, null, "2026-06-01", "2026-06-01", 0, 20);

        ArgumentCaptor<Instant> paidFrom = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> paidTo = ArgumentCaptor.forClass(Instant.class);
        verify(paymentTransactionRepository).searchAdminPayments(
                isNull(), isNull(), isNull(), paidFrom.capture(), paidTo.capture(), any(Pageable.class)
        );

        assertThat(paidFrom.getValue()).isEqualTo(Instant.parse("2026-05-31T17:00:00Z"));
        assertThat(paidTo.getValue()).isEqualTo(Instant.parse("2026-06-01T16:59:59.999999999Z"));
    }
}
