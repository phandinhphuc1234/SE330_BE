package com.vn.enums;

/**
 * Trạng thái của một lượt mượn ebook.
 *
 * ACTIVE   – đang trong hạn, member được phép đọc
 * EXPIRED  – quá hạn, link/token đọc đã bị thu hồi (set bởi scheduler)
 * RETURNED – member chủ động trả sớm trước hạn
 */
public enum EbookLoanStatus {
    ACTIVE,
    EXPIRED,
    RETURNED
}
