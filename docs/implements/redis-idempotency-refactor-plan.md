# Redis Idempotency Refactor Plan

Tài liệu này là plan refactor idempotency key từ PostgreSQL table `idempotency_records` sang Redis cho The Athenaeum.

Mục tiêu: giữ nguyên behavior hiện tại của API idempotent, nhưng loại bỏ việc idempotency record bị phụ thuộc vào transaction SQL của nghiệp vụ chính.

## 1. Trạng thái hiện tại trong codebase

### Trạng thái sau refactor

Các phần đã được triển khai:

```text
IdempotencyServiceImpl dùng Redis StringRedisTemplate.
IdempotencyRecordPayload là Redis JSON payload, không phải JPA entity.
IdempotencyRecord JPA entity đã bị xóa khỏi Java runtime.
IdempotencyRecordRepository đã bị xóa khỏi Java runtime.
Business action vẫn chạy trong SQL transaction riêng bằng TransactionTemplate.
Retry COMPLETED/FAILED/PROCESSING/hash mismatch đã có unit test.
Không drop bảng idempotency_records trong database ở phase này.
```

Các file chính:

```text
src/main/java/com/vn/service/IdempotencyService.java
src/main/java/com/vn/service/impl/IdempotencyServiceImpl.java
src/main/java/com/vn/service/impl/idempotency/IdempotencyRecordPayload.java
src/main/java/com/vn/enums/IdempotencyStatus.java
```

Migration hiện tại tạo bảng:

```text
src/main/resources/db/migration/V13__circulation_core_upgrade.sql
```

Các API hiện đang gọi `IdempotencyService.execute(...)`:

```text
POST /api/staff/circulation/checkouts
POST /api/staff/circulation/checkins
PUT  /api/borrows/{borrowId}/extend
PUT  /api/staff/borrows/{borrowId}/extend
POST /api/staff/holds/{holdId}/checkout
```

Các class gọi:

```text
CirculationServiceImpl.checkout(...)
CirculationServiceImpl.checkin(...)
CirculationServiceImpl.renewMyBorrow(...)
CirculationServiceImpl.staffRenewBorrow(...)
HoldServiceImpl.checkoutHold(...)
```

Project đã có Redis:

```text
spring-boot-starter-data-redis
StringRedisTemplate
spring.data.redis.repositories.enabled=false
RedisTokenService
EmailVerificationRateLimitService
```

Vì vậy refactor này không cần thêm dependency lớn. Có thể dùng `StringRedisTemplate`.

## 2. Nhận định về gợi ý Redis

Gợi ý chuyển idempotency sang Redis là hợp lý, nhưng cần hiểu đúng phạm vi.

Đúng:

```text
SETNX / setIfAbsent là atomic.
Redis key không rollback theo transaction SQL.
TTL giúp tự dọn idempotency key.
Không cần bảng SQL idempotency_records để chống duplicate retry.
Không cần REQUIRES_NEW cho phần ghi idempotency record.
```

Nhưng không nên nói Redis làm hệ thống "hết lo transaction hoàn toàn".

Vẫn phải xử lý:

```text
Business logic vẫn cần transaction SQL.
Vẫn có dual-write: SQL commit thành công nhưng update Redis COMPLETED thất bại.
Vẫn có stale PROCESSING nếu app chết giữa chừng.
Vẫn phải quyết định policy khi Redis unavailable.
Vẫn phải serialize/deserialize response cẩn thận.
```

Kết luận thiết kế:

```text
Redis giữ trạng thái idempotency.
PostgreSQL vẫn giữ transaction nghiệp vụ chính.
IdempotencyService vẫn nên mở transaction cho action.get(), vì các wrapper service hiện đã bỏ @Transactional.
```

## 3. Thiết kế Redis key

Không dùng raw path trực tiếp vì path có `/`, `{`, `}` nhìn khó quản lý. Nên hash/sanitize scope.

Đề xuất key:

```text
idempotency:{actorId}:{method}:{scopeHash}:{idempotencyKey}
```

Trong đó:

```text
actorId        = member/staff/admin id đang gọi request
method         = POST / PUT
scopeHash      = SHA-256(normalizedPath), lấy 16-32 ký tự đầu cho gọn
idempotencyKey = header Idempotency-Key đã trim
```

Ví dụ:

```text
idempotency:15:POST:8b91f4a11a20f620:abc-123
```

Lý do vẫn dùng `normalizedPath` trong hash input:

