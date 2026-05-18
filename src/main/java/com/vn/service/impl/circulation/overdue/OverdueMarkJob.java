package com.vn.service.impl.circulation.overdue;

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
public class OverdueMarkJob {

    private static final String JOB_NAME = "MARK_OVERDUE_BORROWS";

    private final OverdueMarkService overdueMarkService;
    private final JobExecutionLogService jobExecutionLogService;

    // Chức năng: scheduled job hằng ngày để hệ thống tự nhận diện các borrow đã quá hạn.
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Bangkok")
    public void runDailyMarkOverdue() {
        JobExecutionLog jobLog = jobExecutionLogService.start(JOB_NAME);
        try {
            OverdueJobSummary summary = overdueMarkService.markOverdueBorrows();
            jobExecutionLogService.complete(
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount()
            );
            log.info("eventType={} result={} jobId={} totalProcessed={} successCount={} failedCount={}",
                    LogEvent.MARK_OVERDUE_JOB,
                    LogResult.SUCCESS,
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount());
        } catch (Exception e) {
            jobExecutionLogService.fail(jobLog.getId(), e.getMessage());
            log.error("eventType={} result={} jobId={} reason={}",
                    LogEvent.MARK_OVERDUE_JOB, LogResult.FAILED, jobLog.getId(), e.getClass().getSimpleName(), e);
        }
    }
}
