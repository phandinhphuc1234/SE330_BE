package com.vn.repository;

import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findByTokenAndIsUsedFalse(String token);

    Optional<EmailVerification> findByMemberAndIsUsedFalse(Member member);
}

