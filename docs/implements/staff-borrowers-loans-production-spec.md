# Staff Borrowers and Loans Production Spec

## 1. Mục tiêu

Hiện tại hệ thống đã có nghiệp vụ checkout, check-in, gia hạn, holds và fines, nhưng phần staff UI chưa có màn hình đúng trọng tâm để trả lời 2 câu hỏi vận hành hằng ngày:

- "Bạn đọc này đang mượn gì, có quá hạn/phạt/hold gì không?"
- "Toàn hệ thống hiện ai đang mượn gì, sách nào quá hạn hoặc sắp đến hạn?"

Không nên nhét danh sách người mượn vào `Staff Books`. `Staff Books` nên giữ vai trò catalog/copy inventory: đầu sách, bản copy, barcode, vị trí, tình trạng. Nghiệp vụ mượn trả nên tách thành 2 hướng:

- `Staff Members/Borrowers`: borrower-centric, bắt đầu từ người mượn.
- `Staff Loans`: loan-centric, bắt đầu từ lượt mượn.

Spec này mô tả cách implement production-ready cho backend Spring Boot hiện tại và contract để frontend dùng.

## 2. Phạm vi

### In scope cho phase 1

- Thêm đúng 4 API staff MVP:
  - `GET /api/staff/members?q=&status=&hasOverdue=&page=&size=`
  - `GET /api/staff/members/{memberId}`
  - `GET /api/staff/members/{memberId}/loans?openOnly=true&status=&overdue=&page=&size=`
  - `GET /api/staff/loans?q=&status=&openOnly=&overdue=&dueFrom=&dueTo=&page=&size=`
- API `/api/staff/members/{memberId}/loans` dùng `openOnly=true` cho open loans và `openOnly=false` cho borrow history, không tạo endpoint history riêng trong phase 1.
- Reuse action đã có:
  - Renew: `PUT /api/staff/borrows/{borrowId}/extend`
  - Check-in: `POST /api/staff/circulation/checkins`
- Bổ sung DTO riêng cho staff để có đủ dữ liệu hiển thị, không lộ password/token.
- Bổ sung repository query tối ưu, tránh N+1.
- Bổ sung Swagger docs, unit tests, slice/controller tests.

### Out of scope cho phase 1

- Staff notes nội bộ.
- Change due date.
- Claim returned.
- Declare lost workflow riêng.
- Fine payment/waive endpoint.
- Endpoint riêng cho borrow history: `GET /api/staff/members/{memberId}/loans/history`.
- Endpoint riêng cho holds của member: `GET /api/staff/members/{memberId}/holds`.
- Endpoint riêng cho fines của member: `GET /api/staff/members/{memberId}/fines`.
- Endpoint chi tiết một loan: `GET /api/staff/loans/{borrowId}`.
- Export CSV streaming.

Các mục out of scope nên làm phase 2 vì cần thêm audit, permission, idempotency và có thể cần migration bảng mới.

## 3. Hiện trạng codebase

Các entity có sẵn:

- `Member`: `id`, `fullName`, `email`, `phone`, `role`, `status`, `maxBorrowLimit`, `membershipExpiresAt`.
- `BorrowRecord`: trỏ tới `Member` và `BookCopy`, có `borrowedAt`, `dueDate`, `returnedAt`, `status`, `renewCount`, `maxRenewalsAtCheckout`, fine fields.
- `BookCopy`: trỏ tới `Book`, có `barcode`, `status`, `condition`, `location`.
- `Book`: catalog-level data như `title`, `isbn`, `category`, `authors`.
- `Reservation`: hold queue theo `member` và `book`, có `status`, `queuePosition`, `assignedCopy`.

Các enum liên quan:

- `BorrowStatus`: `BORROWED`, `RETURNED`, `OVERDUE`, `LOST`
- `ReservationStatus`: `WAITING`, `NOTIFIED`, `READY_FOR_PICKUP`, `FULFILLED`, `CANCELLED`, `EXPIRED`
- `MemberRole`: `MEMBER`, `LIBRARIAN`, `ADMIN`
- `MemberStatus`: `PENDING_VERIFICATION`, `ACTIVE`, `INACTIVE`, `BANNED`
- `FineStatus`: `NONE`, `UNPAID`, `PAID`, `WAIVED`

