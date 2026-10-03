package com.vn.auth.mapper;

import com.vn.auth.dto.response.AuthResponse;
import com.vn.auth.dto.response.AuthResult;
import com.vn.auth.entity.EmailVerification;
import com.vn.member.entity.Member;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuthMapper {

    public EmailVerification toEmailVerification(Member member, String token, Instant expiresAt) {
        return EmailVerification.builder()
                .member(member)
                .token(token)
                .expiresAt(expiresAt)
                .build();
    }

    public AuthResponse toAuthResponse(String accessToken, long expiresIn) {
        return AuthResponse.of(accessToken, expiresIn);
    }

    public AuthResult toAuthResult(String accessToken, long accessExpiresIn, String refreshToken, long refreshExpiresIn) {
        return new AuthResult(
                toAuthResponse(accessToken, accessExpiresIn),
                refreshToken,
                refreshExpiresIn
        );
    }
}

