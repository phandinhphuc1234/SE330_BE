package com.vn.dto.auth.response;

import lombok.Builder;

// Response trả về khi login / refresh token thành công
@Builder
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn   // thời gian sống access token (milliseconds)
) {
    // Factory method tạo AuthResponse với tokenType mặc định "Bearer"
    public static AuthResponse of(String accessToken, long expiresIn) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .build();
    }
}

