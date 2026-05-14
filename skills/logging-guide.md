---
description: Hướng dẫn logging chuẩn doanh nghiệp với SLF4J + Logback cho dự án QuanLyThuVien
---

# Hướng Dẫn Logging

Dự án đang dùng **SLF4J + Logback** và Lombok `@Slf4j`.

Logger được tạo bằng:

```java
@Slf4j
@Service
public class AuthServiceImpl {
}
```

## Format Hiện Tại

Cấu hình nằm ở:

```text
src/main/resources/logback-spring.xml
```

Pattern đang dùng:

```xml
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [service=${serviceName}] [traceId=%X{traceId:-NO_TRACE}] %logger{36} - %msg%n
```

`serviceName` lấy từ:

```properties
app.service-name=library-service
```

Một dòng log hiện có dạng:

```text
2026-05-11 18:31:27.497 INFO  [service=library-service] [traceId=NO_TRACE] c.vn.QuanLyThuVienApplicationTests - ...
```

Với HTTP request thật:

```text
2026-05-11 18:40:12.421 INFO  [service=library-service] [traceId=7b1c9a] com.vn.logging.RequestLoggingFilter - eventType=HTTP_REQUEST_COMPLETED result=SUCCESS memberId=15 method=GET path=/api/me statusCode=200 durationMs=35
```

## Message Format

Log message trong project dùng style key-value:

```text
eventType=<EVENT> result=<SUCCESS|FAILED> memberId=<id|anonymous> entityType=<type> entityId=<id> reason=<reason> errorCode=<code> durationMs=<ms>
```

Không phải log nào cũng cần đủ mọi field. Field quan trọng nhất:

```text
eventType
result
memberId nếu có
entityType/entityId nếu tác động entity
errorCode/reason nếu lỗi
method/path/statusCode/durationMs nếu là HTTP request
```

Tên event và result được quản lý bằng enum:

```text
src/main/java/com/vn/logging/LogEvent.java
src/main/java/com/vn/logging/LogResult.java
```

Ví dụ:

```java
log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
        LogEvent.LOGIN, LogResult.SUCCESS, member.getId(), member.getId());
```

## TraceId

TraceId được tạo trong:

```text
src/main/java/com/vn/logging/RequestLoggingFilter.java
```

Flow:

```text
Request vào backend
Nếu có header X-Trace-Id thì dùng lại
Nếu không có thì tạo UUID mới
Đưa traceId vào MDC
Set response header X-Trace-Id
Logback lấy traceId từ MDC để in ra log
```

Log ngoài HTTP request, ví dụ startup hoặc background job độc lập, sẽ có:

```text
traceId=NO_TRACE
```

`@Async` gửi email vẫn giữ traceId nếu được gọi trong request, nhờ:

```text
src/main/java/com/vn/config/AsyncConfig.java
```

## Ba Nơi Log Chính

Ba nơi log khác nhau ở mục đích.

| Vị trí | Log cái gì? | Mục đích |
| --- | --- | --- |
| `RequestLoggingFilter` | Tổng kết HTTP request | Biết request nào vào, status code, duration |
| Service layer | Business/security event thành công | Biết nghiệp vụ quan trọng đã xảy ra |
| `GlobalExceptionHandler` | Lỗi nghiệp vụ / lỗi hệ thống | Biết request thất bại vì lý do gì |

### 1. RequestLoggingFilter

Log ở tầng ngoài cùng.

Nó trả lời:

```text
Request nào vào hệ thống?
Ai gọi?
Method/path là gì?
HTTP status code bao nhiêu?
Mất bao lâu?
```

Format:

```text
eventType=HTTP_REQUEST_COMPLETED result=SUCCESS memberId=15 method=GET path=/api/me statusCode=200 durationMs=35
```

Khi chưa đăng nhập:

```text
memberId=anonymous
```

Các path không log để tránh rác:

```text
/swagger-ui.html
/swagger-ui/**
/api-docs/**
/v3/api-docs/**
/favicon.ico
/actuator/health/**
```

### 2. Service Layer

Service layer chỉ nên log các business/security event **thành công và quan trọng**.

Hiện project đang log:

```text
REGISTER
VERIFY_EMAIL
LOGIN
REFRESH_TOKEN
RESEND_VERIFICATION_EMAIL
LOGOUT
SEND_VERIFICATION_EMAIL
GET_MY_PROFILE
UPDATE_MY_PROFILE
```

Ví dụ:

```text
eventType=LOGIN result=SUCCESS memberId=1 entityType=MEMBER entityId=1
```

Không nên log quá chi tiết như:

```text
Đang map DTO
Đang gọi repository
Đang save database
Đang check từng điều kiện nhỏ
```

Các log này làm application log bị nhiễu.

### 3. GlobalExceptionHandler

`GlobalExceptionHandler` log lỗi tập trung.

Nó trả lời:

```text
Request thất bại vì lỗi gì?
Error code là gì?
Lỗi business hay lỗi hệ thống?
Có cần stack trace không?
```

