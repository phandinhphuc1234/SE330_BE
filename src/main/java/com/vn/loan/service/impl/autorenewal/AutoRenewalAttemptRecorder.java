package com.vn.loan.service.impl.autorenewal;

import com.vn.loan.entity.AutoRenewalAttempt;
import com.vn.loan.entity.BorrowRecord;
import com.vn.loan.entity.JobExecutionLog;
import com.vn.loan.enums.AutoRenewalAttemptResult;
import com.vn.loan.enums.AutoRenewalResultCode;
import com.vn.loan.repository.AutoRenewalAttemptRepository;
import com.vn.loan.repository.JobExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AutoRenewalAttemptRecorder {

    private final AutoRenewalAttemptRepository autoRenewalAttemptRepository;
    private final JobExecutionLogRepository jobExecutionLogRepository;

    // Chức năng: lưu lịch sử auto-renewal thành công của một lượt mượn.
    public AutoRenewalAttempt recordSuccess(BorrowRecord borrow,
                                            Long jobLogId,
                                            Instant attemptedAt,
                                            Instant oldDueDate,
                                            Instant newDueDate,
                                            int renewCountBefore,
                                            int renewCountAfter) {
        return autoRenewalAttemptRepository.save(baseAttempt(borrow, jobLogId, attemptedAt)
                .result(AutoRenewalAttemptResult.SUCCESS)
                .reasonCode(AutoRenewalResultCode.SUCCESS.name())
                .reasonMessage(AutoRenewalResultCode.SUCCESS.defaultMessage())
                .oldDueDate(oldDueDate)
                .newDueDate(newDueDate)
                .renewCountBefore(renewCountBefore)
                .renewCountAfter(renewCountAfter)
                .build());
    }

    // Chức năng: lưu lịch sử auto-renewal bị chặn để staff trace được lý do.
    public AutoRenewalAttempt recordFailure(BorrowRecord borrow,
                                            Long jobLogId,
                                            Instant attemptedAt,
                                            AutoRenewalResultCode code) {
        return autoRenewalAttemptRepository.save(baseAttempt(borrow, jobLogId, attemptedAt)
                .result(AutoRenewalAttemptResult.FAILED)
                .reasonCode(code.name())
                .reasonMessage(code.defaultMessage())
                .oldDueDate(borrow.getDueDate())
                .newDueDate(null)
                .renewCountBefore(borrow.getRenewCount())
                .renewCountAfter(borrow.getRenewCount())
                .build());
    }

    private AutoRenewalAttempt.AutoRenewalAttemptBuilder baseAttempt(BorrowRecord borrow,
                                                                     Long jobLogId,
                                                                     Instant attemptedAt) {
        return AutoRenewalAttempt.builder()
                .borrowRecord(borrow)
                .member(borrow.getMember())
                .bookCopy(borrow.getBookCopy())
                .jobExecutionLog(getJobLog(jobLogId))
                .attemptedAt(attemptedAt);
    }

    private JobExecutionLog getJobLog(Long jobLogId) {
        if (jobLogId == null) {
            return null;
        }
        return jobExecutionLogRepository.findById(jobLogId).orElse(null);
    }
}
