package com.vn.auth.service;

import com.vn.auth.dto.request.LoginRequest;
import com.vn.auth.dto.request.RegistrationRequest;
import com.vn.auth.dto.request.ResendVerificationRequest;
import com.vn.auth.dto.request.VerifyEmailCodeRequest;
import com.vn.auth.dto.response.AuthResult;

public interface AuthService {

    // Đăng ký tài khoản mới → gửi email xác nhận
    void register(RegistrationRequest request);

    // Xác nhận email qua token
    void verifyEmail(String token);

    // Xác nhận email bằng mã một lần gồm 9 chữ số
    void verifyEmailCode(VerifyEmailCodeRequest request);

    // Đăng nhập → trả access token, refresh token lưu riêng
    AuthResult login(LoginRequest request);

    // Làm mới token → trả access token mới, rotate refresh token
    AuthResult refreshToken(String refreshToken);

    // Gửi lại email xác nhận, có cooldown và giới hạn số lần gửi lại
    void resendVerificationEmail(ResendVerificationRequest request);

    // Đăng xuất → blacklist access token, xóa refresh token
    void logout(String accessToken, Long userId);
}