Các API hiện có:

- Member self view:
  - `GET /api/me`
  - `PATCH /api/me`
- Member borrow/fine/hold:
  - `GET /api/borrows/my`
  - `GET /api/borrows/my/history`
  - `GET /api/holds/my`
  - `GET /api/fines/my`
- Staff circulation actions:
  - `POST /api/staff/circulation/checkouts/preview`
  - `POST /api/staff/circulation/checkouts`
  - `POST /api/staff/circulation/checkins`
  - `PUT /api/staff/borrows/{borrowId}/extend`
  - `POST /api/staff/holds/{holdId}/checkout`

Khoảng trống hiện tại:

- Chưa có `GET /api/staff/members`.
- Chưa có `GET /api/staff/members/{memberId}`.
- Chưa có staff API để xem loans của member bất kỳ.
- Chưa có `GET /api/staff/loans` để staff lọc toàn bộ borrow records.

## 4. Role và security

Tất cả API trong spec này yêu cầu:

```java
@PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
```

Không cho `MEMBER` truy cập các API staff này.

`SecurityConfig` hiện đã để `.anyRequest().authenticated()`, nên chỉ cần dùng method security ở controller giống các controller hiện tại. Không cần thêm route-specific matcher trừ khi muốn tinh chỉnh public endpoints.

Không trả về:

- `password`
- verification token
- refresh token
- Redis/idempotency internals

Audit/log:

- Read API nên log mức service với `eventType=STAFF_SEARCH_MEMBERS`, `STAFF_GET_MEMBER_PROFILE`, `STAFF_SEARCH_LOANS`.
- Mutating action dùng lại log hiện tại của renew/checkin/checkout.
- Nếu sau này có change due date, waive fine, staff notes thì bắt buộc thêm audit log cấp nghiệp vụ.

## 5. API Contract

Phase 1 chỉ implement 4 API MVP:

```http
GET /api/staff/members?q=&status=&hasOverdue=&page=&size=
GET /api/staff/members/{memberId}
GET /api/staff/members/{memberId}/loans?openOnly=true&status=&overdue=&page=&size=
GET /api/staff/loans?q=&status=&openOnly=&overdue=&dueFrom=&dueTo=&page=&size=
```

Các endpoint history riêng, holds, fines và loan detail chuyển sang phase 2. Borrow history trong phase 1 dùng lại `/api/staff/members/{memberId}/loans?openOnly=false`.

### 5.1 GET `/api/staff/members`

Mục đích: danh sách borrower/member cho staff.

Query params:

| Param | Type | Required | Default | Notes |
|---|---|---:|---|---|
| `q` | string | no | | Search theo `memberId`, `fullName`, `email`, `phone` |
| `status` | string | no | | `PENDING_VERIFICATION`, `ACTIVE`, `INACTIVE`, `BANNED` |
| `hasOverdue` | boolean | no | | Chỉ lấy member có loan quá hạn |
| `page` | int | no | `0` | Min 0 |
| `size` | int | no | `20` | Max 100 |

Response:

```json
{
  "success": true,
  "message": "Lấy danh sách bạn đọc thành công",
  "data": [
    {
      "memberId": 2,
      "fullName": "Nguyen Van A",
      "email": "member@example.com",
      "phone": "0900000000",
      "role": "MEMBER",
      "status": "ACTIVE",
      "maxBorrowLimit": 5,
      "membershipExpiresAt": null,
      "activeLoansCount": 2,
      "overdueLoansCount": 1,
      "activeHoldsCount": 1,
      "unpaidFineTotal": 15000.00,
      "createdAt": "2026-05-01T08:00:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "timestamp": "2026-05-25T08:00:00"
}
```

DTO:

```java
public record StaffMemberListItemResponse(
        Long memberId,
        String fullName,
        String email,
        String phone,
        String role,
        String status,
        Integer maxBorrowLimit,
        Instant membershipExpiresAt,
        long activeLoansCount,
        long overdueLoansCount,
        long activeHoldsCount,
        BigDecimal unpaidFineTotal,
        Instant createdAt
) {}
```

Counting rules:

