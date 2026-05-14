package com.vn.mapper;

import com.vn.dto.auth.response.AuthResponse;
import com.vn.dto.auth.response.AuthResult;
import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
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

