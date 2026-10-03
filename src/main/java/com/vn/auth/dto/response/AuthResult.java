package com.vn.auth.dto.response;
// Class này là DTO giữa A
public record AuthResult(
        AuthResponse authResponse,
        String refreshToken,
        long refreshTokenExpiryMs
) {
}

