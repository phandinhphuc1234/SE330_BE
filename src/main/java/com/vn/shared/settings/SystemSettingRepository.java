package com.vn.shared.settings;

import com.vn.shared.settings.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

// Repository key-value cho cấu hình hệ thống; CRUD mặc định của JpaRepository là đủ cho bảng này.
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
