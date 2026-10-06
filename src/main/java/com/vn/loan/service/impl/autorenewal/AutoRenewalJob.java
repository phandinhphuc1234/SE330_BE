package com.vn.loan.service.impl.autorenewal;

import com.vn.loan.entity.JobExecutionLog;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import com.vn.loan.service.job.JobExecutionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoRenewalJob {

    private static final String JOB_NAME = "AUTO_RENEWAL";

    private final CirculationSettingService circulationSettingService;
    private final AutoRenewalService autoRenewalService;
    private final JobExecutionLogService jobExecutionLogService;

    // Chức năng: scheduled job tự động gia hạn các borrow sắp đến hạn nếu policy cho phép.
    @Scheduled(cron = "0 0 7 * * *", zone = "${app.time.zone}")
    public void runDailyAutoRenewal() {
        if (!circulationSettingService.isAutoRenewEnabled()) {
            return;
        }

        JobExecutionLog jobLog = jobExecutionLogService.start(JOB_NAME);
        try {
            AutoRenewalJobSummary summary = autoRenewalService.runDailyAutoRenewal(jobLog.getId());
            jobExecutionLogService.complete(
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount()
            );
            log.info("eventType={} result={} jobId={} totalProcessed={} successCount={} failedCount={}",
                    LogEvent.AUTO_RENEWAL_JOB,
                    LogResult.SUCCESS,
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount());
        } catch (Exception e) {
            jobExecutionLogService.fail(jobLog.getId(), e.getMessage());
            log.error("eventType={} result={} jobId={} reason={}",
                    LogEvent.AUTO_RENEWAL_JOB, LogResult.FAILED, jobLog.getId(), e.getClass().getSimpleName(), e);
        }
    }
}
