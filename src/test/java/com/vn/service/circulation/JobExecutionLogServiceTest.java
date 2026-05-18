package com.vn.service.circulation;

import com.vn.entity.JobExecutionLog;
import com.vn.enums.JobExecutionStatus;
import com.vn.repository.JobExecutionLogRepository;
import com.vn.service.impl.job.JobExecutionLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobExecutionLogServiceTest {

    @Mock
    private JobExecutionLogRepository jobExecutionLogRepository;

    private JobExecutionLogService jobExecutionLogService;

    @BeforeEach
    void setUp() {
        jobExecutionLogService = new JobExecutionLogService(jobExecutionLogRepository);
    }

    @Test
    void start_shouldCreateRunningJobLog() {
        when(jobExecutionLogRepository.save(any(JobExecutionLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobExecutionLog job = jobExecutionLogService.start("AUTO_RENEWAL");

        assertThat(job.getJobName()).isEqualTo("AUTO_RENEWAL");
        assertThat(job.getStatus()).isEqualTo(JobExecutionStatus.RUNNING);
        assertThat(job.getTotalProcessed()).isZero();
        assertThat(job.getStartedAt()).isNotNull();
    }

    @Test
    void complete_shouldMarkJobCompletedWithCounts() {
        JobExecutionLog job = runningJob();
        when(jobExecutionLogRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jobExecutionLogRepository.save(any(JobExecutionLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        jobExecutionLogService.complete(1L, 10, 8, 2);

        assertThat(job.getStatus()).isEqualTo(JobExecutionStatus.COMPLETED);
        assertThat(job.getTotalProcessed()).isEqualTo(10);
        assertThat(job.getSuccessCount()).isEqualTo(8);
        assertThat(job.getFailedCount()).isEqualTo(2);
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void fail_shouldMarkJobFailedWithErrorMessage() {
        JobExecutionLog job = runningJob();
        when(jobExecutionLogRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jobExecutionLogRepository.save(any(JobExecutionLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        jobExecutionLogService.fail(1L, "Database unavailable");

        assertThat(job.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("Database unavailable");
        assertThat(job.getFinishedAt()).isNotNull();
    }

    private JobExecutionLog runningJob() {
        return JobExecutionLog.builder()
                .id(1L)
                .jobName("AUTO_RENEWAL")
                .status(JobExecutionStatus.RUNNING)
                .totalProcessed(0)
                .successCount(0)
                .failedCount(0)
                .build();
    }
}
