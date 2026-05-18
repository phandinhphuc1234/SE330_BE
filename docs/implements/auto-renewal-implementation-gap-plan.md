# Auto-renewal Implementation Gap Plan

Tài liệu này đối chiếu `auto-renewal-policy-spec.md` với codebase hiện tại, rồi liệt kê chính xác cần thêm gì, sửa gì và không cần làm gì ở phase đầu.

Mục tiêu: khi bắt đầu implement auto-renewal, có thể đi theo checklist này thay vì phải đọc lại toàn bộ spec.

## 0. Trạng thái sau implement

Các phần trong plan này đã được triển khai:

```text
V16 migration
AUTO_RENEW_* system settings
MAX_RENEWALS_DEFAULT = 2 cho các lượt mượn mới
JobExecutionLog entity/repository/service
AutoRenewalAttempt entity/repository
Auto-renewal result/status enums
BorrowRecordRepository candidate query + lock renewal query
CirculationSettingService auto-renew settings
CirculationPolicyService validateAutoRenewal
RenewalUseCase applyRenewal helper
AutoRenewalJob/Service/Processor/AttemptRecorder
EmailService auto-renew success/failure methods
Thymeleaf email templates
Unit tests cho processor, policy và job log service
```

Chưa triển khai ở phase này:

```text
Admin endpoint chạy job thủ công
Admin API xem job/attempt logs
ShedLock hoặc distributed scheduler lock
Spring Batch
```

## 1. Tóm tắt quyết định

Nên implement auto-renewal theo bản "đủ gây ấn tượng nhưng không quá production-heavy":

```text
Có:
- Scheduled job chạy hằng ngày.
- Setting bật/tắt và cấu hình job.
- Reuse renewal policy.
- Job execution log.
- Auto-renewal attempt log từng borrow.
- Email success/failure.
- Transaction riêng cho từng borrow.
- Lock BorrowRecord khi process một auto-renewal attempt.

Chưa cần:
- Spring Batch.
- ShedLock.
- Redis distributed lock.
- Queue.
- WebSocket progress.
- Admin UI chỉnh settings.
```

Lý do: codebase hiện đã có circulation policy, hold queue, renewal, email, system settings và schema `job_execution_logs`. Auto-renewal nối vào các phần này khá tự nhiên.

## 2. Những phần codebase đã có

### 2.1. BorrowRecord đã đủ field core

File:

```text
src/main/java/com/vn/entity/BorrowRecord.java
```

Đã có:

```java
private Instant borrowedAt;
private Instant dueDate;
private Instant returnedAt;
private BigDecimal fineAmount;
private BorrowStatus status;
private Integer renewCount;
private Integer maxRenewalsAtCheckout;
```

Auto-renewal có thể dùng ngay:

```text
dueDate                 -> ngày đến hạn hiện tại
status                  -> chỉ auto-renew khi BORROWED
renewCount              -> số lần đã gia hạn
maxRenewalsAtCheckout   -> giới hạn số lần gia hạn
```

Không cần thêm field vào `BorrowRecord` ở phase đầu.

### 2.2. Renewal logic đã có

File:

```text
src/main/java/com/vn/service/impl/circulation/RenewalUseCase.java
```

Hiện renewal đang làm:

```text
1. Load BorrowRecord.
2. Validate bằng CirculationPolicyService.
3. Tính requestedDays.
4. dueDate = oldDueDate + requestedDays.
5. renewCount += 1.
6. Save.
```

Auto-renewal nên reuse logic "apply renewal" này để tránh duplicate.

### 2.3. Renewal policy đã có phần lớn rule

File:

```text
src/main/java/com/vn/service/impl/circulation/CirculationPolicyService.java
```

Đã có:

```java
public void assertRenewalAllowed(Long actorId, boolean staffFlow, BorrowRecord borrow)
```

Rule đã có:

```text
Borrower phải là MEMBER.
Borrower phải ACTIVE.
Membership chưa hết hạn.
Borrow status phải renewable.
Không renew overdue nếu ALLOW_RENEW_OVERDUE=false.
renewCount < maxRenewalsAtCheckout.
Không renew nếu có reservation active trên cùng book.
```

Đây là nền tốt cho auto-renewal.

### 2.4. System settings đã có

File:

```text
src/main/java/com/vn/service/impl/circulation/CirculationSettingService.java
```

Đã đọc được:

