package com.vn.dto.auth.response;
// Class này là DTO giữa A
public record AuthResult(
        AuthResponse authResponse,
        String refreshToken,
        long refreshTokenExpiryMs
) {
}

