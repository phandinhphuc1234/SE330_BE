package com.vn.controller.fine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.controller.FineController;
import com.vn.dto.circulation.response.FineResponse;
import com.vn.entity.Member;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.exception.GlobalExceptionHandler;
import com.vn.security.MemberUserDetails;
import com.vn.service.FineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FineControllerTest {

    @Mock
    private FineService fineService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FineController(fineService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getMyFines_shouldPassCurrentMemberIdAndReturnPageMeta() throws Exception {
        authenticateAs(5L);
        PageRequest pageable = PageRequest.of(0, 10);
        when(fineService.getMyFines(5L, 0, 10))
                .thenReturn(new PageImpl<>(List.of(fineResponse()), pageable, 1));

        mockMvc.perform(get("/api/fines/my")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách tiền phạt thành công"))
                .andExpect(jsonPath("$.data[0].borrowId").value(100))
                .andExpect(jsonPath("$.data[0].fineAmount").value(15000))
                .andExpect(jsonPath("$.data[0].fineStatus").value("UNPAID"))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        verify(fineService).getMyFines(5L, 0, 10);
    }

    @Test
    void getMyFines_shouldReturnUnauthorized_whenPrincipalMissing() throws Exception {
        mockMvc.perform(get("/api/fines/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }

    private void authenticateAs(Long memberId) {
        Member member = Member.builder()
                .id(memberId)
                .email("member" + memberId + "@example.com")
                .password("hashed-password")
                .role(MemberRole.MEMBER)
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

    private FineResponse fineResponse() {
        return new FineResponse(
                100L,
                5L,
                10L,
                "Clean Code",
                50L,
                "BC-50",
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-15T10:00:00Z"),
                Instant.parse("2026-05-18T10:00:00Z"),
                new BigDecimal("15000"),
                Instant.parse("2026-05-18T10:00:00Z"),
                null,
                null,
                null,
                "UNPAID"
        );
    }
}
