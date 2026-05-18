# Audit Log Spec

## Mục tiêu

`audit_logs` dùng để lưu lịch sử các hành động nghiệp vụ quan trọng trong database, đặc biệt là các thao tác quản lý catalog:

- Tạo, sửa, xóa mềm đầu sách
- Tạo, sửa, xóa mềm bản copy vật lý
- Tạo, sửa tác giả
- Tạo, sửa thể loại
- Import sách từ CSV

Audit log khác với application log:

- `log.info(...)`: phục vụ debug, tracing, observability, đọc trong console/file/log system.
- `audit_logs`: phục vụ truy vết nghiệp vụ, xem ai đã thao tác gì, với entity nào, vào thời điểm nào.

## Đánh giá schema hiện tại

Schema hiện tại:

```sql
CREATE TABLE audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES members(id),
    action       VARCHAR(50) NOT NULL,
    entity_type  VARCHAR(50),
    entity_id    BIGINT,
    metadata     JSONB DEFAULT '{}'::jsonb,
    trace_id     VARCHAR(100),
    actor_role   VARCHAR(20),
    ip_address   VARCHAR(45),
    user_agent   VARCHAR(255),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_user   ON audit_logs(user_id);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_created_at ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_trace_id ON audit_logs(trace_id);
```

Kết luận: schema sau migration `V12__enhance_audit_logs.sql` hợp lý cho MVP nâng cấp.

Nó đủ để trả lời các câu hỏi quan trọng:

- Ai thực hiện hành động?
- User đó đang có role gì tại thời điểm thao tác?
- Hành động là gì?
- Tác động lên loại entity nào?
- Entity cụ thể có ID bao nhiêu?
- Có metadata bổ sung không?
- Request/API call nào sinh ra audit log này?
- IP và user agent nào thực hiện request?
- Xảy ra lúc nào?

Điểm cần hiểu rõ: schema này phù hợp nhất nếu chỉ ghi các thao tác thành công. Nếu muốn ghi cả thao tác thất bại, cần thêm các field như `result`, `error_code`, `error_message`.

## Có cần sửa schema ngay không?

Đã sửa bằng migration:

```text
src/main/resources/db/migration/V12__enhance_audit_logs.sql
```

Mức chỉnh này vẫn vừa sức đồ án: đủ để demo truy vết nghiệp vụ, nhưng chưa đưa thêm các phần phức tạp như audit failure, retention policy, partitioning hay async audit pipeline.

## Quy ước dữ liệu

### `user_id`

ID của user đang đăng nhập.

- Với API cần đăng nhập: lấy từ `SecurityContext`.
- Với system job hoặc dữ liệu seed: có thể để `NULL`.

Không nên truyền `user_id` từ request body vì client có thể giả mạo.

### `actor_role`

Role của user tại thời điểm tạo audit log.

Giá trị hợp lệ:

```text
MEMBER
ADMIN
LIBRARIAN
```

Field này hữu ích vì sau này role của user có thể thay đổi. Audit log nên phản ánh role tại thời điểm thao tác.

### `trace_id`

ID truy vết của request hiện tại.

Codebase đang có `RequestLoggingFilter` sinh/gắn header:

```text
X-Trace-Id
```

Khi audit log lưu cùng `trace_id`, bạn có thể nối được:

```text
application log <-> audit_logs <-> request từ frontend/Postman
```

### `ip_address`

IP client gửi request.

Khi deploy sau proxy/load balancer, nên ưu tiên đọc header như:

```text
X-Forwarded-For
X-Real-IP
```

Trong local/dev có thể dùng `request.getRemoteAddr()`.

### `user_agent`

Thông tin client/browser/tool gọi API. Không dùng field này cho security decision, chỉ dùng để truy vết.

### `action`

Dùng enum hoặc constant trong code để tránh sai chính tả.

Đề xuất action cho catalog:

