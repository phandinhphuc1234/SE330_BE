package com.vn.service.impl;

import com.vn.dto.auth.request.ChangePasswordRequest;
import com.vn.dto.auth.request.ForgotPasswordRequest;
import com.vn.dto.auth.request.ResetPasswordRequest;
import com.vn.entity.Member;
import com.vn.entity.PasswordResetToken;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.MemberRepository;
import com.vn.repository.PasswordResetTokenRepository;
import com.vn.security.JwtService;
import com.vn.service.EmailService;
import com.vn.service.PasswordManagementService;
import com.vn.service.PasswordResetRateLimitService;
import com.vn.service.RedisTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordManagementServiceImpl implements PasswordManagementService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RESET_TOKEN_BYTES = 32;

    private final MemberRepository memberRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisTokenService redisTokenService;
    private final JwtService jwtService;
    private final PasswordResetRateLimitService passwordResetRateLimitService;
    private final EmailService emailService;

    @Value("${app.password-reset.token-expiry-minutes:30}")
    private long tokenExpiryMinutes;

    @Override
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        if (!passwordResetRateLimitService.tryAcquire(email)) {
            log.info("eventType={} result={} reason=RATE_LIMITED emailHash={}",
                    LogEvent.PASSWORD_RESET_REQUEST,
                    LogResult.FAILED,
                    sha256(email).substring(0, 12));
            return;
        }

        // Always return the same controller response. Only ACTIVE members receive an email.
        memberRepository.findByEmail(email)
                .filter(member -> member.getStatus() == MemberStatus.ACTIVE)
                .ifPresent(this::issuePasswordResetToken);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        assertPasswordConfirmation(request.newPassword(), request.confirmNewPassword());
        String tokenHash = sha256(request.token());
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHashAndUsedAtIsNull(tokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN));

        Instant now = Instant.now();
        if (!resetToken.getExpiresAt().isAfter(now)) {
            throw new AppException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
        }

        Member member = memberRepository.findLockedById(resetToken.getMember().getId())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN));
        assertAccountActive(member);
        assertPasswordChanged(member, request.newPassword());

        passwordResetTokenRepository.invalidateUnusedTokensForMember(member.getId(), now);
        member.setPassword(passwordEncoder.encode(request.newPassword()));
        revokeAllSessions(member.getId());

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.PASSWORD_RESET,
                LogResult.SUCCESS,
                member.getId(),
                member.getId());
    }

    @Override
    @Transactional
    public void changePassword(Long memberId, ChangePasswordRequest request) {
        assertPasswordConfirmation(request.newPassword(), request.confirmNewPassword());
        Member member = memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        assertAccountActive(member);
        if (!passwordEncoder.matches(request.currentPassword(), member.getPassword())) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
        assertPasswordChanged(member, request.newPassword());

        member.setPassword(passwordEncoder.encode(request.newPassword()));
        revokeAllSessions(member.getId());

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.CHANGE_PASSWORD,
                LogResult.SUCCESS,
                member.getId(),
                member.getId());
    }

    private void issuePasswordResetToken(Member member) {
        Instant now = Instant.now();
        String rawToken = generateRawToken();
        passwordResetTokenRepository.invalidateUnusedTokensForMember(member.getId(), now);
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .member(member)
                .tokenHash(sha256(rawToken))
                .expiresAt(now.plus(tokenExpiryMinutes, ChronoUnit.MINUTES))
                .build());
        emailService.sendPasswordResetEmail(member.getId(), member.getEmail(), member.getFullName(), rawToken);

        log.info("eventType={} result={} memberId={} entityType=PASSWORD_RESET_TOKEN",
                LogEvent.PASSWORD_RESET_REQUEST,
                LogResult.SUCCESS,
                member.getId());
    }

    private void revokeAllSessions(Long memberId) {
        redisTokenService.revokeAllSessions(memberId, jwtService.getRefreshExpiry());
    }

    private void assertPasswordConfirmation(String password, String confirmation) {
        if (!password.equals(confirmation)) {
            throw new AppException(ErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
        }
    }

    private void assertPasswordChanged(Member member, String newPassword) {
        if (passwordEncoder.matches(newPassword, member.getPassword())) {
            throw new AppException(ErrorCode.PASSWORD_SAME_AS_CURRENT);
        }
    }

    private void assertAccountActive(Member member) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[RESET_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}
