package com.vn.service;

import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.service.impl.CirculationServiceImpl;
import com.vn.service.impl.circulation.usecase.CheckinUseCase;
import com.vn.service.impl.circulation.usecase.CheckoutUseCase;
import com.vn.service.impl.circulation.usecase.RenewalUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CirculationServiceImplTest {

    @Mock
    private CheckoutUseCase checkoutUseCase;
    @Mock
    private CheckinUseCase checkinUseCase;
    @Mock
    private RenewalUseCase renewalUseCase;
    @Mock
    private StaffLoanService staffLoanService;
    @Mock
    private IdempotencyService idempotencyService;

    private CirculationService circulationService;

    @BeforeEach
    void setUp() {
        circulationService = new CirculationServiceImpl(
                checkoutUseCase,
                checkinUseCase,
                renewalUseCase,
                staffLoanService,
                idempotencyService
        );
    }

    @Test
    void renewMyBorrow_shouldScopeIdempotencyToConcreteBorrowId() {
        RenewBorrowRequest request = new RenewBorrowRequest(7);
        RenewBorrowResponse response = new RenewBorrowResponse(100L, null, null, 1, 3);
        when(idempotencyService.execute(
                eq(5L), eq("PUT"), eq("/api/borrows/100/extend"), eq("renew-key"),
                eq(request), eq(RenewBorrowResponse.class), any()
        )).thenReturn(response);

        circulationService.renewMyBorrow(5L, "renew-key", 100L, request);

        verify(idempotencyService).execute(
                eq(5L), eq("PUT"), eq("/api/borrows/100/extend"), eq("renew-key"),
                eq(request), eq(RenewBorrowResponse.class), any()
        );
    }

    @Test
    void staffRenewBorrow_shouldScopeIdempotencyToConcreteBorrowId() {
        RenewBorrowRequest request = new RenewBorrowRequest(7);
        RenewBorrowResponse response = new RenewBorrowResponse(101L, null, null, 1, 3);
        when(idempotencyService.execute(
                eq(99L), eq("PUT"), eq("/api/staff/borrows/101/extend"), eq("staff-renew-key"),
                eq(request), eq(RenewBorrowResponse.class), any()
        )).thenReturn(response);

        circulationService.staffRenewBorrow(99L, "staff-renew-key", 101L, request);

        verify(idempotencyService).execute(
                eq(99L), eq("PUT"), eq("/api/staff/borrows/101/extend"), eq("staff-renew-key"),
                eq(request), eq(RenewBorrowResponse.class), any()
        );
    }
}
