# Locking strategy trong circulation

Tài liệu này giải thích vì sao hệ thống dùng database lock trong nghiệp vụ circulation, cụ thể là checkout, checkin và renewal. Nội dung tập trung vào code hiện tại của The Athenaeum, đồng thời giải thích khi nào nên dùng optimistic lock và khi nào nên dùng pessimistic lock trong Spring Boot/JPA.

## 1. Vấn đề cần giải quyết

Trong hệ thống thư viện, một số tài nguyên là tài nguyên vật lý hoặc có rule rất chặt:

```text
Một barcode chỉ đại diện cho một bản sách vật lý.
Một bản sách không thể được mượn bởi hai người cùng lúc.
Một lượt mượn không được gia hạn vượt quá số lần policy cho phép.
availableCopies không được tăng/giảm sai vì hai request chạy song song.
```

Nếu không kiểm soát concurrency, các request đồng thời có thể tạo bug kiểu:

```text
Hai thủ thư checkout cùng một barcode.
Hai request checkin cùng một barcode làm availableCopies tăng 2 lần.
Hai request renewal cùng một borrowId làm dueDate bị cộng ngày 2 lần.
```

Vì vậy hệ thống dùng kết hợp:

```text
Idempotency-Key
→ chống retry/double click cùng một request từ client.

Database lock
→ chống nhiều transaction cùng sửa một row quan trọng trong database.
```

Hai cơ chế này bổ sung cho nhau, không thay thế nhau.

## 2. Lock đang dùng trong code hiện tại

Hiện tại circulation dùng `PESSIMISTIC_WRITE` ở 2 repository method.

### 2.1. Lock BookCopy theo barcode

File:

```text
src/main/java/com/vn/repository/BookCopyRepository.java
```

Method:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = {"book", "book.authors", "book.category"})
@Query("""
        select copy
        from BookCopy copy
        where lower(copy.barcode) = lower(:barcode)
          and copy.deletedAt is null
        """)
Optional<BookCopy> findLockedByBarcode(@Param("barcode") String barcode);
```

Method này đang được dùng bởi:

```text
CheckoutUseCase
CheckinUseCase
```

Ý nghĩa:

```text
Khi một transaction lấy BookCopy bằng barcode với PESSIMISTIC_WRITE,
database sẽ lock row book_copies đó cho tới khi transaction commit hoặc rollback.
```

### 2.2. Lock BorrowRecord theo id

File:

```text
src/main/java/com/vn/repository/BorrowRecordRepository.java
```

Method:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = {"member", "bookCopy", "bookCopy.book"})
Optional<BorrowRecord> findWithLockById(Long id);
```

Method này đang được dùng bởi:

```text
RenewalUseCase
```

Ý nghĩa:

```text
Khi một transaction đang gia hạn borrowId = 10,
transaction khác muốn gia hạn cùng borrowId = 10 phải chờ transaction đầu kết thúc.
```

## 3. Pessimistic lock hoạt động như thế nào

Trong JPA, `LockModeType.PESSIMISTIC_WRITE` yêu cầu persistence provider lấy database-level lock ngay lúc đọc entity.

Với PostgreSQL, Hibernate thường sinh SQL tương đương:

```sql
SELECT ...
FOR UPDATE
```

Theo PostgreSQL, `FOR UPDATE` lock các row được chọn để chống concurrent update. Transaction khác muốn update/delete hoặc lock cùng row sẽ bị chặn cho tới khi transaction hiện tại kết thúc.

Nói đơn giản:

```text
Transaction A lock row X.
Transaction B muốn sửa/lock row X.
Transaction B phải chờ A commit hoặc rollback.
```

Lock được giữ trong phạm vi transaction:

```text
@Transactional method bắt đầu
↓
Repository query với @Lock chạy
↓
Row bị lock
↓
Business logic update dữ liệu
↓
Commit hoặc rollback
↓
Lock được nhả
```

## 4. Vì sao checkout cần lock BookCopy

### 4.1. Race condition nếu không lock

Giả sử copy `BC001` đang `AVAILABLE`.

Không có lock:

```text
Request A checkout BC001
Request B checkout BC001

A đọc copy = AVAILABLE
B đọc copy = AVAILABLE

A tạo BorrowRecord
B tạo BorrowRecord

A set copy = BORROWED
B set copy = BORROWED

Kết quả:
Một bản sách vật lý bị mượn bởi 2 người.
```

Đây là bug nghiêm trọng vì barcode đại diện cho một bản sách duy nhất.

### 4.2. Flow khi có lock

Có `PESSIMISTIC_WRITE`:

```text
Request A lock BC001
Request B muốn lock BC001 nên phải chờ

A kiểm tra copy AVAILABLE
A tạo BorrowRecord
A set copy = BORROWED
A commit

B tiếp tục sau khi A commit
B đọc lại copy = BORROWED
B bị chặn BOOK_COPY_NOT_AVAILABLE
```

Kết quả:

```text
Chỉ có một checkout thành công.
Request còn lại bị reject đúng nghiệp vụ.
```

## 5. Vì sao checkin cần lock BookCopy

Checkin cũng thao tác trên cùng tài nguyên vật lý `BookCopy`.

Không có lock:

```text
Request A checkin BC001
Request B checkin BC001

Cả hai cùng tìm borrow đang open.
Cả hai cùng set borrow RETURNED.
Cả hai cùng set copy AVAILABLE.
Cả hai cùng tăng availableCopies.

Kết quả:
availableCopies có thể bị tăng sai.
```

Có lock:

```text
Request A lock BC001 và xử lý trả sách.
Request B phải chờ.

A commit xong, borrow đã RETURNED.
B chạy tiếp nhưng không còn active/open borrow hợp lệ.
B bị chặn ACTIVE_BORROW_NOT_FOUND.
```

Lock giúp bảo vệ:

```text
BookCopy.status
BorrowRecord.status
Book.availableCopies
Fine calculation khi trả sách
```

## 6. Vì sao renewal cần lock BorrowRecord

Renewal cập nhật trực tiếp:

```text
dueDate
renewCount
```

Không có lock:

```text
borrowId = 10
renewCount = 0
maxRenewalsAtCheckout = 1

Request A đọc renewCount = 0
Request B đọc renewCount = 0

A thấy còn lượt gia hạn
B cũng thấy còn lượt gia hạn

A cộng dueDate thêm 7 ngày
B cũng cộng dueDate thêm 7 ngày

Kết quả:
Policy chỉ cho gia hạn 1 lần có thể bị phá.
```

Có lock:

```text
Request A lock borrowId = 10.
Request B phải chờ.

A renewCount 0 -> 1, commit.
B đọc lại borrow sau commit.
B thấy renewCount = maxRenewalsAtCheckout.
B bị chặn BORROW_NOT_RENEWABLE.
```

Lock giúp đảm bảo renewal policy là atomic:

```text
check rule
update dueDate
increase renewCount
save
```

## 7. Idempotency-Key khác gì database lock

Hai cơ chế xử lý hai loại vấn đề khác nhau.

| Cơ chế | Chống lỗi gì | Ví dụ |
|---|---|---|
| Idempotency-Key | Retry/double click cùng một request | Frontend timeout rồi gửi lại checkout request cũ |
| Database lock | Nhiều transaction khác nhau cùng sửa một row | Hai thủ thư checkout cùng barcode bằng hai request khác nhau |

Ví dụ idempotency xử lý tốt:

```text
Cùng actor
Cùng endpoint
Cùng Idempotency-Key
Cùng body
```

Backend trả lại response cũ, không chạy nghiệp vụ lần hai.

Nhưng nếu:

```text
Thủ thư A checkout barcode BC001 với key aaa
Thủ thư B checkout barcode BC001 với key bbb
```

Idempotency không coi đây là retry, vì key khác, actor có thể khác. Lúc này database lock mới bảo vệ được `BookCopy`.

## 8. Optimistic lock là gì

Optimistic locking giả định rằng conflict hiếm khi xảy ra.

Thường entity sẽ có một field version:

```java
@Version
private Long version;
```

Flow:

```text
Transaction A đọc entity version = 1.
Transaction B đọc entity version = 1.

A update entity, database version tăng lên 2.
B update entity với version cũ = 1.

Database/JPA phát hiện version đã thay đổi.
B bị OptimisticLockException.
```

Đặc điểm:

```text
Không lock row ngay lúc đọc.
Concurrency cao hơn khi conflict ít.
Khi conflict xảy ra thì request sau bị fail và cần retry/merge/thông báo user.
```

Theo JPA/Jakarta Persistence, optimistic lock thường dùng với entity có version. Persistence provider không bắt buộc hỗ trợ optimistic lock cho non-versioned entity.

## 9. Pessimistic lock là gì

Pessimistic locking giả định rằng conflict có khả năng xảy ra hoặc hậu quả conflict rất nghiêm trọng.

Flow:

```text
Transaction A đọc và lock row.
Transaction B muốn sửa/lock cùng row phải chờ.
Transaction A commit/rollback.
Transaction B tiếp tục.
```

Đặc điểm:

