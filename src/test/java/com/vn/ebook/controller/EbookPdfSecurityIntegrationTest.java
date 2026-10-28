package com.vn.ebook.controller;

import com.vn.auth.config.SecurityConfig;
import com.vn.auth.security.*;
import com.vn.auth.security.cookie.CookieProperties;
import com.vn.auth.service.RedisTokenService;
import com.vn.ebook.service.EbookPdfContent;
import com.vn.ebook.service.EbookRangeNotSatisfiableException;
import com.vn.ebook.service.impl.EbookPdfContentService;
import com.vn.member.enums.MemberRole;
import com.vn.shared.config.CorsConfig;
import com.vn.shared.config.CorsProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.exception.GlobalExceptionHandler;
import com.vn.shared.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebAppConfiguration
@SpringJUnitConfig(classes = { SecurityConfig.class, CorsConfig.class, JwtAuthFilter.class,
        SecurityErrorResponseWriter.class, EbookPdfController.class, GlobalExceptionHandler.class,
        EbookPdfSecurityIntegrationTest.TestConfiguration.class })
class EbookPdfSecurityIntegrationTest {
    private static final String PATH = "/api/ebooks/3343/reader/file";
    private static final String ORIGIN = "https://library.flashsale123.tech";
    @Autowired private WebApplicationContext context;
    @Autowired private EbookPdfContentService contentService;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(contentService);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MemberUserDetails member() {
        return new MemberUserDetails(TestDataFactory.activeMember(1L));
    }

    @Test
    void anonymousSessionTokenMustNotAuthenticateFileRequest() throws Exception {
        mvc.perform(get(PATH).header("X-Reading-Session", "session"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(contentService);
    }

    @Test
    void adminMustNotBypassMemberReaderAuthorization() throws Exception {
        var admin = TestDataFactory.activeMember(1L);
        admin.setRole(MemberRole.ADMIN);
        mvc.perform(get(PATH).with(user(new MemberUserDetails(admin))).header("X-Reading-Session", "session"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(contentService);
    }

    @Test
    void sessionInQueryStringMustNotReplaceHeader() throws Exception {
        mvc.perform(get(PATH).with(user(member())).param("sessionToken", "session"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_SESSION_REQUIRED"));
        verifyNoInteractions(contentService);
    }

    @Test
    void authorizedFullFileMustBePdfNoStoreAndCloseStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        var input = new ByteArrayInputStream("%PDF-test".getBytes()) {
            @Override public void close() { closed.set(true); }
        };
        when(contentService.read(1L, 3343L, "session", null, false))
                .thenReturn(new EbookPdfContent(new InputStreamResource(input), 9, 0, 8, false));
        mvc.perform(get(PATH).with(user(member())).header("X-Reading-Session", "session"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("%PDF-test".getBytes()))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "9"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"book-3343.pdf\""));
        assertThat(closed).isTrue();
    }

    @Test
    void rangeMustBe206WithoutSpringReadingTheStreamTwice() throws Exception {
        when(contentService.read(1L, 3343L, "session", "bytes=0-4", false))
                .thenReturn(new EbookPdfContent(new InputStreamResource(
                        new ByteArrayInputStream("%PDF-".getBytes())), 100, 0, 4, true));
        mvc.perform(get(PATH).with(user(member())).header("X-Reading-Session", "session").header("Range", "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/100"))
                .andExpect(content().bytes("%PDF-".getBytes()));
    }

    @Test
    void invalidRangeMustReturn416AndPrivateHeaders() throws Exception {
        when(contentService.read(1L, 3343L, "session", "bytes=200-", false))
                .thenThrow(new EbookRangeNotSatisfiableException(100));
        mvc.perform(get(PATH).with(user(member())).header("X-Reading-Session", "session").header("Range", "bytes=200-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */100"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0"));
    }

    @Test
    void headMustAuthorizeWithoutOpeningBodyAndIfRangeFallsBackToFullFile() throws Exception {
        when(contentService.read(1L, 3343L, "session", null, true))
                .thenReturn(new EbookPdfContent(null, 100, 0, 99, false));
        mvc.perform(head(PATH).with(user(member())).header("X-Reading-Session", "session")
                        .header("Range", "bytes=0-4").header("If-Range", "old-etag"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
                .andExpect(content().string(""));
    }

    @Test
    void wrongBookOrOwnerMustNotReturnAnyPdfBytes() throws Exception {
        when(contentService.read(1L, 3343L, "foreign", null, false))
                .thenThrow(new AppException(ErrorCode.READING_SESSION_FORBIDDEN));
        mvc.perform(get(PATH).with(user(member())).header("X-Reading-Session", "foreign"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("READING_SESSION_FORBIDDEN"));
    }

    @Test
    void preflightMustAllowJwtSessionAndRangeOnlyFromConfiguredOrigin() throws Exception {
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,X-Reading-Session,Range"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
        mvc.perform(options(PATH).header(HttpHeaders.ORIGIN, "https://attacker.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(contentService);
    }

    @Configuration
    @EnableWebMvc
    static class TestConfiguration {
        @Bean EbookPdfContentService contentService() { return mock(EbookPdfContentService.class); }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean RedisTokenService redisTokenService() { return mock(RedisTokenService.class); }
        @Bean MemberUserDetailsService memberUserDetailsService() { return mock(MemberUserDetailsService.class); }
        @Bean CookieProperties cookieProperties() { return new CookieProperties(); }
        @Bean CorsProperties corsProperties() {
            var properties = new CorsProperties();
            properties.setAllowedOrigins(List.of(ORIGIN));
            properties.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
            properties.setAllowedHeaders(List.of("Authorization", "X-Reading-Session", "Range"));
            properties.setAllowCredentials(true);
            return properties;
        }
    }
}