```text
BORROW_DAYS_DEFAULT
RENEWAL_DAYS_DEFAULT
MAX_RENEWALS_DEFAULT
ALLOW_RENEW_OVERDUE
HOLD_PICKUP_DAYS_DEFAULT
```

Auto-renewal cần thêm setting mới, nhưng pattern đọc setting đã có.

### 2.5. Hold queue đã có status active

File:

```text
src/main/java/com/vn/enums/ReservationStatus.java
```

Đã có:

```java
public static List<ReservationStatus> activeStatuses() {
    return List.of(WAITING, NOTIFIED, READY_FOR_PICKUP);
}
```

File:

```text
src/main/java/com/vn/repository/ReservationRepository.java
```

Đã có:

```java
boolean existsByBookIdAndStatusIn(Long bookId, Collection<ReservationStatus> statuses);
```

Auto-renewal có thể reuse để chặn khi có hold.

### 2.6. Email infrastructure đã có

Files:

```text
src/main/java/com/vn/service/EmailService.java
src/main/java/com/vn/service/impl/EmailServiceImpl.java
```

Đã có:

```java
void sendVerificationEmail(...)
```

`EmailServiceImpl` đã dùng:

```text
JavaMailSender
Thymeleaf TemplateEngine
@Async
structured log
```

Auto-renewal chỉ cần mở rộng interface và thêm templates.

### 2.7. job_execution_logs schema đã có

File:

```text
src/main/resources/db/migration/V13__circulation_core_upgrade.sql
```

Đã có table:

```sql
CREATE TABLE IF NOT EXISTS job_execution_logs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(30) NOT NULL,
    total_processed INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    CONSTRAINT chk_job_execution_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);
```

Nhưng Java entity/repository/service chưa có.

## 3. Những phần còn thiếu

### 3.1. Migration V16

Không được sửa V13 vì đã chạy trên DB. Cần tạo migration mới:

```text
src/main/resources/db/migration/V16__auto_renewal_support.sql
```

Nội dung nên có:

```text
1. Insert auto-renew settings vào system_settings.
2. Tạo table auto_renewal_attempts.
3. Tạo indexes cho attempts.
```

SQL đề xuất:

```sql
INSERT INTO system_settings (key, value, description)
VALUES
    ('AUTO_RENEW_ENABLED', 'false', 'Enable daily automatic renewal job'),
    ('AUTO_RENEW_DAYS_BEFORE_DUE', '1', 'Run auto-renewal for borrows due in this many days'),
    ('AUTO_RENEW_NOTIFY_SUCCESS', 'true', 'Send email when auto-renewal succeeds'),
    ('AUTO_RENEW_NOTIFY_FAILURE', 'true', 'Send email when auto-renewal is blocked'),
    ('AUTO_RENEW_MAX_ITEMS_PER_RUN', '500', 'Maximum borrow records processed per auto-renewal job run')
ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS auto_renewal_attempts (
    id BIGSERIAL PRIMARY KEY,
    borrow_record_id BIGINT NOT NULL REFERENCES borrow_records(id),
    member_id BIGINT NOT NULL REFERENCES members(id),
    book_copy_id BIGINT NOT NULL REFERENCES book_copies(id),
    job_execution_log_id BIGINT REFERENCES job_execution_logs(id),

    attempted_at TIMESTAMP NOT NULL,
    result VARCHAR(30) NOT NULL,
    reason_code VARCHAR(100),
    reason_message TEXT,

    old_due_date TIMESTAMP,
    new_due_date TIMESTAMP,
    renew_count_before INT,
    renew_count_after INT,

    CONSTRAINT chk_auto_renewal_attempt_result
        CHECK (result IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_auto_renewal_attempt_borrow
    ON auto_renewal_attempts(borrow_record_id, attempted_at DESC);

CREATE INDEX IF NOT EXISTS idx_auto_renewal_attempt_member
    ON auto_renewal_attempts(member_id, attempted_at DESC);

CREATE INDEX IF NOT EXISTS idx_auto_renewal_attempt_job
    ON auto_renewal_attempts(job_execution_log_id);
```

### 3.2. Enum mới

Cần thêm:

```text
src/main/java/com/vn/enums/JobExecutionStatus.java
src/main/java/com/vn/enums/AutoRenewalAttemptResult.java
src/main/java/com/vn/enums/AutoRenewalResultCode.java
```