- `activeLoansCount`: borrow status in `BorrowStatus.activeStatuses()` = `BORROWED`, `OVERDUE`.
- `overdueLoansCount`: `status = OVERDUE` OR (`status = BORROWED` AND `dueDate < now`). Dùng cả 2 để UI đúng ngay cả khi overdue job chưa chạy.
- `activeHoldsCount`: reservation status in `ReservationStatus.activeStatuses()`.
- `unpaidFineTotal`: `fineAmount > 0`, `finePaidAt is null`, `fineWaivedBy is null`.

### 5.2 GET `/api/staff/members/{memberId}`

Mục đích: profile tổng quan của một member cho staff.

Response:

```json
{
  "success": true,
  "message": "Lấy hồ sơ bạn đọc thành công",
  "data": {
    "memberId": 2,
    "fullName": "Nguyen Van A",
    "email": "member@example.com",
    "phone": "0900000000",
    "role": "MEMBER",
    "status": "ACTIVE",
    "maxBorrowLimit": 5,
    "membershipExpiresAt": null,
    "activeLoansCount": 2,
    "openLoansCount": 2,
    "overdueLoansCount": 1,
    "borrowHistoryCount": 18,
    "activeHoldsCount": 1,
    "unpaidFineTotal": 15000.00,
    "createdAt": "2026-05-01T08:00:00Z",
    "updatedAt": "2026-05-10T08:00:00Z"
  },
  "timestamp": "2026-05-25T08:00:00"
}
```

DTO:

```java
public record StaffMemberDetailResponse(
        Long memberId,
        String fullName,
        String email,
        String phone,
        String role,
        String status,
        Integer maxBorrowLimit,
        Instant membershipExpiresAt,
        long activeLoansCount,
        long openLoansCount,
        long overdueLoansCount,
        long borrowHistoryCount,
        long activeHoldsCount,
        BigDecimal unpaidFineTotal,
        Instant createdAt,
        Instant updatedAt
) {}
```

### 5.3 GET `/api/staff/members/{memberId}/loans`

Mục đích: tab `Open Loans` trong hồ sơ member.

Query params:

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | string | | Optional specific status |
| `openOnly` | boolean | `true` | Khi true dùng open statuses: `BORROWED`, `OVERDUE`, `LOST` |
| `overdue` | boolean | | Chỉ quá hạn |
| `page` | int | `0` | |
| `size` | int | `20` | Max 100 |

Sort cố định trong phase 1:

- Nếu `openOnly=true`: ưu tiên `dueDate asc`.
- Nếu `openOnly=false`: ưu tiên `borrowedAt desc`.

Response item:

```json
{
  "borrowId": 10,
  "memberId": 2,
  "memberName": "Nguyen Van A",
  "memberEmail": "member@example.com",
  "bookId": 20,
  "bookTitle": "Clean Code",
  "bookCopyId": 3803,
  "itemBarcode": "LIB-2026-003516",
  "copyStatus": "BORROWED",
  "borrowedAt": "2026-05-20T08:00:00Z",
  "dueDate": "2026-06-03T08:00:00Z",
  "returnedAt": null,
  "status": "BORROWED",
  "renewCount": 0,
  "maxRenewals": 1,
  "fineAmount": 0.00,
  "fineStatus": "NONE",
  "overdue": false,
  "daysOverdue": 0
}
```

DTO:

```java
public record StaffLoanResponse(
        Long borrowId,
        Long memberId,
        String memberName,
        String memberEmail,
        Long bookId,
        String bookTitle,
        Long bookCopyId,
        String itemBarcode,
        String copyStatus,
        Instant borrowedAt,
        Instant dueDate,
        Instant returnedAt,
        String status,
        Integer renewCount,
        Integer maxRenewals,
        BigDecimal fineAmount,
        String fineStatus,
        boolean overdue,
        long daysOverdue
) {}
```

### 5.4 GET `/api/staff/loans`

Mục đích: màn tổng hợp toàn bộ loans trong hệ thống.

Query params:

