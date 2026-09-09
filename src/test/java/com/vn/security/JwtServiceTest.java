package com.vn.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "test-secret-must-have-at-least-thirty-two-characters",
            900_000L,
            604_800_000L
    );

    @Test
    void generatedTokens_shouldOnlyValidateForTheirIntendedPurpose() {
        String accessToken = jwtService.generateAccessToken("member@example.com", 1L);
        String refreshToken = jwtService.generateRefreshToken("member@example.com", 1L);

        assertThat(jwtService.isValid(accessToken)).isTrue();
        assertThat(jwtService.isAccessToken(accessToken)).isTrue();
        assertThat(jwtService.isRefreshToken(accessToken)).isFalse();

        assertThat(jwtService.isValid(refreshToken)).isTrue();
        assertThat(jwtService.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwtService.isAccessToken(refreshToken)).isFalse();
    }
}
