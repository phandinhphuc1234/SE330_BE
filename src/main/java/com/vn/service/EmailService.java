package com.vn.service;

public interface EmailService {

    // Gửi email xác nhận tài khoản
    void sendVerificationEmail(Long memberId, String toEmail, String fullName, String token);
}

