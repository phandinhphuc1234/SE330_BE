# Overdue Job Locking Explanation

Tài liệu này giải thích vì sao `markOverdueBorrows` job dùng lock khi xử lý từng `BorrowRecord`.

Code liên quan:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
@Query("""
        select borrow
        from BorrowRecord borrow
        where borrow.id = :id
        """)
Optional<BorrowRecord> findLockedForOverdueById(@Param("id") Long id);
```

## 1. Hàm này dùng để làm gì?

Hàm `findLockedForOverdueById` dùng để:

```text
1. Lấy một BorrowRecord theo id.
2. Load sẵn member, bookCopy và book.
3. Khóa dòng BorrowRecord đó trong database trong lúc transaction đang xử lý.
```

Về mặt SQL, ý nghĩa gần giống:

```sql
SELECT *
FROM borrow_records
WHERE id = ?
FOR UPDATE;
```

`FOR UPDATE` nghĩa là:

```text
Transaction hiện tại đang giữ quyền ghi trên dòng này.
Transaction khác muốn update cùng dòng này phải chờ transaction hiện tại commit hoặc rollback.
```

## 2. Vì sao overdue job cần lock?

Overdue job là background job chạy tự động:

```text
BorrowRecord.status = BORROWED
dueDate < now
→ BorrowRecord.status = OVERDUE
→ BookCopy.status = OVERDUE
```

Nhưng trong lúc job chạy, hệ thống vẫn có thể nhận thao tác thật từ staff/user:

```text
Staff check-in sách
Member self-renew
Staff renew hộ
Auto-renewal job
```

Các flow này đều có thể đụng đến cùng một `BorrowRecord`.

Ví dụ:

```text
01:00:00 Overdue job query thấy borrow #100 đã quá hạn.
01:00:01 Staff quét barcode để check-in đúng copy của borrow #100.
```

Nếu không lock, có thể xảy ra race condition:

```text
Transaction A - Overdue job:
  set BorrowRecord.status = OVERDUE
  set BookCopy.status = OVERDUE

Transaction B - Check-in:
  set BorrowRecord.status = RETURNED
  set BookCopy.status = AVAILABLE hoặc DAMAGED/ON_HOLD_SHELF
```

Nếu hai transaction chạy gần như đồng thời, kết quả cuối phụ thuộc transaction nào commit sau. Điều này dễ gây dữ liệu khó hiểu:

```text
Borrow đã trả nhưng status bị OVERDUE lại.
Copy đã AVAILABLE nhưng bị set OVERDUE.
Log job báo mark overdue thành công trong khi staff vừa trả sách.
```

Lock giúp ép thứ tự xử lý:

```text
Ai lock được borrow #100 trước thì xử lý trước.
Người còn lại phải chờ.
Sau khi chờ xong, người còn lại đọc lại trạng thái mới và quyết định có xử lý tiếp hay skip.
```

## 3. `@Lock(PESSIMISTIC_WRITE)` nghĩa là gì?

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

Đây là pessimistic lock, tức là:

```text
Khóa trước rồi mới sửa.
```

Tên "pessimistic" nghĩa là hệ thống giả định có khả năng xảy ra tranh chấp, nên khóa dữ liệu ngay từ lúc đọc.

Trong case này:

```text
Overdue job đọc BorrowRecord để chuẩn bị update status.
Vì nó sắp update, nên dùng PESSIMISTIC_WRITE.
```

Khi transaction đang giữ lock:

```text
Transaction khác không thể update cùng BorrowRecord đó ngay lập tức.
Transaction khác phải chờ hoặc timeout tùy cấu hình database/JPA.
```

## 4. Vì sao không dùng `findById` bình thường?

Nếu dùng:

```java
borrowRecordRepository.findById(id)
```

thì chỉ là đọc dữ liệu bình thường.

Nó không ngăn transaction khác cùng lúc làm:

```text
update borrow_records set status = ...
```

Với background job, điều này không đủ chắc vì job không phải hành động do một người thao tác trực tiếp. Nó chạy theo lịch và có thể đụng vào flow request thật.

Vì vậy cần một method riêng:

```java
findLockedForOverdueById(id)
```

Tên method cố ý rõ nghĩa:

```text
Locked
ForOverdue
ById
```

Đọc tên là biết:

```text
Method này dùng cho flow overdue và có lock.
```

## 5. Vì sao cần kiểm tra lại status sau khi lock?

Trong processor hiện tại:

```java
BorrowRecord borrow = borrowRecordRepository.findLockedForOverdueById(borrowId).orElse(null);
if (borrow == null || borrow.getStatus() != BorrowStatus.BORROWED) {
    return OverdueMarkResult.skipped();
}
```

Lý do phải check lại:

```text
Candidate list được query trước.
Nhưng từ lúc query candidate đến lúc process từng borrow, record có thể đã thay đổi.
```

Ví dụ:

```text
01:00:00 Job query borrow #100 là BORROWED và quá hạn.
01:00:03 Staff check-in borrow #100 thành RETURNED.
01:00:05 Job mới process borrow #100.
```

Nếu job không check lại status, nó có thể set `RETURNED` thành `OVERDUE`, đây là bug.

Vì vậy flow đúng là:

```text
1. Query candidate ids.
2. Với từng id, load lại bằng lock.
3. Kiểm tra trạng thái hiện tại.
4. Nếu vẫn BORROWED thì mark OVERDUE.
5. Nếu không còn BORROWED thì skip.
```

Đây là pattern quan trọng cho background job.

## 6. `@EntityGraph` trong hàm này để làm gì?

```java
@EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
```

`BorrowRecord` có quan hệ:

```text
BorrowRecord -> Member
BorrowRecord -> BookCopy
BookCopy -> Book
```

Các quan hệ này thường là `LAZY`, tức là chỉ load khi gọi getter.

Overdue processor cần dùng:

```java
borrow.getMember().getId()
borrow.getBookCopy().setStatus(BookCopyStatus.OVERDUE)
borrow.getBookCopy().getBook()
```

`@EntityGraph` giúp load sẵn các quan hệ này cùng lúc với borrow.

Lợi ích:

```text
1. Tránh lazy loading bất ngờ.
2. Tránh N+1 query khi xử lý nhiều borrow.
3. Đảm bảo processor có đủ dữ liệu trong transaction.
```

## 7. Lock đang khóa cái gì?

Quan trọng: method này lock chính `BorrowRecord`.

```text
Lock trực tiếp:
  borrow_records row

