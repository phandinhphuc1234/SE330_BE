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
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.password-reset.base-url}")
    private String passwordResetBaseUrl;

    // Resend SMTP username is the credential value "resend"; the email From address is configured separately.
    @Value("${app.mail.from}")
    private String fromEmail;

    @Override
//    @Async bảo Spring rằng:
//    Method này đừng chạy trên request thread hiện tại. Hãy giao nó cho một thread khác trong executor.
    @Async
    public void sendVerificationEmail(Long memberId, String toEmail, String fullName, String verificationCode) {
        try {
            // Chỉ đưa mã rõ vào email. Database lưu bản BCrypt hash của mã này.
            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("verificationCode", verificationCode);
            context.setVariable("expiryMinutes", 10);

            // 3. Render HTML từ Thymeleaf template
            String htmlContent = templateEngine.process("email-verification", context);

            // 4. Tạo và gửi email
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Mã xác thực tài khoản - Hệ thống Quản lý Thư viện");
            helper.setText(htmlContent, true); // true = HTML

            mailSender.send(message);
            log.info("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION",
                    LogEvent.SEND_VERIFICATION_EMAIL, LogResult.SUCCESS, memberId);

        } catch (MessagingException e) {
            log.error("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION reason={}",
                    LogEvent.SEND_VERIFICATION_EMAIL, LogResult.FAILED, memberId, e.getClass().getSimpleName(), e);
        }
    }
    @Override
    @Async
    public void sendPasswordResetEmail(Long memberId, String toEmail, String fullName, String token) {
        try {
            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("resetLink", buildPasswordResetLink(token));

            String htmlContent = templateEngine.process("password-reset", context);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Đặt lại mật khẩu - Hệ thống Quản lý Thư viện");
            helper.setText(htmlContent, true);
            mailSender.send(message);

            log.info("eventType={} result={} memberId={} entityType=PASSWORD_RESET_EMAIL",
                    LogEvent.PASSWORD_RESET_REQUEST, LogResult.SUCCESS, memberId);
        } catch (MessagingException e) {
            log.error("eventType={} result={} memberId={} entityType=PASSWORD_RESET_EMAIL reason={}",
                    LogEvent.PASSWORD_RESET_REQUEST, LogResult.FAILED, memberId,
                    e.getClass().getSimpleName(), e);
        }
    }

    private String buildPasswordResetLink(String token) {
        return UriComponentsBuilder.fromUriString(passwordResetBaseUrl.strip())
                .queryParam("token", token)
                .build()
                .toUriString();
    }

}
