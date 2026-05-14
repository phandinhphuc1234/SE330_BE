package com.vn.controller;

import com.vn.controller.docs.AuthApiDocs;
import com.vn.dto.auth.request.LoginRequest;
import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.auth.request.ResendVerificationRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.auth.response.AuthResult;
import com.vn.dto.auth.response.AuthResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.security.cookie.RefreshTokenCookieService;
import com.vn.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApiDocs {
    // Call các service cần thiết 
    private final AuthService authService;
    private final RefreshTokenCookieService refreshTokenCookieService;

    // ── POST /api/auth/register ──
    // Đăng ký tài khoản → gửi email xác nhận
    @PostMapping("/register")
    @Override
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegistrationRequest request) {
        authService.register(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đăng ký thành công. Vui lòng kiểm tra email để xác nhận tài khoản.", null));
    }

    // ── GET /api/auth/verify-email?token=xxx ──
    // User click link trong email → xác nhận tài khoản
    @GetMapping("/verify-email")
    @Override
    public ResponseEntity<ApiResponse<Void>> verifyEmail(
            @RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.success("Xác nhận email thành công. Bạn có thể đăng nhập.", null));
    }

    // ── POST /api/auth/login ──
    // Đăng nhập → access token trong body, refresh token trong HttpOnly cookie
    @PostMapping("/login")
    @Override
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                           HttpServletResponse response) {
        AuthResult authResult = authService.login(request);

        refreshTokenCookieService.addRefreshTokenCookie(
                response,
                authResult.refreshToken(),
                authResult.refreshTokenExpiryMs()
        );

        return ResponseEntity.ok(ApiResponse.success("Đăng nhập thành công", authResult.authResponse()));
    }

    // ── POST /api/auth/refresh ──
    // Refresh token rotation: gửi refresh token từ cookie → nhận access + refresh mới
    @PostMapping("/refresh")
    @Override
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = "${app.cookie.refresh-token-name}", required = false) String refreshToken,
            HttpServletResponse response) {

        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.errorWithTrace("UNAUTHORIZED", "Không tìm thấy refresh token", null));
        }

        AuthResult authResult = authService.refreshToken(refreshToken);

        refreshTokenCookieService.addRefreshTokenCookie(
                response,
                authResult.refreshToken(),
                authResult.refreshTokenExpiryMs()
        );

        return ResponseEntity.ok(ApiResponse.success("Làm mới token thành công", authResult.authResponse()));
    }

    @PostMapping("/resend-verification")
    @Override
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerificationEmail(request);
        return ResponseEntity.ok(ApiResponse.success("Email xác thực đã được gửi lại", null));
    }

    // ── POST /api/auth/logout ──
    // Đăng xuất: blacklist access token + xóa refresh token
    @PostMapping("/logout")
    @Override
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader("Authorization") String authHeader,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            HttpServletResponse response) {
        // Từ chối request nếu thiếu Bearer token hợp lệ hoặc chưa xác thực được người dùng.
        if (authHeader == null || !authHeader.startsWith("Bearer ") || userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        String accessToken = authHeader.substring(7); // bỏ "Bearer "
        authService.logout(accessToken, userDetails.getMember().getId());

        refreshTokenCookieService.clearRefreshTokenCookie(response);

        return ResponseEntity.ok(ApiResponse.success("Đăng xuất thành công", null));
    }

}

