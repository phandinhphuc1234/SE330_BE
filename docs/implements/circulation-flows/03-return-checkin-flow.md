# Flow 3 - Return / Check-in / Trả Sách

## Mục tiêu nghiệp vụ

Return/check-in là lúc thư viện nhận lại bản sách vật lý.

Trong project này, return thật nên là staff flow:

```text
MEMBER không tự set sách đã trả.
LIBRARIAN/ADMIN scan barcode và xác nhận check-in.
```

Nếu cần member báo "tôi muốn trả", đó chỉ là request phụ, không đóng borrow record.

## Endpoint đề xuất

```http
POST /api/staff/circulation/checkins
Authorization: Bearer librarian-or-admin-token
Idempotency-Key: uuid
Content-Type: application/json
```

Body:

```json
{
  "itemBarcode": "BC-000101",
  "returnCondition": "GOOD",
  "note": "Returned at front desk"
}
```

## Validate nghiệp vụ

- Actor phải là `LIBRARIAN` hoặc `ADMIN`.
- Copy tồn tại.
- Có borrow record mở cho copy.
- Borrow status là `BORROWED`, `OVERDUE`, hoặc `LOST`.
- Nếu copy bị mark `LOST` nhưng được trả lại, xử lý theo lost item policy.
- `returnCondition` phải hợp lệ: `GOOD`, `DAMAGED`.

## Transaction và lock

Check-in cũng cần lock copy:

```java
@Transactional
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<BookCopy> findByBarcodeIgnoreCaseAndDeletedAtIsNull(String barcode);
```

Sau đó query borrow record mở bằng `bookCopyId`.

Lý do lock: tránh hai staff check-in cùng một barcode đồng thời, hoặc check-in đụng với scheduled job mark lost/overdue.

## Flow xử lý

```text
1. Lock BookCopy theo barcode.
2. Tìm BorrowRecord chưa đóng.
3. Tính returnedAt = now.
4. Tính overdueDays.
5. Nếu quá hạn, tính fine.
6. Set BorrowRecord.status = RETURNED.
7. Set BorrowRecord.returnedAt = now.
8. Nếu returnCondition = DAMAGED:
   - BookCopy.status = DAMAGED.
   - Không tăng availableCopies.
   - Có thể tạo damage fee sau.
9. Nếu sách không damaged:
   - Kiểm tra hold WAITING của book.
   - Nếu có hold, BookCopy.status = ON_HOLD_SHELF.
   - Nếu không có hold, BookCopy.status = AVAILABLE.
10. Update book counters.
11. Audit RETURN_BOOK.
12. Publish event sau commit.
```

## Vấn đề status hiện tại

DB/enum hiện có `AVAILABLE`, `BORROWED`, `RESERVED`, `LOST`, `DAMAGED`.

Về nghiệp vụ thư viện, bộ này hơi thiếu. Nếu muốn làm circulation rõ nghĩa và nhìn chắc hơn, nên thêm:

```text
ON_HOLD_SHELF = copy đang được giữ tại quầy cho hold đã sẵn sàng lấy
OVERDUE       = copy/borrow quá hạn, nếu muốn phản ánh cả ở item status
REMOVED       = copy đã ngừng lưu hành
```

Khuyến nghị:

```text
Nên thêm ON_HOLD_SHELF và REMOVED.
OVERDUE có thể thêm, nhưng status quá hạn quan trọng nhất vẫn nên nằm ở BorrowRecord.
```

Lý do:

- `RESERVED` nghe chung chung, không rõ sách đang được giữ ở đâu.
- `ON_HOLD_SHELF` mô tả đúng thực tế: sách đã về quầy, chờ người đặt hold đến lấy.
- `REMOVED` tốt hơn việc lạm dụng `DAMAGED` hoặc soft delete cho copy ngừng lưu hành.
- `OVERDUE` trên `BookCopy` là tùy chọn vì copy quá hạn thực chất vẫn đang được mượn. Nếu thêm thì mọi logic active copy phải xem cả `BORROWED` và `OVERDUE`.