`JobExecutionStatus`:

```java
RUNNING,
COMPLETED,
FAILED
```

`AutoRenewalAttemptResult`:

```java
SUCCESS,
FAILED
```

`AutoRenewalResultCode`:

```java
SUCCESS,
BORROW_NOT_FOUND,
BORROW_NOT_RENEWABLE_STATUS,
MAX_RENEWALS_REACHED,
BLOCKED_BY_HOLD,
MEMBER_NOT_ACTIVE,
MEMBERSHIP_EXPIRED,
BORROWER_MUST_BE_MEMBER,
BORROW_OVERDUE,
BOOK_COPY_NOT_BORROWED,
BOOK_DELETED,
SYSTEM_ERROR
```

Không nên dùng `ErrorCode` trực tiếp làm reason code cho attempts, vì auto-renewal cần reason ở cấp job/business report, không nhất thiết là HTTP error.

### 3.3. Entity mới

Cần thêm:

```text
src/main/java/com/vn/entity/JobExecutionLog.java
src/main/java/com/vn/entity/AutoRenewalAttempt.java
```

`JobExecutionLog` map với table đã có trong V13.

`AutoRenewalAttempt` map với table mới V16.

Lưu ý:

```text
Không dùng cascade từ attempt sang BorrowRecord/Member/BookCopy.
Attempt là record log, không nên vô tình update domain entity.
```

### 3.4. Repository mới

Cần thêm:

```text
src/main/java/com/vn/repository/JobExecutionLogRepository.java
src/main/java/com/vn/repository/AutoRenewalAttemptRepository.java
```

Phase đầu chỉ cần extends `JpaRepository`.

Sau này nếu làm admin API xem log thì thêm query filter.

### 3.5. BorrowRecordRepository cần thêm query

File:

```text
src/main/java/com/vn/repository/BorrowRecordRepository.java
```

Cần thêm:

```java
@EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
@Query("""
        select borrow
        from BorrowRecord borrow
        where borrow.status = :status
          and borrow.dueDate >= :windowStart
          and borrow.dueDate < :windowEnd
        order by borrow.dueDate asc
        """)
Page<BorrowRecord> findAutoRenewalCandidates(
        @Param("status") BorrowStatus status,
        @Param("windowStart") Instant windowStart,
        @Param("windowEnd") Instant windowEnd,
        Pageable pageable
);
```

Cần thêm lock method:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
@Query("""
        select borrow
        from BorrowRecord borrow
        where borrow.id = :id
        """)
