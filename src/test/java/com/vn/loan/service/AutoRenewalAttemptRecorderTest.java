package com.vn.loan.service;

import com.vn.loan.repository.AutoRenewalAttemptRepository;
import com.vn.loan.repository.JobExecutionLogRepository;
import com.vn.loan.service.impl.autorenewal.AutoRenewalAttemptRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AutoRenewalAttemptRecorderTest {
    @Test
    void missingFailureReason_shouldFailBeforePersistingAttempt() {
        AutoRenewalAttemptRepository attempts = mock(AutoRenewalAttemptRepository.class);
        JobExecutionLogRepository jobs = mock(JobExecutionLogRepository.class);
        AutoRenewalAttemptRecorder recorder = new AutoRenewalAttemptRecorder(attempts, jobs);

        assertThatThrownBy(() -> recorder.recordFailure(null, null, Instant.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auto-renewal failure reason must be provided");
        verifyNoInteractions(attempts, jobs);
    }
}
