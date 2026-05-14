package com.vn.security.cookie;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
// // Đọc cấu hình cookie refresh token từ application.yml/properties
// với prefix "app.cookie"
// để dùng khi tạo và xóa cookie xác thực.
public class RefreshTokenCookieService {

    private final CookieProperties cookieProperties;
    // Thêm vào cookie trên trình duyệt khi user đăng nhập vào trình duyệt
    public void addRefreshTokenCookie(HttpServletResponse response, String refreshToken, long refreshExpiryMs) {
        ResponseCookie cookie = ResponseCookie.from(cookieProperties.getRefreshTokenName(), refreshToken)
                .httpOnly(true)
                .secure(cookieProperties.isRefreshTokenSecure())
                .sameSite(cookieProperties.getRefreshTokenSameSite())
                .path(cookieProperties.getRefreshTokenPath())
                .maxAge(refreshExpiryMs / 1000)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
    // Xóa refreshtoken trong cookie ra khỏi trình duyệt khi user đăng xuất
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieProperties.getRefreshTokenName(), "")
                .httpOnly(true)
                .secure(cookieProperties.isRefreshTokenSecure())
                .sameSite(cookieProperties.getRefreshTokenSameSite())
                .path(cookieProperties.getRefreshTokenPath())
                .maxAge(0)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

