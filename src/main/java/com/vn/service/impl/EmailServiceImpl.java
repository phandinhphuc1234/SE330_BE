package com.vn.service.impl;

import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    // URL gốc để tạo link xác nhận, ví dụ: http://localhost:8080
    @Value("${app.verification.base-url}")
    private String baseUrl;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Override
//    @Async bảo Spring rằng:
//    Method này đừng chạy trên request thread hiện tại. Hãy giao nó cho một thread khác trong executor.
    @Async
    public void sendVerificationEmail(Long memberId, String toEmail, String fullName, String token) {
        try {
            // 1. Tạo link xác nhận
            String verifyLink = baseUrl + "/api/auth/verify-email?token=" + token;

            // 2. Chuẩn bị dữ liệu cho template
            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("verifyLink", verifyLink);

            // 3. Render HTML từ Thymeleaf template
            String htmlContent = templateEngine.process("email-verification", context);

            // 4. Tạo và gửi email
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Xác nhận tài khoản - Hệ thống Quản lý Thư viện");
            helper.setText(htmlContent, true); // true = HTML

            mailSender.send(message);
            log.info("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION",
                    LogEvent.SEND_VERIFICATION_EMAIL, LogResult.SUCCESS, memberId);

        } catch (MessagingException e) {
            log.error("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION reason={}",
                    LogEvent.SEND_VERIFICATION_EMAIL, LogResult.FAILED, memberId, e.getClass().getSimpleName(), e);
        }
    }
}

