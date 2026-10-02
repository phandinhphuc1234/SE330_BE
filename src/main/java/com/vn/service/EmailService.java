package com.vn.service;

public interface EmailService {

    // Gửi email xác nhận tài khoản
    void sendVerificationEmail(Long memberId, String toEmail, String fullName, String verificationCode);

    // Gửi liên kết một lần để đặt lại mật khẩu; token thô không được lưu trong database.
    void sendPasswordResetEmail(Long memberId, String toEmail, String fullName, String token);

}
