package com.vn.repository;

import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // Tìm token xác thực email còn chưa dùng khi user bấm link verify.
    Optional<EmailVerification> findByTokenAndIsUsedFalse(String token);

    // Tìm token xác thực email còn hiệu lực của một member để resend hoặc kiểm soát rate limit.
    Optional<EmailVerification> findByMemberAndIsUsedFalse(Member member);
}

