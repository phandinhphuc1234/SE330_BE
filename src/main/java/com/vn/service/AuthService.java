package com.vn.service;

import com.vn.dto.auth.request.LoginRequest;
import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.auth.request.ResendVerificationRequest;
import com.vn.dto.auth.response.AuthResult;

public interface AuthService {

    // Đăng ký tài khoản mới → gửi email xác nhận
    void register(RegistrationRequest request);

    // Xác nhận email qua token
    void verifyEmail(String token);

    // Đăng nhập → trả access token, refresh token lưu riêng
    AuthResult login(LoginRequest request);

    // Làm mới token → trả access token mới, rotate refresh token
    AuthResult refreshToken(String refreshToken);

    // Gửi lại email xác nhận, có cooldown và giới hạn số lần gửi lại
    void resendVerificationEmail(ResendVerificationRequest request);

    // Đăng xuất → blacklist access token, xóa refresh token
    void logout(String accessToken, Long userId);
}

