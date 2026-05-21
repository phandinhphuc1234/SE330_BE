package com.vn.service.impl.circulation.policy;

import com.vn.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CirculationSettingService {

    private static final int DEFAULT_BORROW_DAYS = 14;
    private static final int DEFAULT_RENEWAL_DAYS = 7;
    private static final int DEFAULT_MAX_RENEWALS = 2;
    private static final int DEFAULT_HOLD_PICKUP_DAYS = 3;
    private static final boolean DEFAULT_AUTO_RENEW_ENABLED = false;
    private static final int DEFAULT_AUTO_RENEW_DAYS_BEFORE_DUE = 1;
    private static final boolean DEFAULT_AUTO_RENEW_NOTIFY_SUCCESS = true;
    private static final boolean DEFAULT_AUTO_RENEW_NOTIFY_FAILURE = true;
    private static final int DEFAULT_AUTO_RENEW_MAX_ITEMS_PER_RUN = 500;
    private static final boolean DEFAULT_DUE_SOON_REMINDER_ENABLED = true;
    private static final int DEFAULT_DUE_SOON_REMINDER_DAYS_BEFORE_DUE = 2;
    private static final int DEFAULT_DUE_SOON_REMINDER_MAX_ITEMS_PER_RUN = 500;

    private final SystemSettingRepository systemSettingRepository;

    // Chức năng: lấy số ngày mượn mặc định khi tạo một lượt mượn mới.
    public int getBorrowDaysDefault() {
        return getIntSetting("BORROW_DAYS_DEFAULT", DEFAULT_BORROW_DAYS);
    }

    // Chức năng: lấy số ngày gia hạn mặc định khi request không truyền requestedDays.
    public int getRenewalDaysDefault() {
        return getIntSetting("RENEWAL_DAYS_DEFAULT", DEFAULT_RENEWAL_DAYS);
    }

    // Chức năng: lấy số lần gia hạn tối đa tại thời điểm checkout.
    public int getMaxRenewalsDefault() {
        return getIntSetting("MAX_RENEWALS_DEFAULT", DEFAULT_MAX_RENEWALS);
    }

    // Chức năng: xác định hệ thống có cho phép gia hạn khi lượt mượn đã quá hạn hay không.
    public boolean isRenewOverdueAllowed() {
        return getBooleanSetting("ALLOW_RENEW_OVERDUE", false);
    }

    // Chức năng: lấy số ngày thư viện giữ sách trên hold shelf trước khi hold hết hạn.
    public int getHoldPickupDaysDefault() {
        return getIntSetting("HOLD_PICKUP_DAYS_DEFAULT", DEFAULT_HOLD_PICKUP_DAYS);
    }

    // Chức năng: kiểm tra job auto-renewal có đang được bật trong system_settings không.
    public boolean isAutoRenewEnabled() {
        return getBooleanSetting("AUTO_RENEW_ENABLED", DEFAULT_AUTO_RENEW_ENABLED);
    }

    // Chức năng: lấy số ngày trước dueDate mà job sẽ quét để tự động gia hạn.
    public int getAutoRenewDaysBeforeDue() {
        return clamp(getIntSetting("AUTO_RENEW_DAYS_BEFORE_DUE", DEFAULT_AUTO_RENEW_DAYS_BEFORE_DUE), 0, 7);
    }

    // Chức năng: xác định có gửi mail khi auto-renew thành công hay không.
    public boolean isAutoRenewNotifySuccessEnabled() {
        return getBooleanSetting("AUTO_RENEW_NOTIFY_SUCCESS", DEFAULT_AUTO_RENEW_NOTIFY_SUCCESS);
    }

    // Chức năng: xác định có gửi mail khi auto-renew bị chặn hay không.
    public boolean isAutoRenewNotifyFailureEnabled() {
        return getBooleanSetting("AUTO_RENEW_NOTIFY_FAILURE", DEFAULT_AUTO_RENEW_NOTIFY_FAILURE);
    }

    // Chức năng: giới hạn số lượt mượn job xử lý trong một lần chạy để tránh batch quá lớn.
    public int getAutoRenewMaxItemsPerRun() {
        return clamp(getIntSetting("AUTO_RENEW_MAX_ITEMS_PER_RUN", DEFAULT_AUTO_RENEW_MAX_ITEMS_PER_RUN), 1, 5000);
    }

    // Chức năng: kiểm tra job nhắc sắp đến hạn có được bật hay không.
    public boolean isDueSoonReminderEnabled() {
        return getBooleanSetting("DUE_SOON_REMINDER_ENABLED", DEFAULT_DUE_SOON_REMINDER_ENABLED);
    }

    // Chức năng: lấy số ngày trước hạn trả mà hệ thống sẽ gửi reminder.
    public int getDueSoonReminderDaysBeforeDue() {
        return clamp(
                getIntSetting("DUE_SOON_REMINDER_DAYS_BEFORE_DUE", DEFAULT_DUE_SOON_REMINDER_DAYS_BEFORE_DUE),
                0,
                14
        );
    }

    // Chức năng: giới hạn số reminder được xử lý trong một lần chạy job.
    public int getDueSoonReminderMaxItemsPerRun() {
        return clamp(
                getIntSetting("DUE_SOON_REMINDER_MAX_ITEMS_PER_RUN", DEFAULT_DUE_SOON_REMINDER_MAX_ITEMS_PER_RUN),
                1,
                5000
        );
    }

    // Chức năng: đọc cấu hình dạng số nguyên từ system_settings, dùng default nếu chưa cấu hình.
    private int getIntSetting(String key, int defaultValue) {
        return systemSettingRepository.findById(key)
                .map(setting -> parseInt(setting.getValue(), defaultValue))
                .orElse(defaultValue);
    }

    // Chức năng: đọc cấu hình bật/tắt từ system_settings, dùng default nếu chưa cấu hình.
    private boolean getBooleanSetting(String key, boolean defaultValue) {
        return systemSettingRepository.findById(key)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(defaultValue);
    }

    // Chức năng: chuyển String sang int an toàn cho các giá trị cấu hình hệ thống.
    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
