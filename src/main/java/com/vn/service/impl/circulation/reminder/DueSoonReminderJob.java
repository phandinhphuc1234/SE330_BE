package com.vn.service.impl.circulation.reminder;

import com.vn.entity.JobExecutionLog;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import com.vn.service.impl.job.JobExecutionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DueSoonReminderJob {

    private static final String JOB_NAME = "DUE_SOON_REMINDER";

    private final CirculationSettingService circulationSettingService;
    private final DueSoonReminderService dueSoonReminderService;
    private final JobExecutionLogService jobExecutionLogService;

    // Chức năng: scheduled job hằng ngày gửi reminder cho các lượt mượn sắp đến hạn trả.
    @Scheduled(cron = "0 30 7 * * *", zone = "Asia/Bangkok")
    public void runDailyDueSoonReminder() {
        if (!circulationSettingService.isDueSoonReminderEnabled()) {
            return;
        }

        JobExecutionLog jobLog = jobExecutionLogService.start(JOB_NAME);
        try {
            DueSoonReminderJobSummary summary = dueSoonReminderService.sendDueSoonReminders();
            jobExecutionLogService.complete(
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount()
            );
            log.info("eventType={} result={} jobId={} totalProcessed={} successCount={} failedCount={}",
                    LogEvent.DUE_SOON_REMINDER_JOB,
                    LogResult.SUCCESS,
                    jobLog.getId(),
                    summary.totalProcessed(),
                    summary.successCount(),
                    summary.failedCount());
        } catch (Exception e) {
            jobExecutionLogService.fail(jobLog.getId(), e.getMessage());
            log.error("eventType={} result={} jobId={} reason={}",
                    LogEvent.DUE_SOON_REMINDER_JOB, LogResult.FAILED, jobLog.getId(), e.getClass().getSimpleName(), e);
        }
    }
}
