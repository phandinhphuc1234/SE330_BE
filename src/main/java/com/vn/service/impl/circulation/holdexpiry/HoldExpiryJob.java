package com.vn.service.impl.circulation.holdexpiry;

import com.vn.entity.JobExecutionLog;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.service.impl.job.JobExecutionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryJob {

    private static final String JOB_NAME = "EXPIRE_READY_HOLDS";

    private final HoldExpiryService holdExpiryService;
    private final JobExecutionLogService jobExecutionLogService;

    // Chức năng: scheduled job hằng ngày expire các hold quá hạn lấy sách.
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Bangkok")
    public void runDailyExpireReadyHolds() {
        JobExecutionLog jobLog = jobExecutionLogService.start(JOB_NAME);
        try {
            HoldExpiryJobSummary summary = holdExpiryService.expireReadyHolds();
            jobExecutionLogService.complete(
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount()
            );
            log.info("eventType={} result={} jobId={} totalProcessed={} successCount={} failedCount={}",
                    LogEvent.EXPIRE_READY_HOLDS_JOB,
                    LogResult.SUCCESS,
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount());
        } catch (Exception e) {
            jobExecutionLogService.fail(jobLog.getId(), e.getMessage());
            log.error("eventType={} result={} jobId={} reason={}",
                    LogEvent.EXPIRE_READY_HOLDS_JOB, LogResult.FAILED, jobLog.getId(), e.getClass().getSimpleName(), e);
        }
    }
}
