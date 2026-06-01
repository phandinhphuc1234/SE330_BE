package com.vn.controller.hold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.controller.HoldController;
import com.vn.dto.circulation.request.CreateHoldRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.HoldResponse;
import com.vn.entity.Member;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import com.vn.enums.ReservationStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.exception.GlobalExceptionHandler;
import com.vn.security.MemberUserDetails;
import com.vn.service.HoldService;
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
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HoldControllerTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Mock
    private HoldService holdService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new HoldController(holdService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setValidator(new SpringValidatorAdapter(validator))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createHold_shouldPassCurrentMemberIdToService() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        CreateHoldRequest request = new CreateHoldRequest(10L);
        HoldResponse response = holdResponse(ReservationStatus.WAITING);
        when(holdService.createHold(5L, request)).thenReturn(response);

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Đặt giữ chỗ sách thành công"))
                .andExpect(jsonPath("$.data.holdId").value(700))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.queuePosition").value(2));

        verify(holdService).createHold(5L, request);
    }

    @Test
    void createHold_shouldReturnValidationError_whenBookIdMissing() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        CreateHoldRequest request = new CreateHoldRequest(null);

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()))
                .andExpect(jsonPath("$.data.bookId").value("bookId không được để trống"));
    }

    @Test
    void getMyHolds_shouldParseStatusAndReturnPageMeta() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        PageRequest pageable = PageRequest.of(0, 10);
        when(holdService.getMyHolds(5L, ReservationStatus.WAITING, 0, 10))
                .thenReturn(new PageImpl<>(List.of(holdResponse(ReservationStatus.WAITING)), pageable, 1));

        mockMvc.perform(get("/api/holds/my")
                        .param("status", "WAITING")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].holdId").value(700))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        verify(holdService).getMyHolds(5L, ReservationStatus.WAITING, 0, 10);
    }

    @Test
    void getMyHolds_shouldReturnBadRequest_whenStatusInvalid() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);

        mockMvc.perform(get("/api/holds/my")
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    void cancelHold_shouldPassStaffFlagFalseForMember() throws Exception {
        authenticateAs(5L, MemberRole.MEMBER);
        HoldResponse response = holdResponse(ReservationStatus.CANCELLED);
        when(holdService.cancelHold(5L, false, 700L)).thenReturn(response);

        mockMvc.perform(delete("/api/holds/{holdId}", 700L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hủy giữ chỗ thành công"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(holdService).cancelHold(5L, false, 700L);
    }

    @Test
    void cancelHold_shouldPassStaffFlagTrueForLibrarian() throws Exception {
        authenticateAs(99L, MemberRole.LIBRARIAN);
        HoldResponse response = holdResponse(ReservationStatus.CANCELLED);
        when(holdService.cancelHold(99L, true, 700L)).thenReturn(response);

        mockMvc.perform(delete("/api/holds/{holdId}", 700L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(holdService).cancelHold(99L, true, 700L);
    }

    @Test
    void checkoutHold_shouldPassActorIdAndIdempotencyKeyToService() throws Exception {
        authenticateAs(99L, MemberRole.ADMIN);
        BorrowResponse response = borrowResponse();
        when(holdService.checkoutHold(99L, "hold-checkout-key", 700L)).thenReturn(response);

        mockMvc.perform(post("/api/staff/holds/{holdId}/checkout", 700L)
                        .header(IDEMPOTENCY_KEY, "hold-checkout-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Checkout giữ chỗ thành công"))
                .andExpect(jsonPath("$.data.borrowId").value(100))
                .andExpect(jsonPath("$.data.status").value("BORROWED"));

        verify(holdService).checkoutHold(99L, "hold-checkout-key", 700L);
    }

    @Test
    void checkoutHold_shouldReturnBusinessError_whenIdempotencyKeyMissing() throws Exception {
        authenticateAs(99L, MemberRole.LIBRARIAN);
        when(holdService.checkoutHold(99L, null, 700L))
                .thenThrow(new AppException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));

        mockMvc.perform(post("/api/staff/holds/{holdId}/checkout", 700L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.getCode()));
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

    private HoldResponse holdResponse(ReservationStatus status) {
        return new HoldResponse(
                700L,
                5L,
                10L,
                "Clean Code",
                status.name(),
                2,
                null,
                null,
                Instant.parse("2026-05-17T10:00:00Z"),
                null,
                Instant.parse("2026-05-20T10:00:00Z")
        );
    }

    private BorrowResponse borrowResponse() {
        return new BorrowResponse(
                100L,
                5L,
                10L,
                "Clean Code",
                50L,
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