| Param | Type | Required | Default | Notes |
|---|---|---:|---|---|
| `q` | string | no | | Search theo barcode, book title, ISBN, member name, member email, member phone |
| `status` | string | no | | `BORROWED`, `RETURNED`, `OVERDUE`, `LOST` |
| `openOnly` | boolean | no | `false` | Nếu true dùng `BORROWED`, `OVERDUE`, `LOST` |
| `overdue` | boolean | no | | `status=OVERDUE` OR `BORROWED` quá hạn |
| `dueFrom` | instant/date | no | | Filter due date from |
| `dueTo` | instant/date | no | | Filter due date to |
| `page` | int | no | `0` | |
| `size` | int | no | `20` | Max 100 |

Sort cố định trong phase 1: `dueDate asc`, sau đó `borrowedAt desc`.

Examples:

```http
GET /api/staff/loans?openOnly=true&overdue=true&page=0&size=20
GET /api/staff/loans?q=LIB-2026-003516
GET /api/staff/loans?q=phuc&status=BORROWED
GET /api/staff/loans?dueFrom=2026-05-25T00:00:00Z&dueTo=2026-06-01T00:00:00Z
```

Response:

```json
{
  "success": true,
  "message": "Lấy danh sách lượt mượn thành công",
  "data": [
    {
      "borrowId": 10,
      "memberId": 2,
      "memberName": "Nguyen Van A",
      "memberEmail": "member@example.com",
      "bookId": 20,
      "bookTitle": "Clean Code",
      "bookCopyId": 3803,
      "itemBarcode": "LIB-2026-003516",
      "copyStatus": "BORROWED",
      "borrowedAt": "2026-05-20T08:00:00Z",
      "dueDate": "2026-06-03T08:00:00Z",
      "returnedAt": null,
      "status": "BORROWED",
      "renewCount": 0,
      "maxRenewals": 1,
      "fineAmount": 0.00,
      "fineStatus": "NONE",
      "overdue": false,
      "daysOverdue": 0
    }
  ],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  },
  "timestamp": "2026-05-25T08:00:00"
}
```

## 6. Frontend Screen Contract

### 6.0 Naming convention

Backend code nên dùng tên `StaffMember` và `StaffLoan`.

Lý do:

- Entity/database hiện tại dùng `Member`, không dùng `Borrower` hoặc `Patron`.
- Existing code đã có `MemberService`, `MemberRepository`, `MemberUserDetails`, `MemberRole`, `MemberStatus`.
- Route `/api/staff/members` nhất quán với domain hiện có.
- `Loan` là tên đọc nghiệp vụ cho một dòng `borrow_records`; dùng `StaffLoan` giúp staff UI dễ hiểu hơn so với `StaffBorrowRecord`.

Không dùng `StaffPatron` trong code phase này vì `patron` là thuật ngữ thư viện quốc tế nhưng chưa xuất hiện trong codebase, dễ tạo thêm một bộ từ vựng mới không cần thiết.

Không dùng `StaffBorrower` trong code phase này vì `Borrower` chỉ là một vai trò/ngữ cảnh của `Member`. Một `Member` có thể có hoặc chưa có loan, nhưng vẫn cần xuất hiện trong staff member list.

UI vẫn có thể dùng nhãn thân thiện hơn:

- Menu/page label: `Borrowers`
- Table/list title: `Borrowers`
- Loan page label: `Active Loans` hoặc `Loans`

Mapping đề xuất:

| Layer | Name |
|---|---|
| Backend controller/service | `StaffMember`, `StaffLoan` |
| API route | `/api/staff/members`, `/api/staff/loans` |
| Frontend route | `/staff/members`, `/staff/loans` |
| Frontend navigation label | `Borrowers`, `Active Loans` |

### 6.1 `/staff/members`

UI:

- Search input: name/email/phone/member ID.
- Filters phase 1: status, has overdue.
- Table columns:
  - Member ID
  - Full name
  - Email
  - Phone
  - Status
  - Role
  - Active loans
  - Overdue
  - Active holds
  - Fine total
  - Action: View profile

Backend endpoint:

```http
GET /api/staff/members
```

### 6.2 `/staff/members/{memberId}`

Tabs:

- `Overview`: `GET /api/staff/members/{memberId}`
- `Open Loans`: `GET /api/staff/members/{memberId}/loans?openOnly=true`
- `Borrow History`: `GET /api/staff/members/{memberId}/loans?openOnly=false`

