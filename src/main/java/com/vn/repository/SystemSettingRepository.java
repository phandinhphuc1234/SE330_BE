package com.vn.repository;

import com.vn.entity.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

// Repository key-value cho cấu hình hệ thống; CRUD mặc định của JpaRepository là đủ cho bảng này.
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
