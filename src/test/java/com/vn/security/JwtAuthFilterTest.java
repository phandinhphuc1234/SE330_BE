package com.vn.security;

import com.vn.exception.ErrorCode;
import com.vn.service.RedisTokenService;
import com.vn.testsupport.TestDataFactory;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private MemberUserDetailsService memberUserDetailsService;
    @Mock
    private RedisTokenService redisTokenService;
    @Mock
    private SecurityErrorResponseWriter securityErrorResponseWriter;
    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_shouldRejectRefreshTokenUsedAsBearerToken() throws Exception {
        MockHttpServletRequest request = bearerRequest("refresh-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(redisTokenService.isBlacklisted("refresh-token")).thenReturn(false);
        when(jwtService.isAccessToken("refresh-token")).thenReturn(false);

        newFilter().doFilter(request, response, filterChain);

        verify(securityErrorResponseWriter).write(response, ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        verify(filterChain, never()).doFilter(request, response);
        verify(memberUserDetailsService, never()).loadUserByUsername("member1@example.com");
    }

    @Test
    void doFilterInternal_shouldRejectInactiveAccountEvenWithValidAccessToken() throws Exception {
        MockHttpServletRequest request = bearerRequest("access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(redisTokenService.isBlacklisted("access-token")).thenReturn(false);
        when(jwtService.isAccessToken("access-token")).thenReturn(true);
        when(jwtService.extractUserId("access-token")).thenReturn(1L);
        when(jwtService.extractIssuedAt("access-token")).thenReturn(Instant.now());
        when(redisTokenService.isSessionRevokedAfter(1L, jwtService.extractIssuedAt("access-token"))).thenReturn(false);
        when(jwtService.extractEmail("access-token")).thenReturn("member1@example.com");
        when(memberUserDetailsService.loadUserByUsername("member1@example.com"))
                .thenReturn(new MemberUserDetails(TestDataFactory.bannedMember(1L)));

        newFilter().doFilter(request, response, filterChain);

        verify(securityErrorResponseWriter).write(response, ErrorCode.ACCOUNT_INACTIVE);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldRejectAccessTokenIssuedBeforeSessionRevocation() throws Exception {
        MockHttpServletRequest request = bearerRequest("revoked-access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Instant issuedAt = Instant.parse("2026-09-08T00:00:00Z");
        when(redisTokenService.isBlacklisted("revoked-access-token")).thenReturn(false);
        when(jwtService.isAccessToken("revoked-access-token")).thenReturn(true);
        when(jwtService.extractUserId("revoked-access-token")).thenReturn(1L);
        when(jwtService.extractIssuedAt("revoked-access-token")).thenReturn(issuedAt);
        when(redisTokenService.isSessionRevokedAfter(1L, issuedAt)).thenReturn(true);

        newFilter().doFilter(request, response, filterChain);

        verify(securityErrorResponseWriter).write(response, ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        verify(memberUserDetailsService, never()).loadUserByUsername("member1@example.com");
        verify(filterChain, never()).doFilter(request, response);
    }

    private JwtAuthFilter newFilter() {
        return new JwtAuthFilter(jwtService, memberUserDetailsService, redisTokenService, securityErrorResponseWriter);
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/members/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
