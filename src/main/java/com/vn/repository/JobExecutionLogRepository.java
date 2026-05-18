package com.vn.repository;

import com.vn.entity.JobExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionLogRepository extends JpaRepository<JobExecutionLog, Long> {
}
