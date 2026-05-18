package com.vn.service.impl.job;

import com.vn.entity.JobExecutionLog;
import com.vn.enums.JobExecutionStatus;
import com.vn.repository.JobExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class JobExecutionLogService {

    private final JobExecutionLogRepository jobExecutionLogRepository;

    // Chức năng: tạo log RUNNING khi một background job bắt đầu.
    @Transactional
    public JobExecutionLog start(String jobName) {
        return jobExecutionLogRepository.save(JobExecutionLog.builder()
                .jobName(jobName)
                .startedAt(Instant.now())
                .status(JobExecutionStatus.RUNNING)
                .totalProcessed(0)
                .successCount(0)
                .failedCount(0)
                .build());
    }

    // Chức năng: đánh dấu job hoàn tất và lưu số lượng record đã xử lý.
    @Transactional
    public void complete(Long jobId, int totalProcessed, int successCount, int failedCount) {
        jobExecutionLogRepository.findById(jobId).ifPresent(job -> {
            job.setFinishedAt(Instant.now());
            job.setStatus(JobExecutionStatus.COMPLETED);
            job.setTotalProcessed(totalProcessed);
            job.setSuccessCount(successCount);
            job.setFailedCount(failedCount);
            jobExecutionLogRepository.save(job);
        });
    }

    // Chức năng: đánh dấu job lỗi ở cấp job, khác với lỗi từng record riêng lẻ.
    @Transactional
    public void fail(Long jobId, String errorMessage) {
        jobExecutionLogRepository.findById(jobId).ifPresent(job -> {
            job.setFinishedAt(Instant.now());
            job.setStatus(JobExecutionStatus.FAILED);
            job.setErrorMessage(truncate(errorMessage));
            jobExecutionLogRepository.save(job);
        });
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