Optional<BorrowRecord> findLockedForRenewalById(@Param("id") Long id);
```

Lý do lock ở auto-renewal:

```text
Auto-renewal có thể chạy cùng lúc với member self-renew hoặc staff renew.
Nếu không lock, có khả năng renewCount bị tăng 2 lần trong race hiếm.
```

### 3.6. CirculationSettingService cần thêm method

File:

```text
src/main/java/com/vn/service/impl/circulation/CirculationSettingService.java
```

Thêm constants:

```java
private static final boolean DEFAULT_AUTO_RENEW_ENABLED = true;
private static final int DEFAULT_AUTO_RENEW_DAYS_BEFORE_DUE = 1;
private static final boolean DEFAULT_AUTO_RENEW_NOTIFY_SUCCESS = true;
private static final boolean DEFAULT_AUTO_RENEW_NOTIFY_FAILURE = true;
private static final int DEFAULT_AUTO_RENEW_MAX_ITEMS_PER_RUN = 500;
```

Thêm methods:

```java
public boolean isAutoRenewEnabled()
public int getAutoRenewDaysBeforeDue()
public boolean isAutoRenewNotifySuccessEnabled()
public boolean isAutoRenewNotifyFailureEnabled()
public int getAutoRenewMaxItemsPerRun()
```

Nên clamp `AUTO_RENEW_MAX_ITEMS_PER_RUN`:

```text
min = 1
max = 5000
```

Nên clamp `AUTO_RENEW_DAYS_BEFORE_DUE`:

```text
min = 0
max = 7
```

### 3.7. CirculationPolicyService cần thêm auto-renew method

File:

```text
src/main/java/com/vn/service/impl/circulation/CirculationPolicyService.java
```

Thêm:

```java
public AutoRenewalResultCode validateAutoRenewal(BorrowRecord borrow)
```

hoặc:

```java
public void assertAutoRenewalAllowed(BorrowRecord borrow)
```

Mình khuyên dùng `validateAutoRenewal` trả result code cho auto-renewal, vì job cần record failure reason thay vì throw rồi map lại.

Ví dụ:

```java
public AutoRenewalResultCode validateAutoRenewal(BorrowRecord borrow) {
    PolicyBlock borrowerBlock = validateBorrowerAccount(borrow.getMember());
    if (borrowerBlock != null) {
        return mapBorrowerBlock(borrowerBlock.errorCode());
    }
    if (!borrow.getStatus().isRenewable()) {
        return AutoRenewalResultCode.BORROW_NOT_RENEWABLE_STATUS;
    }
    if (borrow.getDueDate().isBefore(Instant.now()) && !circulationSettingService.isRenewOverdueAllowed()) {
        return AutoRenewalResultCode.BORROW_OVERDUE;
    }
    if (borrow.getRenewCount() >= borrow.getMaxRenewalsAtCheckout()) {
        return AutoRenewalResultCode.MAX_RENEWALS_REACHED;
    }
    if (borrow.getBookCopy().getStatus() != BookCopyStatus.BORROWED) {
        return AutoRenewalResultCode.BOOK_COPY_NOT_BORROWED;
    }
    Book book = borrow.getBookCopy().getBook();
    if (book.getDeletedAt() != null) {
        return AutoRenewalResultCode.BOOK_DELETED;
    }
    if (reservationRepository.existsByBookIdAndStatusIn(book.getId(), ReservationStatus.activeStatuses())) {
        return AutoRenewalResultCode.BLOCKED_BY_HOLD;
    }
    return AutoRenewalResultCode.SUCCESS;
}
```

Lý do không chỉ reuse `assertRenewalAllowed`:

```text
assertRenewalAllowed throw AppException và trả lỗi HTTP-oriented.
Auto-renewal cần reason code để lưu attempt và gửi email.
```

### 3.8. RenewalUseCase cần tách apply method

File:

```text
src/main/java/com/vn/service/impl/circulation/RenewalUseCase.java
```

Hiện phần cộng ngày đang nằm trực tiếp trong `renew`.

Nên tách:

```java
public RenewBorrowResponse applyRenewal(BorrowRecord borrow, int renewalDays) {
    Instant oldDueDate = borrow.getDueDate();
    Instant newDueDate = oldDueDate.plus(renewalDays, ChronoUnit.DAYS);
    borrow.setDueDate(newDueDate);
    borrow.setRenewCount(borrow.getRenewCount() + 1);
    borrowRecordRepository.save(borrow);
    return new RenewBorrowResponse(...);
}
```

`renew(...)` sẽ:

```text
load borrow
policy check
resolve requestedDays
call applyRenewal
```

`AutoRenewalProcessor` sẽ:

```text
load locked borrow
auto-renew policy check
call applyRenewal(borrow, RENEWAL_DAYS_DEFAULT)
```

### 3.9. JobExecutionLogService cần thêm

Tạo:

```text
src/main/java/com/vn/service/impl/job/JobExecutionLogService.java
```

Hoặc nếu muốn giữ gần circulation:

```text
src/main/java/com/vn/service/impl/circulation/autorenewal/JobExecutionLogService.java
```

Mình khuyên package riêng:

```text
com.vn.service.impl.job
```

Vì sau này overdue job, idempotency cleanup job cũng dùng chung.

Methods:

```java
JobExecutionLog start(String jobName);
void complete(Long jobId, int totalProcessed, int successCount, int failedCount);
void fail(Long jobId, String errorMessage);
```

### 3.10. Auto-renewal package cần thêm

Tạo package:

```text
src/main/java/com/vn/service/impl/circulation/autorenewal
```

Classes:

```text
AutoRenewalJob
AutoRenewalService
AutoRenewalProcessor
AutoRenewalAttemptRecorder
AutoRenewalJobSummary
AutoRenewalResult
AutoRenewalWindow
```

Có thể dùng record cho value objects:

```java
record AutoRenewalWindow(Instant start, Instant end) {}
record AutoRenewalResult(boolean success, AutoRenewalResultCode code) {}
record AutoRenewalJobSummary(int totalProcessed, int successCount, int failedCount) {}
```

### 3.11. EmailService cần mở rộng

File:

```text
src/main/java/com/vn/service/EmailService.java
src/main/java/com/vn/service/impl/EmailServiceImpl.java
```

Thêm:

```java
void sendAutoRenewalSuccessEmail(...);
void sendAutoRenewalFailureEmail(...);
```

Thêm templates:

```text
src/main/resources/templates/auto-renewal-success.html
src/main/resources/templates/auto-renewal-failure.html
```

Thêm `LogEvent`:

```text
SEND_AUTO_RENEWAL_EMAIL
```

hoặc tách:

```text
SEND_AUTO_RENEWAL_SUCCESS_EMAIL
SEND_AUTO_RENEWAL_FAILURE_EMAIL
```

Mình khuyên một event chung:

```text
SEND_AUTO_RENEWAL_EMAIL
```

và log thêm `notificationType=SUCCESS|FAILURE`.

### 3.12. LogEvent cần thêm

File:

```text
src/main/java/com/vn/logging/LogEvent.java
```

Thêm:

```text
AUTO_RENEWAL_JOB
AUTO_RENEWAL_ATTEMPT
SEND_AUTO_RENEWAL_EMAIL
```

Không cần thêm `ErrorCode` HTTP cho auto-renewal failure reasons, vì đây là job nội bộ, không phải request lỗi trả cho client.

## 4. Flow implement đề xuất

### Phase 1: Schema và support model

Thêm:

```text
V16__auto_renewal_support.sql
JobExecutionStatus
AutoRenewalAttemptResult
AutoRenewalResultCode
JobExecutionLog entity/repository
AutoRenewalAttempt entity/repository
```

Chạy test compile.

### Phase 2: Settings và repository query

Sửa:

```text
CirculationSettingService
BorrowRecordRepository
```

Thêm:

```text
auto-renew settings methods
findAutoRenewalCandidates
findLockedForRenewalById
```

### Phase 3: Policy và renewal apply

Sửa:

```text
CirculationPolicyService
RenewalUseCase
```

Thêm:

```text
validateAutoRenewal
applyRenewal
```

### Phase 4: Auto-renewal services

Thêm:

```text
JobExecutionLogService
AutoRenewalAttemptRecorder
AutoRenewalProcessor
AutoRenewalService
AutoRenewalJob
```

### Phase 5: Email

Sửa:

```text
EmailService
EmailServiceImpl
LogEvent
```

Thêm templates:

```text
auto-renewal-success.html
auto-renewal-failure.html
```

### Phase 6: Tests

Thêm unit tests:

```text
AutoRenewalProcessorTest
AutoRenewalServiceTest
JobExecutionLogServiceTest
CirculationPolicyService auto-renew tests
```

Không cần integration test DB ngay nếu scope đang lớn, nhưng có thể thêm sau.

## 5. Class-by-class design đề xuất

### 5.1. AutoRenewalJob

Package:

```text
com.vn.service.impl.circulation.autorenewal
```

Responsibilities:

```text
1. @Scheduled trigger.
2. Nếu AUTO_RENEW_ENABLED=false thì return.
3. Tạo JobExecutionLog RUNNING.
4. Gọi AutoRenewalService.
5. Mark COMPLETED hoặc FAILED.
```

Pseudo:

```java
@Scheduled(cron = "0 0 7 * * *", zone = "Asia/Bangkok")
public void runDailyAutoRenewal() {
    if (!settingService.isAutoRenewEnabled()) {
        return;
    }
    JobExecutionLog job = jobLogService.start("AUTO_RENEWAL");
    try {
        AutoRenewalJobSummary summary = autoRenewalService.run(job.getId());
        jobLogService.complete(job.getId(), summary.total(), summary.success(), summary.failed());
    } catch (Exception e) {
        jobLogService.fail(job.getId(), e.getMessage());
        log.error(...);
    }
}
```

### 5.2. AutoRenewalService

Responsibilities:

```text
1. Tính window.
2. Query candidates.
3. Loop candidates.
4. Gọi processor.
5. Tổng hợp summary.
```

Điểm quan trọng:

```text
Service không mở transaction dài.
Processor tự mở transaction từng borrow.
```

### 5.3. AutoRenewalProcessor

Responsibilities:

```text
1. @Transactional(REQUIRES_NEW).
2. Lock BorrowRecord.
3. Validate policy.
4. Apply renewal nếu pass.
5. Record attempt.
6. Send email.
7. Return result.
```

Nếu policy fail:

```text
Không throw ra ngoài.
Record FAILED attempt.
Gửi email failure nếu enabled.
Return failed result.
```

Nếu unexpected exception:

```text
Record FAILED/SYSTEM_ERROR nếu có đủ borrow data.
Log error.
Return failed result hoặc rethrow tùy mức lỗi.
```

Khuyến nghị:

```text
Không rethrow lỗi từng borrow ra AutoRenewalService.
Chỉ lỗi hệ thống trước khi có candidate/query mới làm job FAILED.
```

### 5.4. AutoRenewalAttemptRecorder

Responsibilities:

```text
Tạo AutoRenewalAttempt SUCCESS/FAILED.
Giữ logic ghi attempt khỏi processor.
```

Methods:

```java
void recordSuccess(BorrowRecord borrow, Long jobId, Instant oldDueDate, Instant newDueDate, int renewCountBefore, int renewCountAfter)
void recordFailure(BorrowRecord borrow, Long jobId, AutoRenewalResultCode code, String message)
```

## 6. Những sửa đổi cần cẩn thận

### 6.1. Đừng dùng staffFlow=true cho auto-renewal

Không nên:

```java
circulationPolicyService.assertRenewalAllowed(systemActorId, true, borrow);
```

Vì:

```text
staffFlow=true là staff renew hộ.
Auto-renewal là system job.
Dùng chung boolean này khiến code mơ hồ.
```

Nên có method riêng:

```java
validateAutoRenewal(borrow)
```

### 6.2. Không reset renewCount

Auto-renewal chỉ tăng:

```text
renewCount += 1
```

Không bao giờ reset về 0.

Reset chỉ xảy ra về mặt nghiệp vụ khi:

```text
Staff checkin borrow cũ.
Staff checkout lại borrow mới.
BorrowRecord mới có renewCount = 0.
```

### 6.3. Không tự động checkout lại

Khi hết max renewals:

```text
Auto-renewal fail.
Gửi email yêu cầu trả sách.
Không tự tạo borrow mới.
```

Lý do:

```text
Cần điểm kiểm tra vật lý ở thư viện.
```

### 6.4. Không gửi email trong transaction nếu muốn rất sạch

Phase đầu có thể gửi email sau khi save attempt trong cùng processor vì `EmailService` async.

Nếu muốn sạch hơn:

```text
Commit transaction trước.
Publish event.
Listener gửi email AFTER_COMMIT.
```

Nhưng phase đầu chưa cần phức tạp. Vì `EmailService` hiện đã async, đủ dùng cho đồ án.

### 6.5. Không cần Idempotency-Key

Auto-renewal không phải HTTP write API.

Không thêm idempotency record cho job.

Chống chạy trùng bằng:

```text
single instance assumption
lock borrow row
renewCount/maxRenewals check
attempt logs
```

## 7. Test plan chi tiết

### 7.1. CirculationPolicyService

Test:

```text
validateAutoRenewal returns SUCCESS when all rules pass.
returns MAX_RENEWALS_REACHED when renewCount == max.
returns BLOCKED_BY_HOLD when reservation exists.
returns MEMBER_NOT_ACTIVE when member inactive.
returns MEMBERSHIP_EXPIRED when membership expired.
returns BORROW_NOT_RENEWABLE_STATUS when status != BORROWED.
returns BOOK_COPY_NOT_BORROWED when copy status != BORROWED.
returns BOOK_DELETED when book deletedAt != null.
```

### 7.2. AutoRenewalProcessor

Test:

```text
processOne success updates dueDate and renewCount.
processOne success records SUCCESS attempt.
processOne success sends success email when setting enabled.
processOne failure does not update borrow.
processOne failure records FAILED attempt.
processOne failure sends failure email when setting enabled.
processOne missing borrow returns failed result.
```

### 7.3. AutoRenewalService

Test:

```text
builds window using Asia/Bangkok.
queries BORROWED candidates only.
uses max items per run.
continues processing when one result fails.
returns correct summary counts.
```

### 7.4. JobExecutionLogService

Test:

```text
start creates RUNNING log.
complete marks COMPLETED and sets counts.
fail marks FAILED and stores errorMessage.
```

### 7.5. EmailService

Test optional:

```text
Can render auto-renewal templates.
Does not throw when mail sender throws MessagingException.
```

Nếu scope test lớn, có thể bỏ email unit test vì email đã có pattern tương tự verification email.

## 8. Không cần sửa API hiện tại

Không cần đổi:

```text
PUT /api/borrows/{borrowId}/extend
PUT /api/staff/borrows/{borrowId}/extend
```

Vẫn giữ:

```text
Member self-renew.
Staff renew hộ.
Auto-renewal chạy nền.
```

Không cần thêm user-facing API ở phase đầu.

Nếu cần demo thủ công:

```text
POST /api/admin/jobs/auto-renewals/run
```

Nhưng endpoint này nên để optional, không làm trước nếu muốn scope sạch.

## 9. Rủi ro hiện tại nếu implement ngay

### 9.1. Job chạy thật khi app start

Nếu `AUTO_RENEW_ENABLED=true`, job có thể chạy hằng ngày khi dev đang chạy app.

Để an toàn trong dev:

```text
AUTO_RENEW_ENABLED=false trong local .env/application.
Seed default trong migration có thể để true hoặc false.
```

Mình khuyên:

```text
Migration seed AUTO_RENEW_ENABLED = false
```

Lý do:

```text
Không làm thay đổi dữ liệu bất ngờ khi bạn chỉ muốn chạy app/test.
Khi demo thì bật setting lên.
```

Spec trước đề xuất true để thể hiện feature bật sẵn, nhưng khi đối chiếu codebase/dev workflow, `false` an toàn hơn.

### 9.2. Email thật bị gửi khi test/dev

Nếu JavaMail đang cấu hình email thật, auto-renewal có thể gửi mail thật.

Nên:

```text
AUTO_RENEW_NOTIFY_SUCCESS=false trong local nếu không muốn spam.
AUTO_RENEW_NOTIFY_FAILURE=false trong local nếu không muốn spam.
```

Hoặc giữ setting true nhưng không bật job.

### 9.3. Timezone phải nhất quán

Fine đang dùng:

```text
Asia/Bangkok
```

Auto-renewal cũng nên dùng:

```text
Asia/Bangkok
```

Không dùng timezone hệ điều hành mặc định.

## 10. Checklist cuối cùng trước khi implement

Trước khi code, xác nhận các quyết định:

```text
1. AUTO_RENEW_ENABLED default là false hay true?
   Khuyến nghị: false cho dev/demo an toàn.