```text
Chặn conflict sớm.
Phù hợp với tài nguyên khan hiếm hoặc nghiệp vụ cần serialize.
Có thể gây wait/block nếu nhiều request tranh cùng một row.
Nếu lock nhiều row không nhất quán thứ tự thì có thể deadlock.
```

Trong JPA, `PESSIMISTIC_WRITE` được dùng để force serialization giữa các transaction muốn update cùng entity.

## 10. Khi nào nên dùng optimistic lock

Nên dùng optimistic lock khi:

```text
1. Conflict hiếm.
2. Dữ liệu được user mở form, chỉnh sửa lâu rồi mới submit.
3. Không muốn giữ database lock qua thời gian user thao tác.
4. Có thể báo lỗi "dữ liệu đã bị thay đổi, vui lòng tải lại".
5. Có thể retry tự động hoặc merge dữ liệu.
```

Ví dụ phù hợp:

```text
Admin sửa metadata sách.
Librarian sửa mô tả category.
User cập nhật profile.
Admin sửa thông tin author.
Admin cập nhật setting không quá thường xuyên.
```

Ví dụ cụ thể:

```text
Admin A mở form sửa Book title.
Admin B cũng mở form sửa cùng Book.
A lưu trước.
B lưu sau.

Optimistic lock phát hiện B đang lưu trên version cũ.
Hệ thống báo B reload dữ liệu.
```

Ở các case này, dùng pessimistic lock không hợp lý vì không thể giữ row lock trong suốt lúc user đang nhìn form trên UI.

## 11. Khi nào nên dùng pessimistic lock

Nên dùng pessimistic lock khi:

```text
1. Conflict có khả năng xảy ra cao hoặc hậu quả nghiêm trọng.
2. Business operation ngắn, chạy trong một transaction backend.
3. Tài nguyên là "limited resource" hoặc "single owner at a time".
4. Cần check-then-update phải atomic.
5. Không muốn xử lý retry/merge phức tạp.
```

Ví dụ phù hợp:

```text
Checkout một bản sách theo barcode.
Checkin một bản sách theo barcode.
Renew một borrow record có renewCount giới hạn.
Reserve một copy cuối cùng còn available.
Payment capture một invoice/fine.
Trừ tồn kho khi đặt hàng.
```

Với circulation của bạn, pessimistic lock hợp lý vì:

```text
Barcode là tài nguyên vật lý duy nhất.
Checkout/checkin là transaction ngắn.
Renewal là check-then-update rất nhạy với race condition.
Nếu sai sẽ tạo dữ liệu nghiệp vụ sai, không chỉ là UI stale.
```

## 12. Vì sao hiện tại không dùng optimistic lock cho circulation

Có thể dùng optimistic lock cho `BookCopy` hoặc `BorrowRecord` bằng `@Version`, nhưng nó chưa phải lựa chọn tốt nhất cho các flow hiện tại.

Lý do:

```text
1. Checkout một copy là tranh chấp tài nguyên vật lý.
2. Nếu conflict xảy ra, request sau gần như chắc chắn phải fail chứ không merge được.
3. Pessimistic lock giúp request sau chờ rồi đọc trạng thái mới nhất.
4. Code nghiệp vụ dễ giải thích hơn cho đồ án/intern.
5. Transaction checkout/checkin/renewal ngắn nên thời gian giữ lock thấp.
```

Optimistic lock sẽ phù hợp hơn cho các màn hình CRUD như:

```text
Book metadata
Author
Category
Member profile
System setting
```

Nếu sau này muốn cải thiện toàn hệ thống, có thể thêm:

```java
@Version
private Long version;
```

cho các entity hay bị sửa qua form admin, nhưng không cần thay thế lock hiện tại của circulation.

## 13. Rủi ro khi dùng pessimistic lock

Pessimistic lock đúng nhưng không miễn phí.

Các rủi ro:

```text
1. Request sau có thể phải chờ request trước commit.
2. Nếu transaction giữ lock quá lâu, throughput giảm.
3. Nếu lock nhiều row theo thứ tự không nhất quán, có thể deadlock.
4. Nếu lock query quá rộng, có thể khóa nhiều row hơn cần thiết.
```

Cách giảm rủi ro trong project hiện tại:

```text
1. Chỉ lock đúng BookCopy theo barcode hoặc BorrowRecord theo id.
2. Không lock cả bảng.
3. Transaction checkout/checkin/renewal phải giữ ngắn.
4. Không gọi external API hoặc gửi email trong transaction đang giữ lock.
5. Nếu sau này phải lock nhiều row, luôn lock theo thứ tự ổn định, ví dụ order by id.
```

## 14. Lock có ảnh hưởng hiệu năng không

Có, nếu nhiều request tranh cùng một row.