Ví dụ lỗi nghiệp vụ:

```text
eventType=BUSINESS_EXCEPTION result=FAILED errorCode=INVALID_CREDENTIALS reason=Email hoặc mật khẩu không đúng
```

Ví dụ lỗi validation:

```text
eventType=VALIDATION_FAILED result=FAILED errorCode=VALIDATION_ERROR reason=FIELD_VALIDATION_FAILED errorCount=2
```

Ví dụ lỗi hệ thống:

```text
eventType=UNHANDLED_EXCEPTION result=FAILED errorCode=INTERNAL_SERVER_ERROR reason=NullPointerException
```

## Flow Ví Dụ

### Login Thành Công

Service layer:

```text
eventType=LOGIN result=SUCCESS memberId=1 entityType=MEMBER entityId=1
```

Request filter:

```text
eventType=HTTP_REQUEST_COMPLETED result=SUCCESS memberId=anonymous method=POST path=/api/auth/login statusCode=200 durationMs=85
```

`memberId=anonymous` ở request login là bình thường, vì request bắt đầu khi user chưa được set vào `SecurityContext`.

### Login Sai Password

Service layer không log lỗi. Service chỉ throw:

```java
throw new AppException(ErrorCode.INVALID_CREDENTIALS);
```

GlobalExceptionHandler:

```text
eventType=BUSINESS_EXCEPTION result=FAILED errorCode=INVALID_CREDENTIALS reason=Email hoặc mật khẩu không đúng
```

Request filter:

```text
eventType=HTTP_REQUEST_COMPLETED result=FAILED memberId=anonymous method=POST path=/api/auth/login statusCode=401 durationMs=52
```

## Quy Tắc Tránh Log Trùng

Không log cùng một lỗi ở cả service và exception handler.

Không nên:

```text
Service: LOGIN_FAILED
GlobalExceptionHandler: INVALID_CREDENTIALS
RequestLoggingFilter: HTTP_REQUEST_COMPLETED FAILED
```

Nên:

```text
GlobalExceptionHandler: lý do thất bại cụ thể
RequestLoggingFilter: request summary
```

Service layer chủ yếu log success quan trọng.

Ngoại lệ: nếu cần audit/security riêng như nhiều lần login fail, tạo audit log riêng hoặc security event riêng, không spam application log.

## Log Level

### INFO

Dùng cho sự kiện bình thường, quan trọng:

```text
REGISTER SUCCESS
LOGIN SUCCESS
BOOK_BORROWED SUCCESS
HTTP_REQUEST_COMPLETED
```

### WARN

Dùng cho lỗi client/business mà hệ thống vẫn kiểm soát được:

```text
VALIDATION_FAILED
BUSINESS_EXCEPTION
ACCESS_DENIED
AUTHENTICATION_FAILED
RESOURCE_NOT_FOUND
```

### ERROR

Dùng cho lỗi thật sự cần xử lý:

```text
DATA_INTEGRITY_VIOLATION
UNHANDLED_EXCEPTION
External service failure
Email sending failure
```

Khi `log.error()`, truyền exception object làm tham số cuối nếu có:

```java
log.error("eventType={} result={} errorCode={} reason={}",
        LogEvent.UNHANDLED_EXCEPTION,
        LogResult.FAILED,
        ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
        ex.getClass().getSimpleName(),
        ex);
```

### DEBUG

Dùng khi dev debug chi tiết. Mặc định production không bật DEBUG.

Không dùng DEBUG để log dữ liệu nhạy cảm.

## Không Log Dữ Liệu Nhạy Cảm

Không được log:

```text
password
accessToken
refreshToken
verification token đầy đủ
OTP đầy đủ
Authorization header
Cookie raw
private key
SMTP password
```

Nên log identifier an toàn:

```text
memberId
entityId
errorCode
method/path
durationMs
```

Email là PII. Chỉ log email khi thật sự cần, ưu tiên `memberId`.

## Quy Tắc Code

Dùng placeholder `{}`, không nối chuỗi:

```java
log.info("eventType={} result={} memberId={}", LogEvent.LOGIN, LogResult.SUCCESS, memberId);
```

Không dùng:

```java
log.info("eventType=LOGIN memberId=" + memberId);
```

Không log trong loop trừ khi DEBUG và có lý do rõ ràng.

## Checklist Trước Khi Commit

- [ ] Không log password/token/cookie/Authorization header
- [ ] Log message dùng `eventType=` và `result=`
- [ ] Dùng `LogEvent` và `LogResult`, không gõ tay event name nếu đã có enum
- [ ] Service layer chỉ log success business quan trọng
- [ ] Exception handler log failure reason
- [ ] RequestLoggingFilter log HTTP summary
- [ ] `log.error()` có exception object ở tham số cuối nếu cần stack trace
- [ ] Dùng `{}` placeholder, không nối chuỗi
- [ ] Không log Swagger/static/health check
