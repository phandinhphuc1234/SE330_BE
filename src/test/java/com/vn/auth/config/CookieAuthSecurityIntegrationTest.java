package com.vn.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.auth.controller.AuthController;
import com.vn.auth.dto.request.LoginRequest;
import com.vn.auth.dto.response.AuthResponse;
import com.vn.auth.dto.response.AuthResult;
import com.vn.auth.security.JwtAuthFilter;
import com.vn.auth.security.JwtService;
import com.vn.auth.security.MemberUserDetails;
import com.vn.auth.security.MemberUserDetailsService;
import com.vn.auth.security.SecurityErrorResponseWriter;
import com.vn.auth.security.cookie.CookieProperties;
import com.vn.auth.security.cookie.RefreshTokenCookieService;
import com.vn.auth.service.AuthService;
import com.vn.auth.service.PasswordManagementService;
import com.vn.auth.service.RedisTokenService;
import com.vn.shared.config.CorsConfig;
import com.vn.shared.config.CorsProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.exception.GlobalExceptionHandler;
import com.vn.shared.testsupport.TestDataFactory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Uses the actual production security chain, JWT filter, CORS, controller and
// exception handlers. Only external/service dependencies are mocked: no real
// credentials, Redis, database, email or provider request is needed.
@WebAppConfiguration
@SpringJUnitConfig(classes = {
        SecurityConfig.class, CorsConfig.class, JwtAuthFilter.class,
        SecurityErrorResponseWriter.class, AuthController.class,
        RefreshTokenCookieService.class, GlobalExceptionHandler.class,
        CookieAuthSecurityIntegrationTest.TestConfiguration.class
})
@TestPropertySource(properties = "app.cookie.refresh-token-name=refreshToken")
class CookieAuthSecurityIntegrationTest {
    private static final String FRONTEND_ORIGIN = "https://library.flashsale123.tech";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String LOGOUT_PATH = "/api/auth/logout";
    private static final String ACCESS_TOKEN = "test-access-token";
    private static final String REFRESH_TOKEN = "test-refresh-token";
    private static final String LOGIN_JSON =
            "{\"email\":\"member1@example.com\",\"password\":\"test-password\"}";

    @Autowired private WebApplicationContext context;
    @Autowired private AuthService authService;
    @Autowired private PasswordManagementService passwordManagementService;
    @Autowired private JwtService jwtService;
    @Autowired private RedisTokenService redisTokenService;
    @Autowired private MemberUserDetailsService memberUserDetailsService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(authService, passwordManagementService, jwtService,
                redisTokenService, memberUserDetailsService);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void bootstrapShouldReturnMaskedTokenAndHostBoundCookieWithoutSession() throws Exception {
        var bootstrap = bootstrap();
        assertThat(bootstrap.cookie().getSecure()).isTrue();
        assertThat(bootstrap.cookie().isHttpOnly()).isTrue();
        assertThat(bootstrap.cookie().getPath()).isEqualTo("/");
        assertThat(bootstrap.cookie().getDomain()).isNull();
        assertThat(bootstrap.cookie().getAttribute("SameSite")).isEqualTo("None");
        assertThat(bootstrap.token()).isNotBlank().isNotEqualTo(bootstrap.cookie().getValue());
        assertThat(bootstrap.headerName()).isEqualTo("X-XSRF-TOKEN");
        verifyNoInteractions(authService, jwtService, redisTokenService);
    }

