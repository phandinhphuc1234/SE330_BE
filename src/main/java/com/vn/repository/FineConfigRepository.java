package com.vn.repository;

import com.vn.entity.FineConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface FineConfigRepository extends JpaRepository<FineConfig, Long> {

    // Lấy cấu hình phạt đang có hiệu lực tại một ngày cụ thể, ưu tiên bản effective_from mới nhất.
    @Query(value = """
            select *
            from fine_configs config
            where config.effective_from <= :date
              and (config.effective_until is null or config.effective_until > :date)
            order by config.effective_from desc
            limit 1
            """, nativeQuery = true)
    Optional<FineConfig> findActiveConfig(@Param("date") LocalDate date);
}
