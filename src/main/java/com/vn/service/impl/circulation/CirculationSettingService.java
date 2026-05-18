package com.vn.service.impl.circulation;

import com.vn.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CirculationSettingService {

    private static final int DEFAULT_BORROW_DAYS = 14;
    private static final int DEFAULT_RENEWAL_DAYS = 7;
    private static final int DEFAULT_MAX_RENEWALS = 1;
    private static final int DEFAULT_HOLD_PICKUP_DAYS = 3;

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
}
