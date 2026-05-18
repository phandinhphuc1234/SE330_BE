package com.vn.service;

import java.time.Instant;

public interface EmailService {

    // Gửi email xác nhận tài khoản
    void sendVerificationEmail(Long memberId, String toEmail, String fullName, String token);

    // Gửi email thông báo hệ thống đã tự động gia hạn sách thành công.
    void sendAutoRenewalSuccessEmail(Long memberId,
                                     String toEmail,
                                     String fullName,
                                     String bookTitle,
                                     String barcode,
                                     Instant oldDueDate,
                                     Instant newDueDate,
                                     Integer renewCount,
                                     Integer maxRenewals);

    // Gửi email thông báo sách không thể tự động gia hạn và cần được trả đúng hạn.
    void sendAutoRenewalFailureEmail(Long memberId,
                                     String toEmail,
                                     String fullName,
                                     String bookTitle,
                                     String barcode,
                                     Instant dueDate,
                                     String reasonCode,
                                     String reasonMessage);
}