Không làm tab `Holds` và `Fines` bằng API riêng trong phase 1. Phase 1 chỉ hiển thị summary count/total từ `GET /api/staff/members/{memberId}` nếu UI cần. Danh sách holds/fines chi tiết để phase 2.

Actions in `Open Loans`:

- `Renew`: call existing `PUT /api/staff/borrows/{borrowId}/extend` with `Idempotency-Key`.
- `Check-in`: call existing `POST /api/staff/circulation/checkins` with `itemBarcode` and `Idempotency-Key`.

### 6.3 `/staff/loans`

UI:

- Search input: barcode/title/member/email/phone.
- Quick filters:
  - Active/Open
  - Overdue
  - Due today
  - Due this week
  - Lost
  - Returned
- Table columns:
  - Borrow ID
  - Member
  - Email
  - Book title
  - Barcode
  - Borrowed at
  - Due date
  - Status
  - Renew count
  - Fine
  - Actions: Renew, Check-in, Open member, Open copy/book

Backend endpoint:

```http
GET /api/staff/loans
```

## 7. Backend Implementation Plan

### 7.1 Package layout

Add:

```text
src/main/java/com/vn/controller/StaffMemberController.java
src/main/java/com/vn/controller/StaffLoanController.java
src/main/java/com/vn/controller/docs/StaffMemberApiDocs.java
src/main/java/com/vn/controller/docs/StaffLoanApiDocs.java

src/main/java/com/vn/service/StaffMemberService.java
src/main/java/com/vn/service/StaffLoanService.java
src/main/java/com/vn/service/impl/StaffMemberServiceImpl.java
src/main/java/com/vn/service/impl/StaffLoanServiceImpl.java

src/main/java/com/vn/dto/staff/member/response/StaffMemberListItemResponse.java
src/main/java/com/vn/dto/staff/member/response/StaffMemberDetailResponse.java
src/main/java/com/vn/dto/staff/loan/response/StaffLoanResponse.java

src/main/java/com/vn/mapper/StaffCirculationMapper.java
```

Optional if dynamic query gets too large:

```text
src/main/java/com/vn/repository/custom/StaffLoanSearchRepository.java
src/main/java/com/vn/repository/custom/StaffLoanSearchRepositoryImpl.java
src/main/java/com/vn/repository/custom/StaffMemberSearchRepository.java
src/main/java/com/vn/repository/custom/StaffMemberSearchRepositoryImpl.java
```

### 7.2 Controller design

Use same response wrapper pattern:

```java
ResponseEntity<ApiResponse<List<StaffLoanResponse>>>
```

Use `PageMeta.from(page)`.

Validate enum query params in controller or service:

- Trim + uppercase.
- Bad enum -> `AppException(ErrorCode.BAD_REQUEST)`.

### 7.3 Service design

`StaffMemberService`:

```java
Page<StaffMemberListItemResponse> searchMembers(StaffMemberSearchRequest request);
StaffMemberDetailResponse getMemberDetail(Long memberId);
Page<StaffLoanResponse> getMemberLoans(Long memberId, StaffLoanSearchRequest request);
```

`getMemberLoans` phải xử lý cả open loans và borrow history thông qua `openOnly`.

`StaffLoanService`:

```java
Page<StaffLoanResponse> searchLoans(StaffLoanSearchRequest request);
```

Keep services read-only:

```java
@Transactional(readOnly = true)
```

### 7.4 Query strategy

For `/api/staff/members`:

Do not join borrow records and holds directly into the member page query because pagination + one-to-many joins can duplicate rows and inflate counts.

Production-safe approach:

1. Query page of `Member` by filters.
2. Extract member IDs from page.
3. Batch query aggregate stats by those IDs:
   - active loans count
   - overdue loans count
   - active holds count
   - unpaid fine total
4. Map stats back to each list item.

For `/api/staff/loans`:

Use one query from `BorrowRecord` join fetch/select needed fields:

- `borrow.member`
- `borrow.bookCopy`
- `borrow.bookCopy.book`

Use DTO projection or entity graph plus mapper. DTO projection is preferred for large staff tables because it avoids loading unused entity columns and reduces lazy-loading risk.

