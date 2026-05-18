# Flow 6 - Overdue, Reminder Và Scheduled Jobs

## Mục tiêu nghiệp vụ

Overdue là trạng thái sách đã quá hạn trả.

Hệ thống cần:

- Đánh dấu borrow quá hạn.
- Báo cáo cho staff.
- Gửi email nhắc sắp đến hạn.
- Gửi email hold ready.
- Expire hold không đến lấy.

## Kỹ thuật nên dùng

Spring có `@Scheduled` cho job định kỳ. Docs Spring mô tả `@Scheduled` hỗ trợ fixed delay, fixed rate và cron expression.

Trong project này nên dùng:

```java
@Scheduled(cron = "${app.jobs.mark-overdue.cron:0 0 1 * * *}", zone = "Asia/Bangkok")
```

Không hardcode cron trong code, để config qua properties.

## Job 1 - markOverdueBorrows

Mục tiêu:

```text
BorrowRecord status BORROWED và due_date < now -> OVERDUE
```

DB hiện có index:

```sql
CREATE INDEX idx_borrow_overdue
    ON borrow_records(due_date, status)
    WHERE status = 'BORROWED';
```

Index này hợp lý vì job chỉ query borrow đang `BORROWED`.

Flow:

```text
1. Query borrow_records status BORROWED due_date < now theo page/chunk.
2. Update borrow status OVERDUE.
3. Update book_copy status BORROWED hoặc OVERDUE tùy chọn.
4. Ghi audit/system log.
5. Ghi job execution log.
```

Khuyến nghị:

```text
BorrowRecord.status chắc chắn chuyển OVERDUE.
BookCopy.status có thể chuyển OVERDUE nếu mình đã thêm OVERDUE vào BookCopyStatus.
```

Nếu thêm `OVERDUE` vào `BookCopyStatus` như flow return/check-in đề xuất, cần nhớ:

```text
BORROWED và OVERDUE đều là copy đang nằm ngoài kệ.
Delete copy/book phải block cả hai status.
Checkout thường chỉ cho AVAILABLE.
Return phải tìm borrow record mở theo status BORROWED/OVERDUE/LOST.
```

Nếu muốn ít sửa hơn, vẫn có thể chỉ để `BorrowRecord.status = OVERDUE` và giữ `BookCopy.status = BORROWED`. Nhưng bản "đẹp" hơn cho portfolio là thêm status `OVERDUE` và xử lý đầy đủ.

## Job 2 - sendDueSoonReminderEmails

Mục tiêu:

```text
Gửi email trước hạn trả 2 ngày.
```

Flow:

```text
1. Tính target date = today + 2 days.
2. Query borrow_records status BORROWED có dueDate trong ngày đó.
3. Kiểm tra notification_queue hoặc notification_logs để không gửi trùng.
4. Tạo notification in-app.
5. Tạo notification_queue EMAIL.
6. Worker gửi email async.
```

Hiện DB đã có:

- `notifications`
- `notification_queue`

Nhưng chưa có unique constraint chống gửi trùng theo borrowId. Nên thêm bảng `notification_logs` hoặc mở rộng `notification_queue` có `target_type`, `target_id`, `notification_type`.

MVP nhanh:

```sql
ALTER TABLE notification_queue
ADD COLUMN notification_type VARCHAR(100),
ADD COLUMN target_type VARCHAR(100),
ADD COLUMN target_id BIGINT;

CREATE UNIQUE INDEX uq_notification_once
ON notification_queue(notification_type, target_type, target_id, channel);
```

## Job 3 - expireReadyHolds

Với schema hiện tại:

```text
Reservation.status = NOTIFIED
Reservation.expires_at < now
```

Flow:

```text
1. Tìm reservation NOTIFIED expired.
2. Lock reservation/copy.
3. Mark reservation EXPIRED.
4. Nếu còn WAITING cho book: assign copy cho người tiếp theo.
5. Nếu không còn WAITING: copy AVAILABLE, book.availableCopies +1.
6. Ghi audit/job log.
```

Nếu chạy nhiều instance backend, cần tránh nhiều node xử lý cùng một hold. Có 2 hướng:

- MVP một instance: `@Scheduled` là đủ.
- Production nhiều instance: dùng DB lock, `FOR UPDATE SKIP LOCKED`, hoặc ShedLock.

PostgreSQL docs mô tả `SKIP LOCKED` phù hợp kiểu queue consumer vì row đang lock sẽ bị bỏ qua thay vì chờ.

## Job execution log

Nên tạo bảng:

```sql
CREATE TABLE job_execution_logs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(30) NOT NULL,
    total_processed INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message TEXT
);
```

Lý do:

- Biết job có chạy không.
- Biết job xử lý bao nhiêu record.
- Debug email không gửi được.
- Khi demo intern, phần này cho thấy bạn nghĩ đến vận hành.

## Email sau commit

Các event email nên gửi sau transaction commit:

- Checkout receipt.
- Return receipt.
- Due soon reminder.
- Hold ready.

Kỹ thuật:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
public void handle(HoldReadyEvent event) {
    emailService.sendHoldReadyEmail(...);
}
```

Lý do: nếu transaction rollback mà email đã gửi, user nhận thông báo sai.

## Dùng Gmail hiện tại rồi đổi Resend sau có dễ không?

Có, nếu circulation không gọi trực tiếp Gmail/Resend.

Code hiện tại đang dùng:

```text
EmailService
EmailServiceImpl
JavaMailSender
spring.mail.*
```

Spring Boot mail docs nói Spring cung cấp abstraction gửi email qua `JavaMailSender` và auto-config bằng `spring.mail.*`. Vì vậy nếu mình giữ circulation chỉ phụ thuộc vào interface nội bộ:

```java
public interface CirculationNotificationService {
    void sendCheckoutReceipt(Long borrowId);
    void sendDueSoonReminder(Long borrowId);
    void sendHoldReady(Long reservationId);
}
```

thì sau này đổi provider rất dễ:

```text
Gmail SMTP hiện tại -> Resend SMTP:
    chủ yếu đổi spring.mail.host/user/password/from.

Gmail SMTP hiện tại -> Resend API:
    thay implementation gửi mail, không đổi circulation flow.
```

Điểm quan trọng:

```text
Không để CheckoutService/ReturnService biết MAIL_USERNAME, MAIL_PASSWORD, Resend API key.
Không gọi JavaMailSender trực tiếp trong circulation service.
Chỉ publish event hoặc gọi notification service abstraction.
```

Khuyến nghị cho project hiện tại:

```text
Phase 1: tiếp tục JavaMailSender như hiện tại.
Phase 2: đổi config sang Resend SMTP.
Phase 3: nếu cần email id/webhook/retry tốt hơn thì viết Resend API implementation.
```

Nên bổ sung timeout mail vì Spring Boot docs lưu ý timeout SMTP mặc định có thể vô hạn:

```properties
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=5000
spring.mail.properties.mail.smtp.writetimeout=5000
```

## Test nên có

- Job mark overdue chuyển đúng borrow quá hạn.
- Job không đụng borrow returned.
- Due soon reminder không gửi trùng.
- Expire hold có người kế tiếp thì chuyển lượt.
- Expire hold không có người kế tiếp thì copy available.
- Email listener chỉ chạy sau commit.

## Nguồn kỹ thuật

- Spring scheduling: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Spring transaction-bound events: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- PostgreSQL row locking / SKIP LOCKED: https://www.postgresql.org/docs/current/sql-select.html
- Spring Boot Sending Email: https://docs.spring.io/spring-boot/reference/io/email.html
