package com.vn.ebook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.auth.security.MemberUserDetails;
import com.vn.ebook.dto.request.EbookSemanticSearchRequest;
import com.vn.ebook.dto.request.EbookAskRequest;
import com.vn.ebook.dto.response.EbookAnswerCitationResponse;
import com.vn.ebook.dto.response.EbookAnswerResponse;
import com.vn.ebook.dto.response.EbookSemanticSearchCitationResponse;
import com.vn.ebook.dto.response.EbookSemanticSearchHitResponse;
import com.vn.ebook.dto.response.EbookSemanticSearchResponse;
import com.vn.ebook.service.EbookReaderSessionService;
import com.vn.ebook.service.EbookQuestionAnswerService;
import com.vn.ebook.service.EbookSemanticSearchService;
import com.vn.member.entity.Member;
import com.vn.member.enums.MemberRole;
import com.vn.member.enums.MemberStatus;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.exception.GlobalExceptionHandler;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EbookReaderControllerTest {

    @Mock
    private EbookReaderSessionService ebookReaderSessionService;

    @Mock
    private EbookSemanticSearchService ebookSemanticSearchService;

    @Mock
    private EbookQuestionAnswerService ebookQuestionAnswerService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EbookReaderController(
                        ebookReaderSessionService,
                        ebookSemanticSearchService,
                        ebookQuestionAnswerService
                ))
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
    void semanticSearchShouldPassAuthenticatedMemberAndReaderToken() throws Exception {
        authenticateAsMember(10L);
        EbookSemanticSearchRequest request = new EbookSemanticSearchRequest(
                "dependency inversion",
                5,
                new BigDecimal("0.7")
        );
        EbookSemanticSearchResponse response = new EbookSemanticSearchResponse(
                501L,
                1001L,
                1,
                List.of(new EbookSemanticSearchHitResponse(
                        "vector-1",
                        0.91,
                        "Dependency inversion means...",
                        new EbookSemanticSearchCitationResponse(
                                "doc_ebook_1001", 501L, 1001L, "SOLID", 3, 42, 43, 12
                        )
                ))
        );
        when(ebookSemanticSearchService.search(10L, 501L, "reader-token", request)).thenReturn(response);

        mockMvc.perform(post("/api/ebooks/{bookId}/reader/semantic-search", 501L)
                        .header("X-Reading-Session", "reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Tìm kiếm nội dung ebook thành công"))
                .andExpect(jsonPath("$.data.bookId").value(501))
                .andExpect(jsonPath("$.data.ebookId").value(1001))
                .andExpect(jsonPath("$.data.results[0].chunkId").value("vector-1"))
                .andExpect(jsonPath("$.data.results[0].citation.pageStart").value(42));

        verify(ebookSemanticSearchService).search(10L, 501L, "reader-token", request);
    }

    @Test
    void semanticSearchShouldRequireReadingSessionHeader() throws Exception {
        authenticateAsMember(10L);

        mockMvc.perform(post("/api/ebooks/{bookId}/reader/semantic-search", 501L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"dependency inversion\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.READING_SESSION_REQUIRED.getCode()));
    }

    @Test
    void semanticSearchShouldValidateQuery() throws Exception {
        authenticateAsMember(10L);

        mockMvc.perform(post("/api/ebooks/{bookId}/reader/semantic-search", 501L)
                        .header("X-Reading-Session", "reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \",\"topK\":21}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()))
                .andExpect(jsonPath("$.data.query").value("Câu tìm kiếm không được để trống"))
                .andExpect(jsonPath("$.data.topK").value("topK tối đa là 20"));
    }

    @Test
    void askThisBookShouldReturnGroundedAnswerContract() throws Exception {
        authenticateAsMember(10L);
        EbookAskRequest request = new EbookAskRequest("DIP là gì?", 5, new BigDecimal("0.7"));
        EbookAnswerResponse response = new EbookAnswerResponse(
                501L,
                1001L,
                "DIP tách module cấp cao khỏi module cấp thấp.",
                true,
                false,
                null,
                List.of(new EbookAnswerCitationResponse(
                        "doc_ebook_1001", 501L, 1001L, "SOLID", 3,
                        42, 43, 12, "vector-1", "Dependency inversion means...", 0.91
                )),
                "library-ebook-answer-v1"
        );
        when(ebookQuestionAnswerService.answer(10L, 501L, "reader-token", request)).thenReturn(response);

        mockMvc.perform(post("/api/ebooks/{bookId}/reader/ask", 501L)
                        .header("X-Reading-Session", "reader-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.grounded").value(true))
                .andExpect(jsonPath("$.data.abstained").value(false))
                .andExpect(jsonPath("$.data.citations[0].chunkId").value("vector-1"))
                .andExpect(jsonPath("$.data.citations[0].pageStart").value(42));

        verify(ebookQuestionAnswerService).answer(10L, 501L, "reader-token", request);
    }

    private void authenticateAsMember(Long memberId) {
        Member member = Member.builder()
                .id(memberId)
                .email("member@example.com")
                .password("hashed-password")
                .role(MemberRole.MEMBER)
                .status(MemberStatus.ACTIVE)
                .build();
        MemberUserDetails principal = new MemberUserDetails(member);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        ));
    }
}