```text
CREATE_BOOK
UPDATE_BOOK
DELETE_BOOK
UPDATE_BOOK_AUTHORS

CREATE_BOOK_COPY
BULK_CREATE_BOOK_COPIES
UPDATE_BOOK_COPY
DELETE_BOOK_COPY

CREATE_AUTHOR
UPDATE_AUTHOR

CREATE_CATEGORY
UPDATE_CATEGORY

IMPORT_BOOKS
```

### `entity_type`

Dùng tên entity dạng uppercase:

```text
BOOK
BOOK_COPY
AUTHOR
CATEGORY
BOOK_IMPORT
```

### `entity_id`

ID của entity chính bị tác động.

Ví dụ:

- `CREATE_BOOK`: `entity_id = books.id`
- `DELETE_BOOK_COPY`: `entity_id = book_copies.id`
- `IMPORT_BOOKS`: có thể để `NULL`, vì một lần import tác động nhiều sách/copy

### `metadata`

Lưu JSONB để chứa dữ liệu bổ sung theo từng action. Không nên nhét toàn bộ entity vào đây. Chỉ lưu các thông tin cần truy vết.

Ví dụ `CREATE_BOOK`:

```json
{
  "title": "Clean Code",
  "isbn": "9780132350884",
  "categoryId": 1,
  "authorIds": [1, 2]
}
```

Ví dụ `UPDATE_BOOK`:

```json
{
  "changedFields": ["title", "language", "categoryId"],
  "before": {
    "title": "Old Title",
    "language": "vi",
    "categoryId": 1
  },
  "after": {
    "title": "New Title",
    "language": "en",
    "categoryId": 2
  }
}
```

Ví dụ `DELETE_BOOK`:

```json
{
  "title": "Clean Code",
  "isbn": "9780132350884",
  "softDeleted": true,
  "softDeletedCopies": 3
}
```

Ví dụ `CREATE_BOOK_COPY`:

```json
{
  "bookId": 10,
  "barcode": "LIB-000001",
  "condition": "GOOD",
  "location": "Shelf A1"
}
```

Ví dụ `IMPORT_BOOKS`:

```json
{
  "fileName": "books.csv",
  "totalRows": 500,
  "successRows": 480,
  "failedRows": 20,
  "createdBooks": 120,
  "createdCopies": 480
}
```

## Các hành động nên audit trước

Ưu tiên implement theo thứ tự:

1. `DELETE_BOOK`
2. `DELETE_BOOK_COPY`
3. `CREATE_BOOK`
4. `CREATE_BOOK_COPY`
5. `IMPORT_BOOKS`
6. `UPDATE_BOOK`
7. `UPDATE_BOOK_COPY`
8. `UPDATE_BOOK_AUTHORS`
9. `CREATE_AUTHOR`, `UPDATE_AUTHOR`
10. `CREATE_CATEGORY`, `UPDATE_CATEGORY`

Lý do ưu tiên delete trước: đây là hành động nhạy cảm nhất, dù đang soft delete.

## Không cần audit những API nào?

Không cần ghi audit cho các API đọc dữ liệu:

```http
GET /api/books
GET /api/books/{bookId}
GET /api/books/{bookId}/copies
GET /api/authors
GET /api/categories
```

Các API đọc có thể để application log xử lý nếu cần tracking request.

## Thiết kế code đề xuất

Tạo entity:

```text
com.vn.entity.AuditLog
```

Tạo repository:

```text
com.vn.repository.AuditLogRepository
```

Tạo service:

```text
com.vn.service.AuditLogService
com.vn.service.impl.AuditLogServiceImpl
```

Interface đề xuất:

```java
public interface AuditLogService {
    void record(String action, String entityType, Long entityId, Object metadata);
}
```

`AuditLogServiceImpl` tự lấy user hiện tại từ `SecurityContext`, không nhận `userId` từ client.

Luồng xử lý:

```text
Controller -> Business Service -> Repository
                            -> AuditLogService.record(...)
```

Ví dụ:

```java
Book savedBook = bookRepository.save(book);

auditLogService.record(
        "CREATE_BOOK",
        "BOOK",
        savedBook.getId(),
        Map.of(
                "title", savedBook.getTitle(),
                "isbn", savedBook.getIsbn()
        )
);
```

## Transaction strategy

MVP nên ghi audit log trong cùng transaction với nghiệp vụ chính.

Lý do:

- Nếu tạo sách rollback thì audit log cũng rollback.
- Không bị tình trạng audit báo thành công nhưng dữ liệu chính không được lưu.
- Dễ hiểu, dễ test.

Không nên dùng `REQUIRES_NEW` ở MVP.

`REQUIRES_NEW` chỉ phù hợp nếu muốn ghi cả attempt/failure logs, ví dụ login failed, access denied, hoặc thao tác thất bại vẫn cần lưu lại.

## Metadata: nên lưu before/after không?

Không cần lưu before/after cho mọi action ngay từ đầu.

Khuyến nghị:

- `CREATE_*`: lưu dữ liệu chính sau khi tạo.
- `DELETE_*`: lưu dữ liệu định danh trước khi xóa mềm.
- `UPDATE_*`: lưu `changedFields`, và chỉ lưu `before/after` cho field quan trọng.
- `IMPORT_BOOKS`: lưu summary, không lưu toàn bộ từng dòng CSV.

Không nên lưu thông tin nhạy cảm:

- Password
- Token
- Cookie
- Raw Authorization header
- Dữ liệu cá nhân không cần thiết

## API xem audit log

MVP chưa bắt buộc cần API xem audit log.

Nếu muốn làm cho Admin sau này:

```http
GET /api/audit-logs?action=&entityType=&entityId=&userId=&from=&to=&page=&size=
```

Chỉ `ADMIN` được truy cập.

Response chỉ nên trả metadata đã sanitize.

## So sánh với soft delete

Soft delete và audit log không thay thế nhau.

Soft delete:

- Giữ bản ghi trong bảng chính.
- Cho phép ẩn dữ liệu khỏi public query.
- Có thể biết entity đã bị xóa lúc nào và bởi ai qua `deleted_at`, `deleted_by`.

Audit log:

- Ghi lại lịch sử hành động.
- Có thể biết không chỉ delete, mà cả create/update/import.
- Có metadata mô tả bối cảnh thao tác.

Vì vậy `DELETE_BOOK` nên có cả hai:

- `books.deleted_at`, `books.deleted_by`
- Một dòng `audit_logs` với action `DELETE_BOOK`

## Acceptance Criteria

Khi implement audit log cho catalog, cần đạt:

- Tạo sách thành công thì có một dòng `audit_logs` action `CREATE_BOOK`.
- Xóa mềm sách thành công thì có một dòng `audit_logs` action `DELETE_BOOK`.
- Tạo book copy thành công thì có một dòng `audit_logs` action `CREATE_BOOK_COPY`.
- Xóa mềm book copy thành công thì có một dòng `audit_logs` action `DELETE_BOOK_COPY`.
- Import CSV xong thì có một dòng `audit_logs` action `IMPORT_BOOKS` chứa summary.
- Nếu nghiệp vụ bị lỗi và rollback, không ghi audit success.
- `user_id` lấy từ user đang đăng nhập, không lấy từ request body.
- Metadata không chứa password/token/header nhạy cảm.

## Kết luận

Table `audit_logs` sau migration V12 là hợp lý cho MVP.

Việc nên làm tiếp theo là implement entity, repository, service, rồi ghi audit cho các nghiệp vụ catalog quan trọng.

Sau khi audit log chạy ổn, nếu muốn production-like hơn nữa thì cân nhắc thêm API xem audit log cho Admin, retention policy, hoặc partitioning theo thời gian.