Nhưng trong case hiện tại, phạm vi lock rất hẹp:

```text
Checkout BC001 không chặn checkout BC002.
Checkin BC001 không chặn checkin BC002.
Renew borrowId 10 không chặn renew borrowId 11.
```

Nó chỉ serialize thao tác trên đúng tài nguyên đang bị tranh chấp.

Đây là trade-off hợp lý:

```text
Chấp nhận chờ ngắn trên cùng một barcode/borrowId
để đổi lấy tính đúng đắn của dữ liệu circulation.
```

## 15. Khi nào nên cân nhắc NOWAIT hoặc timeout

PostgreSQL hỗ trợ `NOWAIT` và `SKIP LOCKED` cho row-level lock.

Ý nghĩa:

```text
NOWAIT:
  Nếu row đang bị lock, fail ngay thay vì chờ.

SKIP LOCKED:
  Bỏ qua row đang bị lock, thường dùng cho queue/job workers.
```

Hiện tại project chưa cần `NOWAIT` vì checkout/checkin/renewal là request nghiệp vụ trực tiếp, chờ một transaction ngắn là chấp nhận được.

Có thể cân nhắc sau này:

```text
Nếu UI cần fail fast khi barcode đang được xử lý.
Nếu job background xử lý batch nhiều row và không muốn worker chờ nhau.
```

Ví dụ `SKIP LOCKED` hợp với job:

```text
Nhiều worker cùng lấy danh sách email pending để gửi.
Worker nào lock được row nào thì xử lý row đó.
Row đang bị worker khác lock thì bỏ qua.
```

Nhưng `SKIP LOCKED` không phù hợp cho checkout một barcode cụ thể, vì nếu barcode bị lock mà skip đi thì nghiệp vụ sẽ khó hiểu với user.

## 16. Checklist chọn optimistic hay pessimistic

| Câu hỏi | Nếu câu trả lời là có | Nên nghiêng về |
|---|---|---|
| Conflict hiếm, user edit form lâu? | Có | Optimistic |
| Có thể báo user reload/merge dữ liệu? | Có | Optimistic |
| Operation ngắn trong backend transaction? | Có | Pessimistic |
| Tài nguyên chỉ được sở hữu bởi một request tại một thời điểm? | Có | Pessimistic |
| Sai race condition sẽ tạo dữ liệu nghiệp vụ sai nghiêm trọng? | Có | Pessimistic |
| Có thể retry an toàn khi conflict? | Có | Optimistic |
| Không muốn request sau chờ request trước? | Có | Optimistic hoặc NOWAIT |
| Cần chọn record cho nhiều worker xử lý batch? | Có | Pessimistic + SKIP LOCKED |

## 17. Áp dụng vào hệ thống hiện tại

| Flow | Lock hiện tại | Lý do |
|---|---|---|
| Checkout | `BookCopy` by barcode, `PESSIMISTIC_WRITE` | Không cho mượn trùng một bản sách |
| Checkin | `BookCopy` by barcode, `PESSIMISTIC_WRITE` | Không trả trùng và không tăng counter sai |
| Renewal | `BorrowRecord` by id, `PESSIMISTIC_WRITE` | Không tăng `renewCount`/`dueDate` trùng |
| Import CSV | Chưa dùng lock circulation | Import xử lý barcode bằng unique constraint/batch duplicate check |
| Book metadata CRUD | Chưa dùng optimistic lock | Có thể thêm `@Version` sau nếu cần chống lost update |

## 18. Cách giải thích khi phỏng vấn

Bạn có thể nói:

```text
Trong circulation, em dùng pessimistic lock cho BookCopy khi checkout/checkin và BorrowRecord khi renewal.
Lý do là đây là các thao tác check-then-update trên tài nguyên có tính độc quyền:
một barcode không thể được mượn hai lần, một borrow record không thể được renew vượt policy.

Idempotency-Key chỉ chống retry cùng request từ client, còn pessimistic lock chống race condition giữa nhiều transaction khác nhau.

Em không dùng pessimistic lock cho các màn hình CRUD metadata vì các form đó conflict thấp và có thể dùng optimistic lock với @Version nếu cần.
```

## 19. Nguồn tham khảo

Các nguồn chính dùng để thiết kế và đối chiếu:

```text
Jakarta Persistence LockModeType
https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/lockmodetype

Spring Data JPA Locking
https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html

Hibernate ORM User Guide - Locking
https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html

PostgreSQL SELECT / FOR UPDATE
https://www.postgresql.org/docs/current/sql-select.html

PostgreSQL Explicit Locking
https://www.postgresql.org/docs/current/explicit-locking.html
```