Migration gợi ý:

```sql
ALTER TABLE book_copies
DROP CONSTRAINT IF EXISTS chk_copy_status;

ALTER TABLE book_copies
ADD CONSTRAINT chk_copy_status
CHECK (
    status IN (
        'AVAILABLE',
        'BORROWED',
        'OVERDUE',
        'ON_HOLD_SHELF',
        'LOST',
        'DAMAGED',
        'REMOVED'
    )
);
```

Enum Java:

```java
public enum BookCopyStatus {
    AVAILABLE,
    BORROWED,
    OVERDUE,
    ON_HOLD_SHELF,
    LOST,
    DAMAGED,
    REMOVED
}
```

Điểm cần sửa theo:

```text
BookServiceImpl.ACTIVE_COPY_STATUSES nên gồm BORROWED, OVERDUE, ON_HOLD_SHELF.
BookCopy delete không cho xóa BORROWED, OVERDUE, ON_HOLD_SHELF.
availableOnly chỉ tính AVAILABLE.
Return có hold thì chuyển copy sang ON_HOLD_SHELF.
```

## Fine khi trả quá hạn

Đúng như bạn nghi ngờ: `fine_configs` đã có trong DB.

Hiện schema có:

```text
fine_configs
- rate_per_day
- currency
- effective_from
- effective_until
- created_by
```

và `borrow_records` lưu fine trực tiếp:

```text
fine_amount
fine_config_id
fine_calculated_at
fine_paid_at
fine_waived_by
fine_waived_reason
```

Vì vậy MVP không cần tạo bảng `fines` riêng. Khi trả sách:

```java
long overdueDays = max(0, ChronoUnit.DAYS.between(dueDate.toLocalDate(), returnedAt.toLocalDate()));
BigDecimal fine = ratePerDay.multiply(BigDecimal.valueOf(overdueDays));
```

Nếu fine > 0:

- Set `borrow_records.fine_amount`.
- Set `fine_config_id`.
- Set `fine_calculated_at`.

Thiết kế này ổn cho đồ án vì mỗi borrow record chỉ có một overdue fine chính. Nếu sau này cần nhiều loại phí trên cùng một borrow, ví dụ vừa quá hạn vừa hư hỏng vừa mất sách, khi đó mới nên tách bảng `fines` riêng.

## Counter book

Nếu copy từ `BORROWED` về `AVAILABLE`:

```text
availableCopies +1
```

Nếu copy từ `BORROWED` về `ON_HOLD_SHELF`:

```text
availableCopies không tăng
```

Nếu copy từ `BORROWED` về `DAMAGED`:

```text
availableCopies không tăng
```

`totalCopies` giữ nguyên trong cả ba trường hợp.

## Hold handoff khi return

Khi có hold queue:

```text
Borrow returned
Next waiting reservation selected
Copy chuyển ON_HOLD_SHELF
Reservation chuyển NOTIFIED hoặc READY_FOR_PICKUP
Gửi email hold ready sau commit
```

DB hiện `reservations.status` có `NOTIFIED`, chưa có `READY_FOR_PICKUP`. MVP có thể dùng:

```text
NOTIFIED = ready for pickup
```

Nếu muốn rõ hơn, migration sau đổi `NOTIFIED` thành `READY_FOR_PICKUP`.

## Test nên có

- Check-in copy borrowed thành công.
- Check-in copy không có borrow mở bị từ chối.
- Return quá hạn tính fine đúng.
- Return good không có hold tăng availableCopies.
- Return good có hold không tăng availableCopies và reservation chuyển `NOTIFIED`.
- Return damaged không tăng availableCopies.
- Staff/admin check-in được, member không check-in được.

## Nguồn kỹ thuật

- Spring Data JPA Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Koha circulation manual: https://koha-community.org/manual/23.05/en/html/circulation.html
- PostgreSQL check constraints: https://www.postgresql.org/docs/current/ddl-constraints.html
