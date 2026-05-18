package com.vn.service.auth;

import com.vn.dto.auth.request.LoginRequest;
import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.auth.request.ResendVerificationRequest;
import com.vn.dto.auth.response.AuthResult;
import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.AuthMapper;
import com.vn.mapper.MemberMapper;
import com.vn.repository.EmailVerificationRepository;
import com.vn.repository.MemberRepository;
import com.vn.security.JwtService;
import com.vn.service.EmailService;
import com.vn.service.EmailVerificationRateLimitService;
import com.vn.service.RedisTokenService;
import com.vn.service.impl.AuthServiceImpl;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private EmailVerificationRepository verificationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RedisTokenService redisTokenService;

    @Mock
    private EmailVerificationRateLimitService emailVerificationRateLimitService;

    @Mock
    private EmailService emailService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                memberRepository,
                verificationRepository,
                passwordEncoder,
                jwtService,
                redisTokenService,
                emailVerificationRateLimitService,
                emailService,
                new AuthMapper(),
                new MemberMapper()
        );
    }

    @Test
    void register_shouldCreateMemberVerificationAndSendEmail_whenRequestValid() {
        RegistrationRequest request = new RegistrationRequest(
                "Nguyen Van A",
                "user@example.com",
                "Password123",
                "0123456789"
        );

        when(memberRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123")).thenReturn("encoded-password");
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            member.setId(1L);
            return member;
        });
        when(verificationRepository.save(any(EmailVerification.class))).thenAnswer(invocation -> {
            EmailVerification verification = invocation.getArgument(0);
            verification.setId(10L);
            return verification;
        });

        authService.register(request);

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        ArgumentCaptor<EmailVerification> verificationCaptor = ArgumentCaptor.forClass(EmailVerification.class);

        verify(memberRepository).save(memberCaptor.capture());
        verify(verificationRepository).save(verificationCaptor.capture());

        Member savedMember = memberCaptor.getValue();
        assertThat(savedMember.getEmail()).isEqualTo("user@example.com");
        assertThat(savedMember.getPassword()).isEqualTo("encoded-password");

        EmailVerification savedVerification = verificationCaptor.getValue();
        assertThat(savedVerification.getMember()).isSameAs(savedMember);
        assertThat(savedVerification.getToken()).isNotBlank();
        assertThat(savedVerification.getExpiresAt()).isNotNull();

        verify(emailService).sendVerificationEmail(1L, "user@example.com", "Nguyen Van A", savedVerification.getToken());
        verify(emailVerificationRateLimitService).startCooldown(1L);
    }

    @Test
    void register_shouldThrowEmailAlreadyExists_whenEmailExists() {
        RegistrationRequest request = new RegistrationRequest(
                "Nguyen Van A",
                "user@example.com",
                "Password123",
                null
        );
        when(memberRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS.getCode()));

        verify(memberRepository, never()).save(any());
        verify(emailService, never()).sendVerificationEmail(any(), any(), any(), any());
    }

    @Test
    void login_shouldReturnAuthResultAndStoreRefreshToken_whenCredentialsValid() {
        LoginRequest request = new LoginRequest("member1@example.com", "Password123");
        Member member = TestDataFactory.activeMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("Password123", "hashed-password")).thenReturn(true);
        when(jwtService.generateAccessToken(member.getEmail(), member.getId())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(member.getEmail(), member.getId())).thenReturn("refresh-token");
        when(jwtService.getAccessExpiry()).thenReturn(900000L);
        when(jwtService.getRefreshExpiry()).thenReturn(604800000L);

        AuthResult result = authService.login(request);

        assertThat(result.authResponse().accessToken()).isEqualTo("access-token");
        assertThat(result.authResponse().tokenType()).isEqualTo("Bearer");
        assertThat(result.authResponse().expiresIn()).isEqualTo(900000L);
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.refreshTokenExpiryMs()).isEqualTo(604800000L);

        verify(redisTokenService).saveRefreshToken(1L, "refresh-token", 604800000L);
    }

    @Test
    void login_shouldThrowInvalidCredentials_whenEmailNotFound() {
        LoginRequest request = new LoginRequest("missing@example.com", "Password123");
        when(memberRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getCode()));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void login_shouldThrowInvalidCredentials_whenPasswordInvalid() {
        LoginRequest request = new LoginRequest("member1@example.com", "wrong-password");
        Member member = TestDataFactory.activeMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getCode()));

        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void login_shouldThrowEmailNotVerified_whenMemberPendingVerification() {
        LoginRequest request = new LoginRequest("member1@example.com", "Password123");
        Member member = TestDataFactory.pendingMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("Password123", "hashed-password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED.getCode()));

        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void login_shouldThrowAccountInactive_whenMemberInactive() {
        LoginRequest request = new LoginRequest("member1@example.com", "Password123");
        Member member = TestDataFactory.inactiveMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("Password123", "hashed-password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE.getCode()));

        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void login_shouldThrowAccountInactive_whenMemberBanned() {
        LoginRequest request = new LoginRequest("member1@example.com", "Password123");
        Member member = TestDataFactory.bannedMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(passwordEncoder.matches("Password123", "hashed-password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE.getCode()));

        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void verifyEmail_shouldActivateMemberAndMarkTokenUsed_whenTokenValid() {
        Member member = TestDataFactory.pendingMember(1L);
        EmailVerification verification = TestDataFactory.activeEmailVerification(10L, member, "valid-token");

        when(verificationRepository.findByTokenAndIsUsedFalse("valid-token"))
                .thenReturn(Optional.of(verification));

        authService.verifyEmail("valid-token");

        assertThat(verification.getIsUsed()).isTrue();
        assertThat(verification.getUsedAt()).isNotNull();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        verify(emailVerificationRateLimitService).clear(1L);
    }

    @Test
    void verifyEmail_shouldThrowInvalidOrExpiredToken_whenTokenNotFound() {
        when(verificationRepository.findByTokenAndIsUsedFalse("missing-token"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail("missing-token"))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_TOKEN.getCode()));
    }

    @Test
    void verifyEmail_shouldThrowVerificationTokenExpired_whenTokenExpired() {
        Member member = TestDataFactory.pendingMember(1L);
        EmailVerification verification = TestDataFactory.expiredEmailVerification(10L, member, "expired-token");

        when(verificationRepository.findByTokenAndIsUsedFalse("expired-token"))
                .thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> authService.verifyEmail("expired-token"))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.VERIFICATION_TOKEN_EXPIRED.getCode()));

        assertThat(verification.getIsUsed()).isFalse();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.PENDING_VERIFICATION);
        verify(emailVerificationRateLimitService, never()).clear(anyLong());
    }

    @Test
    void refreshToken_shouldThrowInvalidOrExpiredToken_whenJwtInvalid() {
        when(jwtService.isValid("invalid-refresh-token")).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken("invalid-refresh-token"))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_TOKEN.getCode()));

        verify(redisTokenService, never()).getRefreshToken(anyLong());
    }

    @Test
    void refreshToken_shouldThrowInvalidOrExpiredToken_whenRedisTokenMissing() {
        when(jwtService.isValid("refresh-token")).thenReturn(true);
        when(jwtService.extractUserId("refresh-token")).thenReturn(1L);
        when(jwtService.extractEmail("refresh-token")).thenReturn("member1@example.com");
        when(redisTokenService.getRefreshToken(1L)).thenReturn(null);

        assertThatThrownBy(() -> authService.refreshToken("refresh-token"))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_TOKEN.getCode()));

        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void refreshToken_shouldThrowInvalidOrExpiredToken_whenRedisTokenDoesNotMatch() {
        when(jwtService.isValid("refresh-token")).thenReturn(true);
        when(jwtService.extractUserId("refresh-token")).thenReturn(1L);
        when(jwtService.extractEmail("refresh-token")).thenReturn("member1@example.com");
        when(redisTokenService.getRefreshToken(1L)).thenReturn("another-refresh-token");

        assertThatThrownBy(() -> authService.refreshToken("refresh-token"))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_OR_EXPIRED_TOKEN.getCode()));

        verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
    }

    @Test
    void refreshToken_shouldRotateTokens_whenRefreshTokenMatchesRedis() {
        when(jwtService.isValid("old-refresh-token")).thenReturn(true);
        when(jwtService.extractUserId("old-refresh-token")).thenReturn(1L);
        when(jwtService.extractEmail("old-refresh-token")).thenReturn("member1@example.com");
        when(redisTokenService.getRefreshToken(1L)).thenReturn("old-refresh-token");
        when(jwtService.generateAccessToken("member1@example.com", 1L)).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken("member1@example.com", 1L)).thenReturn("new-refresh-token");
        when(jwtService.getAccessExpiry()).thenReturn(900000L);
        when(jwtService.getRefreshExpiry()).thenReturn(604800000L);

        AuthResult result = authService.refreshToken("old-refresh-token");

        assertThat(result.authResponse().accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
        verify(redisTokenService).saveRefreshToken(1L, "new-refresh-token", 604800000L);
    }

    @Test
    void resendVerificationEmail_shouldUpdateExistingVerificationAndSendEmail_whenRequestValid() {
        ResendVerificationRequest request = new ResendVerificationRequest("member1@example.com");
        Member member = TestDataFactory.pendingMember(1L);
        EmailVerification verification = TestDataFactory.activeEmailVerification(10L, member, "old-token");

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(verificationRepository.findByMemberAndIsUsedFalse(member)).thenReturn(Optional.of(verification));
        when(verificationRepository.save(any(EmailVerification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.resendVerificationEmail(request);

        assertThat(verification.getToken()).isNotEqualTo("old-token");
        assertThat(verification.getExpiresAt()).isAfter(verification.getLastSentAt());
        assertThat(verification.getIsUsed()).isFalse();
        assertThat(verification.getUsedAt()).isNull();

        verify(verificationRepository).save(verification);
        verify(emailService).sendVerificationEmail(1L, member.getEmail(), member.getFullName(), verification.getToken());
        verify(emailVerificationRateLimitService).incrementResendCount(1L);
        verify(emailVerificationRateLimitService).startCooldown(1L);
    }

    @Test
    void resendVerificationEmail_shouldCreateVerification_whenNoActiveVerificationExists() {
        ResendVerificationRequest request = new ResendVerificationRequest("member1@example.com");
        Member member = TestDataFactory.pendingMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(verificationRepository.findByMemberAndIsUsedFalse(member)).thenReturn(Optional.empty());
        when(verificationRepository.save(any(EmailVerification.class))).thenAnswer(invocation -> {
            EmailVerification verification = invocation.getArgument(0);
            verification.setId(10L);
            return verification;
        });

        authService.resendVerificationEmail(request);

        ArgumentCaptor<EmailVerification> verificationCaptor = ArgumentCaptor.forClass(EmailVerification.class);
        verify(verificationRepository).save(verificationCaptor.capture());

        EmailVerification verification = verificationCaptor.getValue();
        assertThat(verification.getMember()).isSameAs(member);
        assertThat(verification.getToken()).isNotBlank();
        assertThat(verification.getExpiresAt()).isNotNull();
        assertThat(verification.getIsUsed()).isFalse();

        verify(emailService).sendVerificationEmail(1L, member.getEmail(), member.getFullName(), verification.getToken());
    }

    @Test
    void resendVerificationEmail_shouldThrowResourceNotFound_whenMemberNotFound() {
        ResendVerificationRequest request = new ResendVerificationRequest("missing@example.com");
        when(memberRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resendVerificationEmail(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode()));

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void resendVerificationEmail_shouldThrowEmailAlreadyVerified_whenMemberActive() {
        ResendVerificationRequest request = new ResendVerificationRequest("member1@example.com");
        Member member = TestDataFactory.activeMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> authService.resendVerificationEmail(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_VERIFIED.getCode()));

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void resendVerificationEmail_shouldThrowAccountInactive_whenMemberInactive() {
        ResendVerificationRequest request = new ResendVerificationRequest("member1@example.com");
        Member member = TestDataFactory.inactiveMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> authService.resendVerificationEmail(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE.getCode()));

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void resendVerificationEmail_shouldThrowCooldown_whenMemberIsInCooldown() {
        ResendVerificationRequest request = new ResendVerificationRequest("member1@example.com");
        Member member = TestDataFactory.pendingMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(emailVerificationRateLimitService.isInCooldown(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.resendVerificationEmail(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.EMAIL_RESEND_COOLDOWN.getCode()));

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void resendVerificationEmail_shouldThrowLimitExceeded_whenMemberExceededResendLimit() {
        ResendVerificationRequest request = new ResendVerificationRequest("member1@example.com");
        Member member = TestDataFactory.pendingMember(1L);

        when(memberRepository.findByEmail("member1@example.com")).thenReturn(Optional.of(member));
        when(emailVerificationRateLimitService.isInCooldown(1L)).thenReturn(false);
        when(emailVerificationRateLimitService.hasExceededResendLimit(1L)).thenReturn(true);

        assertThatThrownBy(() -> authService.resendVerificationEmail(request))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.EMAIL_RESEND_LIMIT_EXCEEDED.getCode()));

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void logout_shouldBlacklistAccessTokenAndDeleteRefreshToken() {
        when(jwtService.getAccessExpiry()).thenReturn(900000L);

        authService.logout("access-token", 1L);

        verify(redisTokenService).blacklistAccessToken("access-token", 900000L);
        verify(redisTokenService).deleteRefreshToken(1L);
    }
}

