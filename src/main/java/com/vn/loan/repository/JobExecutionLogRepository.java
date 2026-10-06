package com.vn.loan.repository;

import com.vn.loan.entity.JobExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobExecutionLogRepository extends JpaRepository<JobExecutionLog, Long> {
}
