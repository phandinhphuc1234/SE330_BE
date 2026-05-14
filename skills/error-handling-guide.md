---
description: Hướng dẫn sử dụng ErrorCode, AppException và exception handling trong dự án
---

# Hướng dẫn Error Handling

## Kiến trúc tổng quan

```
Controller → Service throw AppException(ErrorCode.XXX)
                          ↓
              GlobalExceptionHandler bắt
                          ↓
              ApiResponse.error(...) + traceId
                          ↓
              ResponseEntity trả về client
```

## Cách thêm ErrorCode mới

### Bước 1: Thêm vào `ErrorCode.java`

File: `src/main/java/com/vn/exception/ErrorCode.java`

```java
// Format: TEN_LOI(HttpStatus, "MA_LOI", "Message tiếng Việt cho user")
BOOK_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "BOOK_NOT_AVAILABLE", "Sách hiện không khả dụng"),
```

**Quy tắc đặt tên:**
- Dùng UPPER_SNAKE_CASE
- Nhóm theo chức năng (Auth, Validation, Resource, Business...)
- Message là tiếng Việt, dành cho end-user đọc

**Chọn HttpStatus đúng:**
- `400 BAD_REQUEST` — request sai (validation, logic lỗi)
- `401 UNAUTHORIZED` — chưa đăng nhập hoặc token sai
- `403 FORBIDDEN` — đã login nhưng không có quyền
- `404 NOT_FOUND` — tài nguyên không tồn tại
- `409 CONFLICT` — trùng lặp dữ liệu
- `500 INTERNAL_SERVER_ERROR` — lỗi hệ thống không lường trước

### Bước 2: Throw exception trong Service

```java
// Cách 1: Dùng ErrorCode (khuyến khích)
throw new AppException(ErrorCode.BOOK_NOT_AVAILABLE);

// Cách 2: Custom message (khi cần dynamic message)
throw new AppException(HttpStatus.BAD_REQUEST, "BOOK_NOT_AVAILABLE", "Sách '" + title + "' đã hết");
```

### Bước 3 (tùy chọn): Tạo exception riêng cho domain cụ thể

Chỉ tạo khi exception được throw ở nhiều nơi và cần semantic rõ ràng:

File: `src/main/java/com/vn/exception/BookNotAvailableException.java`

```java
public class BookNotAvailableException extends AppException {
    public BookNotAvailableException() {
        super(ErrorCode.BOOK_NOT_AVAILABLE);
    }
}
```

## Quy tắc KHÔNG được làm

1. **KHÔNG throw Exception chung chung** — luôn dùng `AppException` hoặc subclass
   ```java
   // ❌ SAI
   throw new RuntimeException("Sách không có");
   
   // ✅ ĐÚNG
   throw new AppException(ErrorCode.BOOK_NOT_AVAILABLE);
   ```

2. **KHÔNG try-catch rồi nuốt exception** — để GlobalExceptionHandler xử lý
   ```java
   // ❌ SAI
   try {
       memberRepository.save(member);
   } catch (Exception e) {
       return null;  // nuốt lỗi
   }
   
   // ✅ ĐÚNG — để exception tự propagate lên GlobalExceptionHandler
   memberRepository.save(member);
   ```

3. **KHÔNG trả error response thủ công trong controller** — để GlobalExceptionHandler lo
   ```java
   // ❌ SAI
   @PostMapping("/borrow")
   public ResponseEntity<?> borrow() {
       if (!available) {
           return ResponseEntity.badRequest().body("Sách hết");
       }
   }
   
   // ✅ ĐÚNG
   @PostMapping("/borrow")
   public ResponseEntity<ApiResponse<Void>> borrow() {
       borrowService.borrow(bookId); // service tự throw nếu lỗi
       return ResponseEntity.ok(ApiResponse.success("Mượn thành công", null));
   }
   ```

4. **KHÔNG tạo ErrorCode trùng ý nghĩa** — dùng lại cái đã có
   ```java
   // ❌ SAI — đã có RESOURCE_NOT_FOUND
   MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "Không tìm thấy thành viên"),
   
   // ✅ ĐÚNG — dùng lại RESOURCE_NOT_FOUND
   throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
   ```

## Danh sách ErrorCode hiện có

| Nhóm | ErrorCode | HTTP | Dùng khi |
|------|-----------|------|----------|
| Auth | `INVALID_OR_EXPIRED_TOKEN` | 401 | JWT hết hạn hoặc sai |
| Auth | `INVALID_CREDENTIALS` | 401 | Login sai email/password |
| Auth | `UNAUTHORIZED` | 401 | Chưa đăng nhập |
| Auth | `ACCESS_DENIED` | 403 | Không có quyền |
| Auth | `EMAIL_NOT_VERIFIED` | 403 | Chưa xác nhận email |
| Auth | `ACCOUNT_INACTIVE` | 403 | Tài khoản bị khóa |
| Auth | `VERIFICATION_TOKEN_EXPIRED` | 400 | Link xác nhận hết hạn |
| Validation | `VALIDATION_ERROR` | 400 | @Valid failed |
| Validation | `ILLEGAL_ARGUMENT` | 400 | Tham số sai |
| Validation | `BAD_REQUEST` | 400 | Request chung chung sai |
| Validation | `MISSING_REQUEST_PARAMETER` | 400 | Thiếu query param |
| Validation | `MALFORMED_JSON` | 400 | JSON body sai format |
| Validation | `CONSTRAINT_VIOLATION` | 400 | DB constraint lỗi |
| Resource | `RESOURCE_NOT_FOUND` | 404 | Không tìm thấy |
| Resource | `EMAIL_ALREADY_EXISTS` | 409 | Email trùng |
| Resource | `DUPLICATE_RESOURCE` | 409 | Tài nguyên trùng |
| Resource | `DATA_INTEGRITY_VIOLATION` | 409 | FK/unique constraint |
| System | `METHOD_NOT_ALLOWED` | 405 | HTTP method sai |
| System | `INTERNAL_SERVER_ERROR` | 500 | Lỗi không xác định |

## Flow xử lý exception

```
1. Service: throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS)
2. GlobalExceptionHandler.handleAppException() bắt
3. Tạo traceId (từ MDC hoặc UUID)
4. Log: log.warn("Business exception [EMAIL_ALREADY_EXISTS]: ... | traceId=xxx")
5. Trả về client:
   {
     "success": false,
     "code": "EMAIL_ALREADY_EXISTS",
     "message": "Email đã được sử dụng",
     "traceId": "abc-123"
   }
```