### 7.5 Repository additions

Add member search:

```java
Page<Member> searchStaffMembers(
        String q,
        MemberStatus status,
        Boolean hasOverdue,
        Pageable pageable
);
```

For complex optional filters, prefer custom repository implementation over a very large `@Query`.

Add aggregate projections:

```java
public record MemberBorrowStats(
        Long memberId,
        long activeLoansCount,
        long overdueLoansCount,
        long openLoansCount,
        long borrowHistoryCount,
        BigDecimal unpaidFineTotal
) {}

public record MemberHoldStats(
        Long memberId,
        long activeHoldsCount
) {}
```

Add loan search:

```java
Page<StaffLoanResponse> searchStaffLoans(StaffLoanSearchCriteria criteria, Pageable pageable);
```

Search rules:

- `q` numeric only can match `borrow.id`, `member.id`, `book.id`, `bookCopy.id`.
- `q` text matches lowercase:
  - `member.fullName`
  - `member.email`
  - `member.phone`
  - `book.title`
  - `book.isbn`
  - `copy.barcode`

### 7.6 Overdue calculation

Use service helper:

```java
boolean isOverdue(BorrowRecord borrow, Instant now) {
    return borrow.getStatus() == BorrowStatus.OVERDUE
            || (borrow.getStatus() == BorrowStatus.BORROWED && borrow.getDueDate().isBefore(now));
}
```

`daysOverdue`:

```java
long days = Duration.between(borrow.getDueDate(), now).toDays();
return Math.max(days, 0);
```

For date-only UI display, frontend can show "Due today", "1 day overdue", etc.

## 8. Database and Index Plan

Phase 1 does not require new business tables.

Recommended migration:

```sql
-- V22__staff_member_loan_search_indexes.sql

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_members_role_status
    ON members(role, status);

CREATE INDEX IF NOT EXISTS idx_members_full_name_trgm
    ON members USING gin (lower(full_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_members_email_trgm
    ON members USING gin (lower(email) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_members_phone_trgm
    ON members USING gin (coalesce(phone, '') gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_book_copies_barcode_lower
    ON book_copies(lower(barcode));

CREATE INDEX IF NOT EXISTS idx_borrow_records_status_due_date
    ON borrow_records(status, due_date);

CREATE INDEX IF NOT EXISTS idx_borrow_records_member_due
    ON borrow_records(member_id, due_date DESC);

CREATE INDEX IF NOT EXISTS idx_reservations_member_status
    ON reservations(member_id, status);
```

Notes:

- Current schema already has `idx_borrow_member_status` and `idx_borrow_overdue`; keep them.
- `pg_trgm` improves `%keyword%` search. If deployment DB policy disallows extensions, fall back to lower btree indexes and prefix search.
- Do not add table for fines yet because current domain stores fine fields on `borrow_records`.

## 9. Error Handling

Use existing `ErrorCode`:

- Member not found: `RESOURCE_NOT_FOUND`
- Borrow not found: `RESOURCE_NOT_FOUND`
- Bad enum/date range: `BAD_REQUEST`
- Unauthorized principal: `UNAUTHORIZED`
- Staff role missing: Spring security -> `ACCESS_DENIED`

Date validation:

- `dueFrom > dueTo` -> `BAD_REQUEST`
- `size > 100` should clamp or reject. Prefer clamp for consistency with current services.

## 10. Swagger/OpenAPI

Add docs interfaces:

- `StaffMemberApiDocs`
- `StaffLoanApiDocs`

Tags:

```java
@Tag(name = "Staff Members", description = "Staff APIs for borrower profiles and borrower-centric circulation views")
@Tag(name = "Staff Loans", description = "Staff APIs for loan-centric circulation search and details")
@SecurityRequirement(name = "Bearer Authentication")
```

Document:

- Query params and allowed values.
- Example responses.
- Permission requirement.
- Pagination metadata.

## 11. Tests

### Unit tests

`StaffMemberServiceImplTest`:

- Search members returns aggregate counts correctly.
- `overdueLoansCount` counts both `OVERDUE` and `BORROWED` past due.
- Not found member -> `RESOURCE_NOT_FOUND`.
- Page size clamps to max.