```text
PUT /api/borrows/{borrowId}/extend
```

ổn định hơn raw path:

```text
PUT /api/borrows/9001/extend
```

vì cùng một loại thao tác dùng chung scope endpoint.

## 4. Redis value JSON

Không nên giữ `IdempotencyRecord` là JPA entity nữa.

Nên tạo model riêng, ví dụ:

```text
src/main/java/com/vn/service/impl/idempotency/IdempotencyRecordPayload.java
```

Đây là object Java thuần để serialize thành JSON trong Redis.

Field đề xuất:

```java
public record IdempotencyRecordPayload(
        Long actorId,
        String httpMethod,
        String normalizedPath,
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        Integer responseCode,
        String responseBody,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
}
```

Không cần field:

```text
id
expiresAt
```

Lý do:

```text
id        -> Redis key chính là primary key.
expiresAt -> Redis TTL thay thế expires_at.
```

Nên để `responseBody` là `String`, không để `Object`.

Lý do:

```text
responseBody String giúp replay response bằng objectMapper.readValue(responseBody, responseType).
Nếu để Object, Jackson thường deserialize về LinkedHashMap, sau đó convertValue vẫn được nhưng dễ khó debug hơn.
```

## 5. TTL policy

Giữ policy hiện tại:

```text
IDEMPOTENCY_TTL_HOURS = 24
```

Nên dùng cùng TTL cho mọi trạng thái:

```text
PROCESSING -> 24h
COMPLETED  -> 24h
FAILED     -> 24h
```

Lý do:

```text
Đơn giản.
Đủ cho retry gần thời điểm request.
Không cần scheduled cleanup job.
```

Optional sau này:

```text
PROCESSING TTL ngắn hơn, ví dụ 15-30 phút.
COMPLETED/FAILED TTL 24h.
```

Nhưng phase đầu không cần, vì sẽ làm code nhiều nhánh hơn.

## 6. Flow xử lý mới

### 6.1. Request mới

```text
Client gửi Idempotency-Key
↓
Backend validate key không blank
↓
Build requestHash = SHA-256(method + normalizedPath + canonicalBody)
↓
Build redisKey = actorId + method + normalizedPath scope + idempotencyKey
↓
SETNX redisKey PROCESSING_JSON TTL 24h
↓
Nếu SETNX true:
    chạy business action trong SQL transaction
    nếu success:
        SET redisKey COMPLETED_JSON TTL 24h
        return response
    nếu AppException:
        SET redisKey FAILED_JSON TTL 24h
        throw lại lỗi
    nếu RuntimeException hệ thống:
        policy phase đầu: SET FAILED_JSON hoặc DELETE key
```

Policy cho lỗi hệ thống:

```text
Với AppException: cache FAILED để retry không chạy lại nghiệp vụ.
Với RuntimeException không kiểm soát: nên cache FAILED INTERNAL_SERVER_ERROR.
```

Lý do không delete key ngay:

```text
Nếu lỗi xảy ra sau khi business SQL đã commit nhưng Redis update cuối lỗi, delete key có thể làm retry chạy lại nghiệp vụ.
FAILED an toàn hơn cho side effect.
```

### 6.2. Retry cùng request

```text
SETNX redisKey ... -> false
↓
GET redisKey
↓
Nếu requestHash khác:
    throw IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST
Nếu status COMPLETED:
    deserialize responseBody -> responseType
    return cached response
Nếu status FAILED:
    throw AppException theo errorCode cũ
Nếu status PROCESSING:
    throw REQUEST_ALREADY_PROCESSING
```

### 6.3. Redis key hết hạn

Sau TTL, Redis xóa key.

Nếu client retry sau 24h:

```text
Request được xem như request mới.
Business constraint vẫn phải bảo vệ duplicate nghiệp vụ.
```

Ví dụ:

```text
Checkout cùng barcode sau 24h vẫn bị chặn bởi status BORROWED hoặc open borrow constraint.
Renew cùng borrow sau 24h vẫn bị chặn bởi renewCount/maxRenewals.
```

Idempotency không thay thế business constraint.

## 7. Transaction sau khi chuyển sang Redis

Điểm quan trọng nhất của codebase hiện tại:

```text
CirculationServiceImpl.checkout/checkin/renew...
HoldServiceImpl.checkoutHold...
```

các method này hiện không còn `@Transactional` sau lần fix trước.

Vì vậy Redis version của `IdempotencyServiceImpl` vẫn phải giữ transaction cho business action:

