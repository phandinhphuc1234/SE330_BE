package com.vn.dto.ebook.request;

import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookAccessType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request cập nhật metadata/policy của ebook.
 * Không dùng DTO này để thay file PDF; thay file vẫn đi qua API upload ebook riêng.
 */
public record UpdateBookEbookRequest(
        // Số lượt đọc/mượn ebook đồng thời mà thư viện cho phép.
        @Min(value = 1, message = "Số license đọc đồng thời phải lớn hơn 0")
        Integer maxConcurrentLoans,

        // Thời hạn mặc định của một lượt mượn ebook.
        @Min(value = 1, message = "Thời hạn mượn ebook phải lớn hơn 0 ngày")
        Integer loanDurationDays,

        // MVP chỉ hỗ trợ FREE hoặc PAID để giữ logic thanh toán đơn giản.
        EbookAccessType accessType,

        // Phí truy cập/thuê quyền đọc; FREE phải là 0, PAID phải lớn hơn 0.
        @DecimalMin(value = "0.00", message = "Phí truy cập ebook không được âm")
        @Digits(integer = 10, fraction = 2, message = "Phí truy cập ebook tối đa 10 chữ số phần nguyên và 2 chữ số thập phân")
        BigDecimal accessFee,

        // Mã tiền tệ hiển thị và dùng cho thanh toán, ví dụ VND.
        @Size(max = 10, message = "Mã tiền tệ tối đa 10 ký tự")
        String currency,

        // Thời hạn quyền đọc sau khi user mượn/trả phí thành công.
        @Min(value = 1, message = "Thời hạn quyền đọc ebook phải lớn hơn 0 ngày")
        Integer accessDurationDays,

        // Staff chỉ nên bật/tắt ebook qua ACTIVE/INACTIVE; các trạng thái kỹ thuật khác do hệ thống quản lý.
        BookEbookStatus status
) {
}
