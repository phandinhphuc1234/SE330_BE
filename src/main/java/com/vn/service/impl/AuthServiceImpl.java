package com.vn.service.impl;

import com.vn.dto.auth.request.LoginRequest;
import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.auth.request.ResendVerificationRequest;
import com.vn.dto.auth.request.VerifyEmailCodeRequest;
import com.vn.dto.auth.response.AuthResult;
import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.AuthMapper;
import com.vn.mapper.MemberMapper;
import com.vn.repository.EmailVerificationRepository;
import com.vn.repository.MemberRepository;
import com.vn.security.JwtService;
import com.vn.service.AuthService;
import com.vn.service.EmailService;
import com.vn.service.EmailVerificationCodeGenerator;
import com.vn.service.EmailVerificationRateLimitService;
import com.vn.service.RedisTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final long VERIFICATION_CODE_EXPIRY_MINUTES = 10;

    private final MemberRepository memberRepository;
    private final EmailVerificationRepository verificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RedisTokenService redisTokenService;
    private final EmailVerificationRateLimitService emailVerificationRateLimitService;
    private final EmailVerificationCodeGenerator emailVerificationCodeGenerator;
    private final EmailService emailService;
    private final AuthMapper authMapper;
    private final MemberMapper memberMapper;

    // ================= REGISTER =================
    // Flow: check email trùng → hash password → save member → tạo mã → gửi email
    @Override
    @Transactional
    public void register(RegistrationRequest request) {
        // 1. Kiểm tra email đã tồn tại chưa
        if (memberRepository.existsByEmail(request.email())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 2. Tạo member với status PENDING_VERIFICATION, chờ để xác nhận bằng email
        String encodedPassword = passwordEncoder.encode(request.password());
        Member member = memberMapper.toMember(request, encodedPassword);
        // @PrePersist sẽ set: status=PENDING_VERIFICATION, role=MEMBER, maxBorrowLimit=5
        memberRepository.save(member);

        // 3. Chỉ gửi mã rõ qua email; database lưu BCrypt hash để tránh lộ mã.
        String verificationCode = emailVerificationCodeGenerator.generate();
        String codeHash = passwordEncoder.encode(verificationCode);
        Instant expiresAt = Instant.now().plus(VERIFICATION_CODE_EXPIRY_MINUTES, ChronoUnit.MINUTES);
        EmailVerification verification = authMapper.toEmailVerification(member, codeHash, expiresAt);
        // @PrePersist sẽ set: isUsed=false, createdAt=now
        verificationRepository.save(verification);

        // 4. Gửi email xác nhận (@Async — không block)
        emailService.sendVerificationEmail(member.getId(), member.getEmail(), member.getFullName(), verificationCode);
        emailVerificationRateLimitService.startCooldown(member.getId());

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.REGISTER, LogResult.SUCCESS, member.getId(), member.getId());
    }

    // ================= VERIFY EMAIL =================
    // Giữ endpoint token cũ để các link đã gửi trước khi chuyển sang mã số vẫn dùng được.
    @Override
    @Transactional
    public void verifyEmail(String token) {
        // Bản ghi mới lưu BCrypt hash của mã. Hash không bao giờ được dùng như legacy token.
        if (token.startsWith("$2")) {
            throw new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }

        // 1. Tìm token chưa dùng (Tránh trường hợp gửi lại email)
        EmailVerification verification = verificationRepository
                .findByTokenAndIsUsedFalse(token)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN));

        // 2. Kiểm tra hết hạn
        if (verification.getExpiresAt().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.VERIFICATION_TOKEN_EXPIRED);
        }

        // 3. Đánh dấu token đã dùng
        verification.setIsUsed(true);
        verification.setUsedAt(Instant.now());

        // 4. Kích hoạt tài khoản
        Member member = verification.getMember();
        member.setStatus(MemberStatus.ACTIVE);
        emailVerificationRateLimitService.clear(member.getId());

        log.info("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION entityId={}",
                LogEvent.VERIFY_EMAIL, LogResult.SUCCESS, member.getId(), verification.getId());
    }

    @Override
    @Transactional
    public void verifyEmailCode(VerifyEmailCodeRequest request) {
        Member member = memberRepository.findByEmail(request.email().trim())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_VERIFICATION_CODE));

        if (member.getStatus() == MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        if (member.getStatus() != MemberStatus.PENDING_VERIFICATION) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }
        if (emailVerificationRateLimitService.hasExceededVerificationAttemptLimit(member.getId())) {
            throw new AppException(ErrorCode.EMAIL_VERIFICATION_ATTEMPT_LIMIT_EXCEEDED);
        }

        EmailVerification verification = verificationRepository.findByMemberAndIsUsedFalse(member)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_VERIFICATION_CODE));

        if (verification.getExpiresAt().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }

        String storedSecret = verification.getToken();
        boolean codeMatches = storedSecret != null
                && storedSecret.startsWith("$2")
                && passwordEncoder.matches(request.code(), storedSecret);
        if (!codeMatches) {
            long failedAttempts = emailVerificationRateLimitService.recordFailedVerificationAttempt(member.getId());
            if (failedAttempts >= 5) {
                throw new AppException(ErrorCode.EMAIL_VERIFICATION_ATTEMPT_LIMIT_EXCEEDED);
            }
            throw new AppException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        verification.setIsUsed(true);
        verification.setUsedAt(Instant.now());
        member.setStatus(MemberStatus.ACTIVE);
        emailVerificationRateLimitService.clear(member.getId());

        log.info("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION entityId={}",
                LogEvent.VERIFY_EMAIL, LogResult.SUCCESS, member.getId(), verification.getId());
    }

    // ================= LOGIN =================
    // Flow: tìm member → check status → check password → gen tokens → lưu Redis
    @Override
    public AuthResult login(LoginRequest request) {
        // 1. Tìm member theo email
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));

        // 2. Check password
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 3. Check trạng thái tài khoản
        switch (member.getStatus()) {
            case PENDING_VERIFICATION -> throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED);
            case INACTIVE, BANNED     -> throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
            case ACTIVE               -> { /* OK, tiếp tục */ }
        }

        // 4. Tạo access tokens và refresh token
        String accessToken = jwtService.generateAccessToken(member.getEmail(), member.getId());
        String refreshToken = jwtService.generateRefreshToken(member.getEmail(), member.getId());

        // 5. Lưu refresh token vào Redis
        redisTokenService.saveRefreshToken(member.getId(), refreshToken, jwtService.getRefreshExpiry());

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.LOGIN, LogResult.SUCCESS, member.getId(), member.getId());

        return authMapper.toAuthResult(
                accessToken,
                jwtService.getAccessExpiry(),
                refreshToken,
                jwtService.getRefreshExpiry()
        );
    }

    // ================= REFRESH TOKEN =================
    // Flow: validate token → check Redis match → rotate tokens
    @Override
    public AuthResult refreshToken(String refreshToken) {
        // 1. Validate refresh token
        if (!jwtService.isRefreshToken(refreshToken)) {
            throw new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }

        // 2. Extract thông tin
        Long userId = jwtService.extractUserId(refreshToken);
        String email = jwtService.extractEmail(refreshToken);

        // 3. So sánh với Redis (chống token cũ bị dùng lại)
        String storedToken = redisTokenService.getRefreshToken(userId);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }

        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN));
        if (!member.getEmail().equals(email)) {
            throw new AppException(ErrorCode.INVALID_OR_EXPIRED_TOKEN);
        }
        if (member.getStatus() != MemberStatus.ACTIVE) {
            redisTokenService.deleteRefreshToken(userId);
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }

        // 4. Rotate: tạo token mới, xóa token cũ và lưu token mới vào Redis
        String newAccessToken = jwtService.generateAccessToken(member.getEmail(), userId);
        String newRefreshToken = jwtService.generateRefreshToken(member.getEmail(), userId);
        redisTokenService.saveRefreshToken(userId, newRefreshToken, jwtService.getRefreshExpiry());
        // Sau khi log được tạo ra thành công
        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.REFRESH_TOKEN, LogResult.SUCCESS, userId, userId);

        return authMapper.toAuthResult(
                newAccessToken,
                jwtService.getAccessExpiry(),
                newRefreshToken,
                jwtService.getRefreshExpiry()
        );
    }

    @Override
    @Transactional
    public void resendVerificationEmail(ResendVerificationRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (member.getStatus() == MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        if (member.getStatus() != MemberStatus.PENDING_VERIFICATION) {
            throw new AppException(ErrorCode.ACCOUNT_INACTIVE);
        }

        if (emailVerificationRateLimitService.isInCooldown(member.getId())) {
            throw new AppException(ErrorCode.EMAIL_RESEND_COOLDOWN);
        }

        if (emailVerificationRateLimitService.hasExceededResendLimit(member.getId())) {
            throw new AppException(ErrorCode.EMAIL_RESEND_LIMIT_EXCEEDED);
        }

        Instant now = Instant.now();
        String verificationCode = emailVerificationCodeGenerator.generate();
        String newCodeHash = passwordEncoder.encode(verificationCode);
        Instant newExpiresAt = now.plus(VERIFICATION_CODE_EXPIRY_MINUTES, ChronoUnit.MINUTES);
        EmailVerification verification = verificationRepository
                .findByMemberAndIsUsedFalse(member)
                .orElseGet(() -> authMapper.toEmailVerification(member, newCodeHash, newExpiresAt));

        verification.setToken(newCodeHash);
        verification.setExpiresAt(newExpiresAt);
        verification.setIsUsed(false);
        verification.setUsedAt(null);
        verification.setLastSentAt(now);

        verificationRepository.save(verification);
        emailVerificationRateLimitService.clearVerificationAttempts(member.getId());
        emailService.sendVerificationEmail(member.getId(), member.getEmail(), member.getFullName(), verificationCode);
        emailVerificationRateLimitService.incrementResendCount(member.getId());
        emailVerificationRateLimitService.startCooldown(member.getId());

        log.info("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION entityId={}",
                LogEvent.RESEND_VERIFICATION_EMAIL, LogResult.SUCCESS, member.getId(), verification.getId());
    }

    // ================= LOGOUT =================
    // Flow: blacklist access token → xóa refresh token
    @Override
    public void logout(String accessToken, Long userId) {
        // 1. Blacklist access token (TTL = thời gian còn lại)
        // Đơn giản hóa: dùng toàn bộ accessExpiry làm TTL
        redisTokenService.blacklistAccessToken(accessToken, jwtService.getAccessExpiry());

        // 2. Xóa refresh token khỏi Redis
        redisTokenService.deleteRefreshToken(userId);

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.LOGOUT, LogResult.SUCCESS, userId, userId);
    }
}

