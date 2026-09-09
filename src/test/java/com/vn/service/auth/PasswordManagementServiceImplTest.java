package com.vn.service.auth;

import com.vn.dto.auth.request.ChangePasswordRequest;
import com.vn.dto.auth.request.ForgotPasswordRequest;
import com.vn.dto.auth.request.ResetPasswordRequest;
import com.vn.entity.Member;
import com.vn.entity.PasswordResetToken;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.MemberRepository;
import com.vn.repository.PasswordResetTokenRepository;
import com.vn.security.JwtService;
import com.vn.service.EmailService;
import com.vn.service.PasswordManagementService;
import com.vn.service.PasswordResetRateLimitService;
import com.vn.service.RedisTokenService;
import com.vn.service.impl.PasswordManagementServiceImpl;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordManagementServiceImplTest {

    @Mock private MemberRepository memberRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RedisTokenService redisTokenService;
    @Mock private JwtService jwtService;
    @Mock private PasswordResetRateLimitService passwordResetRateLimitService;
    @Mock private EmailService emailService;

    private PasswordManagementService passwordManagementService;

    @BeforeEach
    void setUp() {
        PasswordManagementServiceImpl service = new PasswordManagementServiceImpl(
                memberRepository, passwordResetTokenRepository, passwordEncoder, redisTokenService,
                jwtService, passwordResetRateLimitService, emailService
        );
        ReflectionTestUtils.setField(service, "tokenExpiryMinutes", 30L);
        passwordManagementService = service;
    }

    @Test
    void requestPasswordReset_shouldPersistOnlyHashAndEmailRawTokenForActiveMember() throws Exception {
        Member member = TestDataFactory.activeMember(1L);
        when(passwordResetRateLimitService.tryAcquire("member@example.com")).thenReturn(true);
        when(memberRepository.findByEmail("member@example.com")).thenReturn(Optional.of(member));

        passwordManagementService.requestPasswordReset(new ForgotPasswordRequest(" MEMBER@example.com "));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        ArgumentCaptor<String> rawTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        verify(emailService).sendPasswordResetEmail(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(member.getEmail()),
                org.mockito.ArgumentMatchers.eq(member.getFullName()),
                rawTokenCaptor.capture());

        String rawToken = rawTokenCaptor.getValue();
        assertThat(rawToken).hasSizeGreaterThan(40);
        assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(sha256(rawToken));
        assertThat(tokenCaptor.getValue().getTokenHash()).doesNotContain(rawToken);
        verify(passwordResetTokenRepository).invalidateUnusedTokensForMember(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void requestPasswordReset_shouldStayGenericWhenRateLimited() {
        when(passwordResetRateLimitService.tryAcquire("missing@example.com")).thenReturn(false);

        passwordManagementService.requestPasswordReset(new ForgotPasswordRequest("missing@example.com"));

        verify(memberRepository, never()).findByEmail(anyString());
        verify(emailService, never()).sendPasswordResetEmail(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void resetPassword_shouldConsumeTokenEncodePasswordAndRevokeAllSessions() throws Exception {
        String rawToken = "token-for-reset";
        Member member = TestDataFactory.activeMember(1L);
        PasswordResetToken token = PasswordResetToken.builder()
                .member(member)
                .tokenHash(sha256(rawToken))
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
        when(passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull(sha256(rawToken))).thenReturn(Optional.of(token));
        when(memberRepository.findLockedById(1L)).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("NewPassword123", "hashed-password")).thenReturn(false);
        when(passwordEncoder.encode("NewPassword123")).thenReturn("new-hash");
        when(jwtService.getRefreshExpiry()).thenReturn(604800000L);

        passwordManagementService.resetPassword(new ResetPasswordRequest(rawToken, "NewPassword123", "NewPassword123"));

        assertThat(member.getPassword()).isEqualTo("new-hash");
        verify(passwordResetTokenRepository).invalidateUnusedTokensForMember(org.mockito.ArgumentMatchers.eq(1L), any());
        verify(redisTokenService).revokeAllSessions(1L, 604800000L);
    }

    @Test
    void changePassword_shouldRejectWrongCurrentPasswordWithoutRevokingSession() {
        Member member = TestDataFactory.activeMember(1L);
        when(memberRepository.findLockedById(1L)).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("wrong-current", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> passwordManagementService.changePassword(1L,
                new ChangePasswordRequest("wrong-current", "NewPassword123", "NewPassword123")))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getCode()));

        verify(redisTokenService, never()).revokeAllSessions(anyLong(), anyLong());
        verify(passwordEncoder, never()).encode(anyString());
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