Load kèm:
  member
  bookCopy
  book
```

`@EntityGraph` không có nghĩa là tất cả entity liên quan đều bị lock mạnh như nhau theo mọi database/provider. Nó chủ yếu là load graph.

Trong flow hiện tại, lock `BorrowRecord` là trọng tâm vì:

```text
BorrowRecord.status là source of truth cho lượt mượn.
Check-in, renew, overdue đều phải nhìn borrow status.
```

Overdue processor cũng update `BookCopy.status`.

Trong thực tế nếu muốn cực chặt, có thể thêm method lock riêng `BookCopy` hoặc query lock cả copy. Nhưng với project này, lock `BorrowRecord` + re-check status là mức hợp lý, không quá nặng.

## 8. Lock này có over-engineering không?

Với thao tác quầy đơn lẻ, có lúc lock có thể bị xem là hơi nặng.

Nhưng với overdue job, lock hợp lý hơn vì:

```text
1. Job chạy nền, không có người kiểm soát từng record.
2. Job xử lý hàng loạt.
3. Job có thể chạy đúng lúc staff check-in hoặc user renew.
4. Nếu update sai status thì dữ liệu circulation rất khó debug.
5. Mỗi transaction chỉ lock một borrow trong thời gian ngắn.
```

Nên đây không phải over-engineering. Nó là biện pháp bảo vệ vừa đủ cho background job.

## 9. Lock này không giải quyết những gì?

Lock này không phải thuốc chữa mọi concurrency problem.

Nó không giải quyết:

```text
1. Hai instance backend cùng chạy job nếu deploy nhiều node.
2. Deadlock phức tạp nếu nhiều flow lock nhiều bảng theo thứ tự khác nhau.
3. Gửi email trùng.
4. Job chạy lại nhiều lần trong ngày.
```

Với project hiện tại, assumption là:

```text
Một backend instance.
Job chạy theo lịch cố định.
Mỗi borrow xử lý transaction riêng.
```

Nếu sau này chạy nhiều instance, nên dùng thêm:

```text
ShedLock
hoặc DB advisory lock
hoặc queue với SKIP LOCKED
```

### 9.1. Vì sao idempotency key không thay lock ở đây?

Idempotency key phù hợp cho HTTP request do frontend gửi lên, ví dụ:

```text
POST /api/staff/circulation/checkins
PUT /api/borrows/{borrowId}/extend
POST /api/holds
```

Nó chống các case như:

```text
1. User double click.
2. Frontend timeout rồi retry cùng request.
3. Staff bấm submit lại vì tưởng request chưa chạy.
```

Nhưng overdue job không phải request từ frontend. Nó là background job chạy theo lịch, nên nó không có `Idempotency-Key` do client sinh ra.

Concurrency của overdue job đến từ hướng khác:

```text
1. Job nền đang mark overdue.
2. Staff đang check-in cùng borrow.
3. User hoặc staff đang renewal cùng borrow.
4. Auto-renewal job cũng có thể đang xử lý cùng borrow.
```

Vì vậy công cụ đúng ở đây là:

```text
transaction
row-level lock
re-check business condition sau khi lock
job execution log
```

Tóm lại:

```text
Idempotency key bảo vệ retry của cùng một HTTP command.
Pessimistic lock bảo vệ cập nhật đồng thời trên cùng một database row.
```

Hai cơ chế này không thay thế hoàn toàn cho nhau.

### 9.2. Vì sao không dùng optimistic lock?

Optimistic lock thường dùng field `@Version` trên entity:

```java
@Version
private Long version;
```

Flow của optimistic lock:

```text
Transaction A đọc borrow version = 1.
Transaction B đọc borrow version = 1.
Transaction A update thành version = 2.
Transaction B update thất bại vì version cũ.
```

Optimistic lock hợp khi:

```text
1. Conflict hiếm.
2. Không muốn transaction chờ nhau.
3. Có cơ chế retry hoặc báo lỗi rõ ràng cho caller.
```

Với overdue job, pessimistic lock dễ hiểu và hợp hơn ở thời điểm hiện tại:

```text
1. Processor xử lý một borrow rất nhanh.
2. Lock chỉ giữ trong transaction ngắn.
3. Nếu trùng với check-in/renewal thì chờ một chút rồi đọc trạng thái mới nhất là ổn.
4. Không cần thiết kế retry phức tạp cho job MVP.
```

Nếu sau này scale lớn hơn, có thể cân nhắc thêm `@Version` hoặc `SKIP LOCKED`, nhưng hiện tại pessimistic lock ở điểm job nền là giải pháp thực dụng hơn.

## 10. Vì sao processor dùng transaction riêng?

`OverdueMarkProcessor.markOne(...)` dùng:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
```

