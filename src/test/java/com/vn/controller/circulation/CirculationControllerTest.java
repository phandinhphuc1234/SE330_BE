package com.vn.controller.circulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.controller.CirculationController;
import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.request.CheckoutRequest;
import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.dto.circulation.response.CheckoutPreviewResponse;
import com.vn.dto.circulation.response.CirculationBlockResponse;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.entity.Member;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.exception.GlobalExceptionHandler;
import com.vn.security.MemberUserDetails;
import com.vn.service.CirculationService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CirculationControllerTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Mock
    private CirculationService circulationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new CirculationController(circulationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setValidator(new SpringValidatorAdapter(validator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewCheckout_shouldReturnAllowedPreview_whenRequestValid() throws Exception {
        CheckoutRequest request = new CheckoutRequest(5L, "BC001");
        CheckoutPreviewResponse response = new CheckoutPreviewResponse(
                true,
                5L,
                10L,
                14,
                Instant.parse("2026-05-31T10:00:00Z"),
                List.of()
        );
        when(circulationService.previewCheckout(request)).thenReturn(response);

        mockMvc.perform(post("/api/staff/circulation/checkouts/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Kiểm tra điều kiện mượn sách thành công"))
                .andExpect(jsonPath("$.data.allowed").value(true))
                .andExpect(jsonPath("$.data.memberId").value(5))
                .andExpect(jsonPath("$.data.bookCopyId").value(10))
                .andExpect(jsonPath("$.data.loanPeriodDays").value(14))
                .andExpect(jsonPath("$.data.reasons").isArray());

        verify(circulationService).previewCheckout(request);
    }

    @Test
    void previewCheckout_shouldReturnBlockReasons_whenCheckoutNotAllowed() throws Exception {
        CheckoutRequest request = new CheckoutRequest(5L, "BC001");
        CheckoutPreviewResponse response = new CheckoutPreviewResponse(
                false,
                5L,
                10L,
                14,
                null,
                List.of(new CirculationBlockResponse(
                        ErrorCode.MEMBER_HAS_OVERDUE_ITEMS.getCode(),
                        ErrorCode.MEMBER_HAS_OVERDUE_ITEMS.getMessage()
                ))
        );
        when(circulationService.previewCheckout(request)).thenReturn(response);

        mockMvc.perform(post("/api/staff/circulation/checkouts/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.dueDate").doesNotExist())
                .andExpect(jsonPath("$.data.reasons[0].code").value(ErrorCode.MEMBER_HAS_OVERDUE_ITEMS.getCode()));
    }

    @Test
    void previewCheckout_shouldReturnValidationError_whenBarcodeBlank() throws Exception {
        CheckoutRequest request = new CheckoutRequest(5L, "");

        mockMvc.perform(post("/api/staff/circulation/checkouts/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()))
                .andExpect(jsonPath("$.data.itemBarcode").value("itemBarcode không được để trống"));
    }

    @Test
    void checkout_shouldPassActorIdAndIdempotencyKeyToService() throws Exception {
        authenticateAs(99L, MemberRole.LIBRARIAN);
        CheckoutRequest request = new CheckoutRequest(5L, "BC001");
        BorrowResponse response = borrowResponse();
        when(circulationService.checkout(99L, "checkout-key", request)).thenReturn(response);

        mockMvc.perform(post("/api/staff/circulation/checkouts")
                        .header(IDEMPOTENCY_KEY, "checkout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Mượn sách thành công"))
                .andExpect(jsonPath("$.data.borrowId").value(100))
                .andExpect(jsonPath("$.data.memberId").value(5))
                .andExpect(jsonPath("$.data.itemBarcode").value("BC001"))
                .andExpect(jsonPath("$.data.status").value("BORROWED"));

        verify(circulationService).checkout(99L, "checkout-key", request);
    }

    @Test
    void checkout_shouldReturnBusinessError_whenIdempotencyKeyMissing() throws Exception {
        authenticateAs(99L, MemberRole.LIBRARIAN);
        CheckoutRequest request = new CheckoutRequest(5L, "BC001");
        when(circulationService.checkout(99L, null, request))
                .thenThrow(new AppException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));

        mockMvc.perform(post("/api/staff/circulation/checkouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.getCode()));
    }

    @Test
    void checkin_shouldPassActorIdAndIdempotencyKeyToService() throws Exception {
        authenticateAs(99L, MemberRole.LIBRARIAN);
        CheckinRequest request = new CheckinRequest("BC001", "GOOD", "Returned at front desk");
        CheckinResponse response = new CheckinResponse(
                100L,
                5L,
                2L,
                "Clean Code",
                10L,
                "BC001",
                Instant.parse("2026-05-20T10:00:00Z"),
                0,
                BigDecimal.ZERO,
                "RETURNED",
                "AVAILABLE",
                null,
                null
        );
        when(circulationService.checkin(99L, "checkin-key", request)).thenReturn(response);

        mockMvc.perform(post("/api/staff/circulation/checkins")
                        .header(IDEMPOTENCY_KEY, "checkin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Trả sách thành công"))
                .andExpect(jsonPath("$.data.borrowId").value(100))
                .andExpect(jsonPath("$.data.overdueDays").value(0))
                .andExpect(jsonPath("$.data.bookCopyStatus").value("AVAILABLE"));

        verify(circulationService).checkin(99L, "checkin-key", request);
    }

    @Test
    void getMyActiveBorrows_shouldReturnPageMetaAndCurrentMemberBorrows() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        PageRequest pageable = PageRequest.of(0, 10);
        when(circulationService.getMyActiveBorrows(5L, 0, 10))
                .thenReturn(new PageImpl<>(List.of(borrowResponse()), pageable, 1));

        mockMvc.perform(get("/api/borrows/my")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].borrowId").value(100))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(10))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        verify(circulationService).getMyActiveBorrows(5L, 0, 10);
    }

    @Test
    void getMyBorrowHistory_shouldReturnMemberHistory() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        PageRequest pageable = PageRequest.of(0, 10);
        when(circulationService.getMyBorrowHistory(5L, 0, 10))
                .thenReturn(new PageImpl<>(List.of(borrowResponse()), pageable, 1));

        mockMvc.perform(get("/api/borrows/my/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy lịch sử mượn sách thành công"))
                .andExpect(jsonPath("$.data[0].borrowId").value(100));

        verify(circulationService).getMyBorrowHistory(5L, 0, 10);
    }

    @Test
    void renewMyBorrow_shouldPassCurrentMemberIdAndIdempotencyKeyToService() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        RenewBorrowRequest request = new RenewBorrowRequest(7);
        RenewBorrowResponse response = new RenewBorrowResponse(
                100L,
                Instant.parse("2026-05-31T10:00:00Z"),
                Instant.parse("2026-06-07T10:00:00Z"),
                1,
                1
        );
        when(circulationService.renewMyBorrow(5L, "renew-key", 100L, request)).thenReturn(response);

        mockMvc.perform(put("/api/borrows/{borrowId}/extend", 100L)
                        .header(IDEMPOTENCY_KEY, "renew-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Gia hạn lượt mượn thành công"))
                .andExpect(jsonPath("$.data.borrowId").value(100))
                .andExpect(jsonPath("$.data.renewCount").value(1));

        verify(circulationService).renewMyBorrow(5L, "renew-key", 100L, request);
    }

    @Test
    void staffRenewBorrow_shouldPassStaffActorIdAndIdempotencyKeyToService() throws Exception {
        authenticateAs(99L, MemberRole.ADMIN);
        RenewBorrowRequest request = new RenewBorrowRequest(null);
        RenewBorrowResponse response = new RenewBorrowResponse(
                100L,
                Instant.parse("2026-05-31T10:00:00Z"),
                Instant.parse("2026-06-07T10:00:00Z"),
                1,
                1
        );
        when(circulationService.staffRenewBorrow(99L, "staff-renew-key", 100L, request)).thenReturn(response);

        mockMvc.perform(put("/api/staff/borrows/{borrowId}/extend", 100L)
                        .header(IDEMPOTENCY_KEY, "staff-renew-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.borrowId").value(100));

        verify(circulationService).staffRenewBorrow(99L, "staff-renew-key", 100L, request);
    }

    @Test
    void renewMyBorrow_shouldReturnValidationError_whenRequestedDaysTooLarge() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        RenewBorrowRequest request = new RenewBorrowRequest(31);

        mockMvc.perform(put("/api/borrows/{borrowId}/extend", 100L)
                        .header(IDEMPOTENCY_KEY, "renew-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()))
                .andExpect(jsonPath("$.data.requestedDays").value("requestedDays tối đa là 30"));
    }

    private void authenticateAs(Long memberId, MemberRole role) {
        Member member = Member.builder()
                .id(memberId)
                .email("user" + memberId + "@example.com")
                .password("hashed-password")
                .role(role)
                .status(MemberStatus.ACTIVE)
                .build();
        MemberUserDetails principal = new MemberUserDetails(member);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private BorrowResponse borrowResponse() {
        return new BorrowResponse(
                100L,
                5L,
                2L,
                "Clean Code",
                10L,
                "BC001",
                Instant.parse("2026-05-17T10:00:00Z"),
                Instant.parse("2026-05-31T10:00:00Z"),
                null,
                "BORROWED",
                0,
                1,
                BigDecimal.ZERO
        );
    }
}