    @Test
    void refreshCookieWithoutCsrfShouldBeRejectedBeforeController() throws Exception {
        mvc.perform(postTo(REFRESH_PATH).cookie(refreshCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(authService);
    }

    @Test
    void refreshWithIncorrectCsrfShouldBeRejectedBeforeController() throws Exception {
        var csrf = bootstrap();
        mvc.perform(postTo(REFRESH_PATH).cookie(refreshCookie(), csrf.cookie())
                        .header(csrf.headerName(), "incorrect-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authService);
    }

    @Test
    void validCsrfWithoutRefreshCookieShouldStillRequireAuthentication() throws Exception {
        var csrf = bootstrap();
        mvc.perform(postTo(REFRESH_PATH).cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_REFRESH_TOKEN"));
        verifyNoInteractions(authService);
    }

    @Test
    void validCsrfWithRejectedRefreshTokenShouldNotAuthenticate() throws Exception {
        var csrf = bootstrap();
        when(authService.refreshToken(REFRESH_TOKEN))
                .thenThrow(new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN));
        mvc.perform(postTo(REFRESH_PATH).cookie(refreshCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
        verify(authService).refreshToken(REFRESH_TOKEN);
    }

    @Test
    void validCsrfAndRefreshCookieShouldReachControllerAndRotateCookie() throws Exception {
        var csrf = bootstrap();
        when(authService.refreshToken(REFRESH_TOKEN)).thenReturn(authResult());
        var result = mvc.perform(postTo(REFRESH_PATH).cookie(refreshCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value(ACCESS_TOKEN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("refreshToken=test-rotated-refresh", "HttpOnly", "Secure");
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(authService).refreshToken(REFRESH_TOKEN);
    }

    @Test
    void refreshCookieAloneShouldNotAuthorizeBearerProtectedLogout() throws Exception {
        mvc.perform(postTo(LOGOUT_PATH).cookie(refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(authService, jwtService, redisTokenService);
    }

    @Test
    void explicitValidBearerShouldAuthorizeLogoutWithoutCsrf() throws Exception {
        Instant issuedAt = Instant.parse("2026-10-01T00:00:00Z");
        when(jwtService.isAccessToken(ACCESS_TOKEN)).thenReturn(true);
        when(jwtService.extractUserId(ACCESS_TOKEN)).thenReturn(1L);
        when(jwtService.extractIssuedAt(ACCESS_TOKEN)).thenReturn(issuedAt);
        when(jwtService.extractEmail(ACCESS_TOKEN)).thenReturn("member1@example.com");
        when(memberUserDetailsService.loadUserByUsername("member1@example.com"))
                .thenReturn(new MemberUserDetails(TestDataFactory.activeMember(1L)));
        mvc.perform(postTo(LOGOUT_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk());
        verify(authService).logout(ACCESS_TOKEN, 1L);
    }

    @Test
    void foreignOriginLoginShouldBeRejectedBeforeController() throws Exception {
        mvc.perform(postTo(LOGIN_PATH).contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON)
                        .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verifyNoInteractions(authService);
    }

    @Test
    void foreignOriginRefreshShouldBeRejectedEvenWithValidCsrf() throws Exception {
        var csrf = bootstrap();
        mvc.perform(postTo(REFRESH_PATH).cookie(refreshCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authService);
    }

    @Test
    void configuredOriginShouldAllowRefreshPreflightWithCsrfHeader() throws Exception {
        mvc.perform(options(REFRESH_PATH).servletPath(REFRESH_PATH).secure(true)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        verifyNoInteractions(authService);
    }

    @Test
    void loginShouldRejectFormAndPlainTextEvenWithoutOriginHeader() throws Exception {
        for (MediaType type : List.of(MediaType.APPLICATION_FORM_URLENCODED, MediaType.TEXT_PLAIN)) {
            mvc.perform(postTo(LOGIN_PATH).contentType(type).content(LOGIN_JSON))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
        verifyNoInteractions(authService);
    }

    @Test
    void configuredOriginJsonLoginShouldRemainCompatible() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(authResult());
        mvc.perform(postTo(LOGIN_PATH).header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value(ACCESS_TOKEN));
        verify(authService).login(new LoginRequest("member1@example.com", "test-password"));
    }

    private CsrfBootstrap bootstrap() throws Exception {
        String path = "/api/auth/csrf";
        var result = mvc.perform(get(path).servletPath(path).secure(true)
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        var data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");
        Cookie cookie = result.getResponse().getCookie("__Host-XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return new CsrfBootstrap(cookie, data.get("token").asText(), data.get("headerName").asText());
    }

    private MockHttpServletRequestBuilder postTo(String path) {
        // Match the default DispatcherServlet mapping used by the real servlet container.
        return post(path).servletPath(path).secure(true);
    }

    private Cookie refreshCookie() {
        return new Cookie("refreshToken", REFRESH_TOKEN);
    }

    private AuthResult authResult() {
        return new AuthResult(AuthResponse.of(ACCESS_TOKEN, 900_000), "test-rotated-refresh", 604_800_000);
    }

    private record CsrfBootstrap(Cookie cookie, String token, String headerName) { }

    @Configuration
    @EnableWebMvc
    static class TestConfiguration {
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean PasswordManagementService passwordManagementService() { return mock(PasswordManagementService.class); }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean RedisTokenService redisTokenService() { return mock(RedisTokenService.class); }
        @Bean MemberUserDetailsService memberUserDetailsService() { return mock(MemberUserDetailsService.class); }

        @Bean
        CookieProperties cookieProperties() {
            CookieProperties properties = new CookieProperties();
            properties.setRefreshTokenSecure(true);
            properties.setRefreshTokenSameSite("None");
            return properties;
        }

        @Bean
        CorsProperties corsProperties() {
            CorsProperties properties = new CorsProperties();
            properties.setAllowedOrigins(List.of(FRONTEND_ORIGIN));
            properties.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            properties.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
            properties.setAllowCredentials(true);
            return properties;
        }
    }
}