```java
T response = businessTransactionTemplate.execute(status -> action.get());
```

Nghĩa là:

```text
Redis SETNX PROCESSING không nằm trong SQL transaction.
action.get() nằm trong SQL transaction riêng.
Redis SET COMPLETED/FAILED không nằm trong SQL transaction.
```

Không nên chỉ gọi:

```java
T response = action.get();
```

vì như vậy các use case JPA có thể không còn transaction bao ngoài rõ ràng.

## 8. Refactor code theo phase

### Phase 1: Thêm Redis payload model

Thêm package:

```text
src/main/java/com/vn/service/impl/idempotency/
```

Thêm:

```text
IdempotencyRecordPayload.java
```

Giữ enum hiện tại:

```text
IdempotencyStatus
```

Không cần đổi interface:

```text
IdempotencyService.execute(...)
```

### Phase 2: Refactor IdempotencyServiceImpl

Sửa dependency:

Remove:

```text
IdempotencyRecordRepository
idempotencyTransactionTemplate
```

Keep:

```text
businessTransactionTemplate
ObjectMapper
```

Add:

```text
StringRedisTemplate
```

Các method cần có:

```text
buildRedisKey(...)
writePayload(...)
readPayload(...)
registerProcessing(...)
markCompleted(...)
markFailed(...)
readCachedResponse(...)
readCachedFailure(...)
buildRequestHash(...)
sha256(...)
```

Pseudo-code:

```java
Boolean inserted = redisTemplate.opsForValue()
        .setIfAbsent(redisKey, processingJson, ttl);

if (Boolean.FALSE.equals(inserted)) {
    IdempotencyRecordPayload existing = readExisting(redisKey);
    validateHash(existing, requestHash);
    return replayOrReject(existing, responseType);
}

try {
    T response = businessTransactionTemplate.execute(status -> action.get());
    redisTemplate.opsForValue().set(redisKey, completedJson, ttl);
    return response;
} catch (RuntimeException e) {
    redisTemplate.opsForValue().set(redisKey, failedJson, ttl);
    throw e;
}
```

### Phase 3: Bỏ JPA idempotency khỏi runtime

Xóa hoặc ngưng dùng:

```text
src/main/java/com/vn/entity/IdempotencyRecord.java
src/main/java/com/vn/repository/IdempotencyRecordRepository.java
```

Khuyến nghị phase đầu:

```text
Xóa Java entity/repository khỏi code.
Chưa drop bảng SQL ngay.
```

Lý do:

```text
Extra table trong PostgreSQL không làm JPA validate lỗi.
Không cần sửa migration cũ V13.
Không tạo destructive migration ngay khi đang refactor logic.
```

Optional sau khi chạy ổn:

```text
V20__drop_idempotency_records.sql
DROP TABLE IF EXISTS idempotency_records;
```

Nếu muốn final schema sạch cho đồ án thì có thể thêm migration drop ở bước sau, nhưng không nên sửa trực tiếp `V13__circulation_core_upgrade.sql` vì migration đã chạy rồi.

### Phase 4: Update tests

Test hiện tại:

```text
src/test/java/com/vn/service/idempotency/IdempotencyServiceImplTest.java
```

cần đổi từ mock repository sang mock `StringRedisTemplate`.

Các case bắt buộc giữ:

```text
1. Missing Idempotency-Key -> IDEMPOTENCY_KEY_REQUIRED.
2. Request mới success -> SETNX PROCESSING, action chạy, SET COMPLETED.
3. Request mới AppException -> SET FAILED, throw lỗi gốc.
4. Retry COMPLETED cùng hash -> không chạy action, return cached response.
5. Retry FAILED cùng hash -> không chạy action, throw lỗi cũ.
6. Retry PROCESSING cùng hash -> REQUEST_ALREADY_PROCESSING.
7. Retry cùng key nhưng khác body -> IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.
8. Redis GET null sau SETNX false -> INTERNAL_SERVER_ERROR hoặc retry-safe policy.
```

Controller/use case tests không cần đổi nếu `IdempotencyService` interface giữ nguyên.

### Phase 5: Verify

Chạy:

```text
.\mvnw.cmd test
```

Sau đó test manual với Swagger/Postman:

```text
POST /api/staff/circulation/checkouts
Idempotency-Key: same-key
```

Kỳ vọng:

