package com.vn.repository;

import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // Chỉ dùng để tương thích với token trong link xác thực đã phát hành trước đây.
    Optional<EmailVerification> findByTokenAndIsUsedFalse(String token);

    // Tìm mã xác thực hiện hành của member; cột token lưu BCrypt hash của mã.
    Optional<EmailVerification> findByMemberAndIsUsedFalse(Member member);
}