Ý nghĩa:

```text
Mỗi borrow được xử lý trong một transaction độc lập.
```

Lợi ích:

```text
1. Borrow A lỗi không làm rollback Borrow B.
2. Lock được giữ trong thời gian ngắn.
3. Job không mở một transaction dài cho cả batch.
4. Dễ đếm success/failed.
```

Flow:

```text
OverdueMarkService:
  query candidates
  loop từng borrowId

OverdueMarkProcessor:
  mở transaction mới
  lock borrow
  check status
  update status
  commit
```

## 11. Ví dụ flow an toàn

### Case 1: Job chạy trước check-in

```text
01:00:00 Job lock borrow #100.
01:00:01 Job thấy status BORROWED.
01:00:02 Job set borrow OVERDUE, copy OVERDUE.
01:00:03 Job commit.
01:00:04 Staff check-in borrow #100.
01:00:05 Check-in tìm open status gồm BORROWED, OVERDUE, LOST.
01:00:06 Check-in set RETURNED.
```

Kết quả đúng:

```text
Borrow từng bị quá hạn, sau đó được trả.
Fine vẫn được tính dựa trên dueDate/returnedAt.
```

### Case 2: Check-in chạy trước job

```text
01:00:00 Staff check-in borrow #100.
01:00:01 Check-in set RETURNED.
01:00:02 Check-in commit.
01:00:03 Job lock borrow #100.
01:00:04 Job thấy status RETURNED.
01:00:05 Job skip.
```

Kết quả đúng:

```text
Job không set RETURNED ngược lại thành OVERDUE.
```

### Case 3: Renew chạy trước job

```text
01:00:00 Member renew borrow #100.
01:00:01 Renew kéo dueDate sang tương lai.
01:00:02 Renew commit.
01:00:03 Job process borrow #100 từ candidate list cũ.
01:00:04 Job lock và check status vẫn BORROWED.
```

Ở case này cần lưu ý:

```text
Hiện processor check lại status nhưng chưa check lại dueDate < now sau khi lock.
```

Nếu borrow đã được renew sau candidate query, dueDate có thể không còn quá hạn nữa.

Khuyến nghị cải tiến:

```text
Sau khi lock, processor nên kiểm tra lại:
if (!borrow.getDueDate().isBefore(Instant.now())) skip;
```

Điều này giúp tránh job mark overdue một borrow vừa được renew.

## 12. Cải tiến nên làm ngay

Nên bổ sung check dueDate sau khi lock:

```java
if (borrow == null
        || borrow.getStatus() != BorrowStatus.BORROWED
        || !borrow.getDueDate().isBefore(Instant.now())) {
    return OverdueMarkResult.skipped();
}
```

Lý do:

```text
Candidate list là ảnh chụp tại thời điểm query.
DueDate có thể đã thay đổi trước khi processor lock được record.
```

Đây là check nhỏ nhưng làm job chắc hơn.

## 13. Kết luận

`findLockedForOverdueById` tồn tại để bảo vệ background job khỏi update đụng với flow thật.

Nó giúp:

```text
1. Không update cùng BorrowRecord đồng thời.
2. Tránh job mark overdue đè lên check-in/renew.
3. Load đủ dữ liệu cần dùng trong processor.
4. Giữ transaction ngắn và an toàn cho từng borrow.
```

Đây là một ví dụ tốt về việc dùng pessimistic lock đúng chỗ:

```text
Không dùng lock cho mọi thứ.
Chỉ dùng ở điểm background job có thể tranh chấp với request thật.
```