2. Có thêm auto_renewal_attempts không?
   Khuyến nghị: có, vì gây ấn tượng và dễ debug.

3. Có thêm API chạy job thủ công không?
   Khuyến nghị: chưa làm phase đầu, hoặc làm sau để demo.

4. Có gửi email không?
   Khuyến nghị: có service method/template, nhưng job chỉ gửi nếu setting bật.

5. Có dùng ShedLock không?
   Khuyến nghị: chưa dùng, ghi assumption single instance.
```

## 11. Thứ tự commit/PR hợp lý

Nếu chia nhỏ:

```text
Commit 1:
  schema + entities + repositories + enums

Commit 2:
  settings + repository query + policy + renewal apply helper

Commit 3:
  job log service + auto-renewal service/processor/job

Commit 4:
  email templates + email service methods

Commit 5:
  tests
```

Nếu làm trong một lần cho đồ án thì vẫn được, nhưng nên giữ file/module rõ ràng để review dễ.

## 12. Kết luận

So với codebase hiện tại, auto-renewal cần thêm khá nhiều file nhưng không phải là refactor phá kiến trúc. Nó là một extension tự nhiên của circulation module.

Các phần đã có đủ tốt:

```text
BorrowRecord renewal fields
RenewalUseCase
CirculationPolicyService
CirculationSettingService
Reservation active statuses
Email infrastructure
job_execution_logs schema
```

Các phần cần thêm chính:

```text
V16 migration
JobExecutionLog entity/repository/service
AutoRenewalAttempt entity/repository
AutoRenewal enums/result codes
BorrowRecordRepository candidate + lock queries
CirculationSettingService auto-renew settings
CirculationPolicyService validateAutoRenewal
RenewalUseCase applyRenewal helper
AutoRenewalJob/Service/Processor/Recorder
Email methods/templates
Tests
```

Đây là một feature đáng làm vì nó thể hiện được:

```text
real-world library policy
scheduled job
config-driven behavior
transaction per item
traceable job history
notification
policy reuse
```