```text
Lần 1: tạo borrow.
Lần 2 cùng body/key: trả lại response cũ.
Lần 2 khác body/cùng key: 409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.
Hai request song song cùng key: một request xử lý, request còn lại REQUEST_ALREADY_PROCESSING hoặc replay sau khi completed.
```

## 9. Redis outage policy

Vì các API idempotent là write API có side effect, phase đầu nên chọn fail-closed:

```text
Nếu Redis lỗi khi register PROCESSING:
    throw INTERNAL_SERVER_ERROR.
    Không chạy nghiệp vụ.
```

Lý do:

```text
Nếu Redis chết mà vẫn chạy checkout/checkin/renew, request retry có thể tạo side effect trùng.
Fail-closed an toàn hơn fail-open.
```

Nếu Redis lỗi sau khi business SQL đã commit nhưng trước khi ghi COMPLETED/FAILED:

```text
Không thể rollback SQL từ Redis layer.
Client có thể nhận lỗi 500 dù nghiệp vụ đã commit.
Business constraints vẫn phải bảo vệ retry.
```

Đây là vấn đề dual-write. Phase đầu chấp nhận vì:

```text
Đồ án không cần Kafka/outbox/distributed transaction.
Business constraints đã có vai trò bảo vệ dữ liệu chính.
TTL Redis chỉ xử lý stale key, không giải quyết hoàn toàn dual-write.
```

Nếu muốn production hơn sau này:

```text
Outbox pattern cho response/result.
Hoặc giữ SQL idempotency table nếu cần audit/replay bền vững tuyệt đối.
```

## 10. Có nên drop bảng idempotency_records không?

Có 2 lựa chọn.

### Lựa chọn A: Không drop bảng ngay

Khuyến nghị cho phase refactor đầu.

Ưu điểm:

```text
Ít rủi ro.
Không thêm destructive migration.
Không cần đụng migration V13.
Rollback code dễ hơn nếu Redis version có lỗi.
```

Nhược điểm:

```text
Database còn một bảng không dùng.
Nhìn schema chưa sạch.
```

### Lựa chọn B: Drop bằng migration mới

Chỉ làm sau khi Redis idempotency đã test ổn.

Migration:

```sql
DROP TABLE IF EXISTS idempotency_records;
```

Ưu điểm:

```text
Schema sạch.
Không còn hiểu nhầm idempotency đang dùng SQL.
```

Nhược điểm:

```text
Destructive migration.
Nếu muốn quay lại SQL idempotency thì phải tạo lại bảng.
```

Kết luận:

```text
Phase implement nên chọn A.
Sau khi ổn mới quyết định B.
```

## 11. Những thứ không nên đổi

Không đổi:

```text
Header Idempotency-Key.
IdempotencyService interface.
Danh sách API đang dùng idempotency.
requestHash SHA-256.
normalizedPath scope.
ErrorCode hiện tại.
Business rules checkout/checkin/renew/checkout hold.
```

Không đưa idempotency vào:

```text
GET API.
PATCH update metadata thông thường.
Các read-only flow.
```

## 12. Checklist implement

```text
[x] Thêm IdempotencyRecordPayload model cho Redis JSON.
[x] Refactor IdempotencyServiceImpl dùng StringRedisTemplate.
[x] Giữ businessTransactionTemplate để bọc action.get().
[x] Xóa dependency IdempotencyRecordRepository khỏi service.
[x] Xóa entity/repository idempotency khỏi Java code hoặc để tạm nhưng không dùng.
[x] Update IdempotencyServiceImplTest sang Redis behavior.
[x] Chạy toàn bộ test.
[ ] Manual test retry COMPLETED/FAILED/PROCESSING/hash mismatch.
[ ] Sau khi ổn mới cân nhắc migration drop idempotency_records.
```

## 13. Kết luận

Chuyển idempotency sang Redis là hợp lý với hệ thống này vì project đã dùng Redis cho token và rate limit.

Thiết kế nên giữ tinh thần:

```text
Redis = idempotency state ngắn hạn, atomic SETNX, TTL cleanup.
PostgreSQL = dữ liệu nghiệp vụ chính, transaction cho checkout/checkin/renew.
Business constraints = lớp bảo vệ cuối cùng chống duplicate dữ liệu thật.
```

Điểm cần nhớ khi phỏng vấn:

```text
Redis giúp idempotency record không bị rollback theo transaction SQL,
nhưng không thay thế transaction của nghiệp vụ chính và không xóa hoàn toàn dual-write risk.
```
