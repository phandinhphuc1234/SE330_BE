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

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    // URL gốc để tạo link xác nhận, ví dụ: http://localhost:8080
    @Value("${app.verification.base-url}")
    private String baseUrl;

    // Resend SMTP username is the credential value "resend"; the email From address is configured separately.
    @Value("${app.mail.from}")
    private String fromEmail;

    @Override
//    @Async bảo Spring rằng:
//    Method này đừng chạy trên request thread hiện tại. Hãy giao nó cho một thread khác trong executor.
    @Async
    public void sendVerificationEmail(Long memberId, String toEmail, String fullName, String token) {
        try {
            // 1. Tạo link xác nhận
            String verifyLink = buildVerificationLink(token);

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
    // Tạo URL + endpoint verify + token
    private String buildVerificationLink(String token) {
        String normalizedBaseUrl = baseUrl.strip();
        if (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }

        return UriComponentsBuilder.fromUriString(normalizedBaseUrl)
                .path("/api/auth/verify-email")
                .queryParam("token", token)
                .build()
                .toUriString();
    }

    // Gửi email khi cronjob tự động gia hạn thành công
    @Override
    @Async
    public void sendAutoRenewalSuccessEmail(Long memberId,
                                            String toEmail,
                                            String fullName,
                                            String bookTitle,
                                            String barcode,
                                            Instant oldDueDate,
                                            Instant newDueDate,
                                            Integer renewCount,
                                            Integer maxRenewals) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("bookTitle", bookTitle);
        context.setVariable("barcode", barcode);
        context.setVariable("oldDueDate", oldDueDate);
        context.setVariable("newDueDate", newDueDate);
        context.setVariable("renewCount", renewCount);
        context.setVariable("maxRenewals", maxRenewals);

        sendAutoRenewalEmail(
                memberId,
                toEmail,
                "SUCCESS",
                "Gia hạn sách tự động thành công - Hệ thống Quản lý Thư viện",
                "auto-renewal-success",
                context
        );
    }
    // Gửi mail thông báo renew thật bại
    @Override
    @Async
    public void sendAutoRenewalFailureEmail(Long memberId,
                                            String toEmail,
                                            String fullName,
                                            String bookTitle,
                                            String barcode,
                                            Instant dueDate,
                                            String reasonCode,
                                            String reasonMessage) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("bookTitle", bookTitle);
        context.setVariable("barcode", barcode);
        context.setVariable("dueDate", dueDate);
        context.setVariable("reasonCode", reasonCode);
        context.setVariable("reasonMessage", reasonMessage);

        sendAutoRenewalEmail(
                memberId,
                toEmail,
                "FAILURE",
                "Không thể tự động gia hạn sách - Hệ thống Quản lý Thư viện",
                "auto-renewal-failure",
                context
        );
    }

    // Gửi email nhắc bạn đọc chuẩn bị trả sách trước hạn cấu hình của thư viện.
    @Override
    @Async
    public void sendDueSoonReminderEmail(Long memberId,
                                         String toEmail,
                                         String fullName,
                                         String bookTitle,
                                         String barcode,
                                         Instant dueDate) {
        try {
            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("bookTitle", bookTitle);
            context.setVariable("barcode", barcode);
            context.setVariable("dueDate", dueDate);

            String htmlContent = templateEngine.process("due-soon-reminder", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Nhắc trả sách sắp đến hạn - Hệ thống Quản lý Thư viện");
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD bookTitle=\"{}\"",
                    LogEvent.SEND_DUE_SOON_REMINDER_EMAIL,
                    LogResult.SUCCESS,
                    memberId,
                    bookTitle);
        } catch (MessagingException e) {
            log.error("eventType={} result={} memberId={} reason={}",
                    LogEvent.SEND_DUE_SOON_REMINDER_EMAIL,
                    LogResult.FAILED,
                    memberId,
                    e.getClass().getSimpleName(),
                    e);
        }
    }

    // Chức năng: render và gửi email auto-renewal theo template success/failure.
    private void sendAutoRenewalEmail(Long memberId,
                                      String toEmail,
                                      String notificationType,
                                      String subject,
                                      String template,
                                      Context context) {
        try {
        //
            String htmlContent = templateEngine.process(template, context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("eventType={} result={} memberId={} notificationType={}",
                    LogEvent.SEND_AUTO_RENEWAL_EMAIL, LogResult.SUCCESS, memberId, notificationType);
        } catch (MessagingException e) {
            log.error("eventType={} result={} memberId={} notificationType={} reason={}",
                    LogEvent.SEND_AUTO_RENEWAL_EMAIL,
                    LogResult.FAILED,
                    memberId,
                    notificationType,
                    e.getClass().getSimpleName(),
                    e);
        }
    }
}