`StaffLoanServiceImplTest`:

- Search by barcode.
- Search by member email/name.
- Filter overdue.
- Filter due window.
- `fineStatus` resolver used correctly.

### Controller tests

`StaffMemberControllerTest`:

- `LIBRARIAN` allowed.
- `ADMIN` allowed.
- `MEMBER` denied.
- Missing auth denied.
- Bad enum -> 400 project error format.

`StaffLoanControllerTest`:

- Same security cases.
- Pagination response includes `meta`.
- Query params map into service request.

### Integration tests

If Testcontainers is available later:

- Seed member + borrowed copy + overdue copy + fine + hold.
- Assert `/api/staff/members` stats match DB.
- Assert `/api/staff/loans?overdue=true` returns both stale `BORROWED` due in past and `OVERDUE`.

## 12. Rollout Plan

1. Add DTOs, mapper and service interfaces.
2. Add repository query methods/custom repository.
3. Add controllers and Swagger docs.
4. Add migration for indexes only.
5. Add tests.
6. Run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests compile
docker compose up -d --build library-service
```

7. Frontend integrates pages:
   - `/staff/members`
   - `/staff/members/:memberId`
   - `/staff/loans`
8. Validate with real seed data:
   - One active member with active borrow.
   - One overdue borrow.
   - One returned borrow with fine.
   - One active hold.

## 13. Acceptance Criteria

- Staff can search members by ID, name, email, phone.
- Staff member list shows active loans, overdue count, holds count, fine total without N+1 queries.
- Staff can open a member profile and view overview, open loans and borrow history through `/api/staff/members/{memberId}/loans`.
- Staff can search all loans by barcode, title, member name/email.
- Staff can filter overdue, due today/due this week via date ranges.
- Existing renew/check-in actions work from the new screens using current idempotency rules.
- `MEMBER` role cannot access any `/api/staff/members/**` or `/api/staff/loans/**`.
- Swagger shows the new endpoints clearly.
- Tests cover service logic, security, bad filters and pagination.

## 14. Phase 2 Candidates

### Detailed member holds, fines, loan history endpoint and loan detail

Phase 1 intentionally does not create these endpoints because the MVP can cover the main staff screens with 4 APIs. Add these later when the UI needs dedicated tabs/detail pages:

```http
GET /api/staff/members/{memberId}/loans/history
GET /api/staff/members/{memberId}/holds
GET /api/staff/members/{memberId}/fines
GET /api/staff/loans/{borrowId}
```

Notes:

- `loans/history` is optional because phase 1 already supports `GET /api/staff/members/{memberId}/loans?openOnly=false`.
- `holds` should reuse or extend `HoldResponse`.
- `fines` should reuse `FineResponse`; current fines are stored directly on `borrow_records`, not in a separate `fines` table.
- `loans/{borrowId}` should return `StaffLoanDetailResponse` with member summary, copy/book summary, hold warning and fine details.

### Staff notes

Không triển khai trong phase 1/MVP. Không tạo migration, entity, repository, service hay API cho `member_staff_notes` ở đợt này.

Mục này chỉ ghi lại hướng mở rộng tương lai nếu sau này staff UI cần ghi chú nội bộ trên hồ sơ bạn đọc. Khi nào làm thật thì phải tách thành ticket riêng, có audit/permission rõ ràng.

Add table:

```sql
CREATE TABLE member_staff_notes (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    note TEXT NOT NULL,
    created_by BIGINT REFERENCES members(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL
);
```

API:

```http
GET /api/staff/members/{memberId}/notes
POST /api/staff/members/{memberId}/notes
PATCH /api/staff/members/{memberId}/notes/{noteId}
DELETE /api/staff/members/{memberId}/notes/{noteId}
```

### Change due date

API:

```http
PUT /api/staff/loans/{borrowId}/due-date
```

Requires:

- `Idempotency-Key`
- audit log
- reason field
- only staff/admin
- forbid `RETURNED` loans

### Export CSV

API:

```http
GET /api/staff/loans/export.csv
```

Implementation:

- Stream response.
- Reuse same filters as `/api/staff/loans`.
- Limit or async job if result set is large.
