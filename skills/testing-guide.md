---
description: Hướng dẫn viết testing cho Spring Boot Library Management System
---

# Hướng Dẫn Testing

Mục tiêu testing của project không phải viết thật nhiều test, mà là test đúng các phần chứng minh backend có nghiệp vụ thật:

```text
Business logic
Validation
Security
Database query
Transaction
API behavior
Edge cases
```

Project hiện tại đã có:

```text
Spring Boot 4
Spring Security
JPA
Flyway
Redis
Mail
MockMvc/WebMVC test
Security test
Testcontainers PostgreSQL
JUnit 5
Mockito
AssertJ
```

## Nguyên Tắc Tổng Quát

Chia test thành 4 tầng:

```text
1. Unit test
2. Repository test
3. Controller/API integration test
4. Security test
```

Không viết test lung tung. Mỗi tầng trả lời một câu hỏi khác nhau.

| Tầng | Test cái gì? | Dùng gì? |
| --- | --- | --- |
| Unit test | Business logic trong service | JUnit 5, Mockito, AssertJ |
| Repository test | Custom query, constraint, lock | @DataJpaTest, Testcontainers PostgreSQL |
| Controller integration test | API behavior thật | @SpringBootTest, MockMvc, Testcontainers |
| Security test | Authenticated/role access | spring-security-test, MockMvc |

## Cấu Trúc Thư Mục

Dùng cấu trúc:

```text
src/test/java/com/vn
├── unit
│   ├── mapper
│   │   └── MemberMapperTest.java
│   └── service
│       ├── AuthServiceTest.java
│       ├── MemberServiceTest.java
│       ├── BorrowServiceTest.java
│       ├── ReturnBookServiceTest.java
│       └── FineServiceTest.java
│
├── integration
│   ├── AuthControllerIntegrationTest.java
│   ├── MeControllerIntegrationTest.java
│   ├── BookRepositoryIntegrationTest.java
│   ├── BorrowControllerIntegrationTest.java
│   └── SecurityIntegrationTest.java
│
└── support
    ├── TestDataFactory.java
    ├── AbstractIntegrationTest.java
    └── WithMockMember.java
```

Hiện project đã có:

```text
src/test/java/com/vn/support/TestDataFactory.java
src/test/java/com/vn/unit/service/AuthServiceTest.java
src/test/java/com/vn/unit/service/MemberServiceTest.java
src/test/java/com/vn/unit/mapper/MemberMapperTest.java
```

## Naming Convention

Tên test method dùng format:

```text
method_shouldExpectedResult_whenCondition
```

Ví dụ:

```text
login_shouldReturnAuthResult_whenCredentialsValid
login_shouldThrowInvalidCredentials_whenPasswordInvalid
verifyEmail_shouldThrowVerificationTokenExpired_whenTokenExpired
borrowBook_shouldThrowNoAvailableCopy_whenAllCopiesBorrowed
returnBook_shouldCreateFine_whenReturnIsOverdue
```

Bên trong test nên theo 3 phần:

```text
Arrange: chuẩn bị data/mock
Act: gọi method cần test
Assert: kiểm tra kết quả
```

Ví dụ:

```java
@Test
void login_shouldThrowInvalidCredentials_whenPasswordInvalid() {
    // Arrange
    LoginRequest request = new LoginRequest("member1@example.com", "wrong-password");
    Member member = TestDataFactory.activeMember(1L);

    when(memberRepository.findByEmail("member1@example.com"))
            .thenReturn(Optional.of(member));
    when(passwordEncoder.matches("wrong-password", "hashed-password"))
            .thenReturn(false);

    // Act + Assert
    assertThatThrownBy(() -> authService.login(request))
            .isInstanceOfSatisfying(AppException.class, ex ->
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.getCode()));

    verify(jwtService, never()).generateAccessToken(anyString(), anyLong());
}
```

## Tầng 1: Unit Test

Unit test kiểm tra service logic mà không chạy app thật và không cần database thật.

Dùng:

```text
@ExtendWith(MockitoExtension.class)
@Mock
manual constructor injection hoặc @InjectMocks
```

Ưu tiên manual constructor injection nếu service có nhiều dependency và mapper thật:

```java
@BeforeEach
void setUp() {
    authService = new AuthServiceImpl(
            memberRepository,
            verificationRepository,
            passwordEncoder,
            jwtService,
            redisTokenService,
            emailVerificationRateLimitService,
            emailService,
            new AuthMapper(),
            new MemberMapper()
    );
}
```

### Unit Test Hiện Tại Nên Có

Vì codebase hiện tại có Auth/Profile, ưu tiên:

```text
AuthServiceTest
MemberServiceTest
MemberMapperTest
```

### AuthServiceTest

Các case nên cover:

```text
register_shouldCreateMemberVerificationAndSendEmail_whenRequestValid
register_shouldThrowEmailAlreadyExists_whenEmailExists

login_shouldReturnAuthResultAndStoreRefreshToken_whenCredentialsValid
login_shouldThrowInvalidCredentials_whenEmailNotFound
login_shouldThrowInvalidCredentials_whenPasswordInvalid
login_shouldThrowEmailNotVerified_whenMemberPendingVerification
login_shouldThrowAccountInactive_whenMemberInactive
login_shouldThrowAccountInactive_whenMemberBanned

verifyEmail_shouldActivateMemberAndMarkTokenUsed_whenTokenValid
verifyEmail_shouldThrowInvalidOrExpiredToken_whenTokenNotFound
verifyEmail_shouldThrowVerificationTokenExpired_whenTokenExpired

refreshToken_shouldThrowInvalidOrExpiredToken_whenJwtInvalid
refreshToken_shouldThrowInvalidOrExpiredToken_whenRedisTokenMissing
refreshToken_shouldThrowInvalidOrExpiredToken_whenRedisTokenDoesNotMatch
refreshToken_shouldRotateTokens_whenRefreshTokenMatchesRedis

resendVerificationEmail_shouldUpdateExistingVerificationAndSendEmail_whenRequestValid
resendVerificationEmail_shouldCreateVerification_whenNoActiveVerificationExists
resendVerificationEmail_shouldThrowResourceNotFound_whenMemberNotFound
resendVerificationEmail_shouldThrowEmailAlreadyVerified_whenMemberActive
resendVerificationEmail_shouldThrowAccountInactive_whenMemberInactive
resendVerificationEmail_shouldThrowCooldown_whenMemberIsInCooldown
resendVerificationEmail_shouldThrowLimitExceeded_whenMemberExceededResendLimit

logout_shouldBlacklistAccessTokenAndDeleteRefreshToken
```

Điểm cần assert:

```text
AppException.code đúng ErrorCode
Không generate token khi login fail
Không save repository khi validation/business fail
Refresh token rotation lưu token mới vào Redis
Verify email set member ACTIVE, isUsed=true, usedAt != null
Resend verification update token, reset usedAt, gửi email, tăng rate limit
```

### MemberServiceTest

Các case nên cover:

```text
getMyProfile_shouldReturnCurrentMemberProfile
getMyProfile_shouldThrowResourceNotFound_whenMemberDoesNotExist
updateMyProfile_shouldUpdateAllowedFieldsOnly
updateMyProfile_shouldSetPhoneNull_whenPhoneIsBlank
```

Không cho test update `email`, `role`, `status` qua `/api/me`, vì API này chỉ cho sửa profile cơ bản.

### Mapper Test

Mapper đơn giản không cần test quá nhiều. Chỉ test nếu mapping có ý nghĩa bảo mật hoặc response contract.

Với project hiện tại, `MemberMapperTest` nên giữ:

```text
toMember_shouldMapRegistrationRequestToMember
toMyProfileResponse_shouldHideSensitiveFields
```

Không test getter/setter DTO.

## Tầng 2: Repository Test

Repository test dùng khi có custom query thật.

Dùng:

```text
@DataJpaTest
Testcontainers PostgreSQL
Flyway migration thật
```

Không test CRUD mặc định của `JpaRepository`.

Chỉ test query quan trọng như:

```text
findFirstAvailableCopyForUpdate
countActiveBorrowsByMemberId
findWaitingReservationsByBookIdOrderByCreatedAt
existsByUserIdAndIdempotencyKey
```

Repository test giúp kiểm tra:

```text
SQL đúng với PostgreSQL thật
constraint đúng
unique index đúng
lock query hoạt động
Flyway migration chạy được
```

Hiện codebase mới có:

```text
MemberRepository
EmailVerificationRepository
```

Hai repository này chủ yếu dùng query derived đơn giản. Có thể chưa cần repository test riêng. Khi thêm Book/Borrow custom query thì bắt đầu viết.

## Tầng 3: Controller/API Integration Test

Integration test kiểm tra API thật:

```text
Controller
Validation
Security filter
Exception handler
Service
Repository
Database
Response JSON
Cookie/header nếu có
```

Dùng:

```text
@SpringBootTest
@AutoConfigureMockMvc
MockMvc
Testcontainers PostgreSQL
```

### AbstractIntegrationTest

Tạo base class:

```java
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("library_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

Khi dùng Redis trong integration test, cần một trong hai hướng:

```text
Mock Redis-related beans bằng @MockBean
Hoặc thêm Redis Testcontainer
```

Với auth integration test ban đầu, nên mock:

```text
EmailService
RedisTokenService
EmailVerificationRateLimitService
```

để test API không phụ thuộc SMTP/Redis thật.

### AuthControllerIntegrationTest

API hiện tại nên test:

```text
POST /api/auth/register valid -> 201
POST /api/auth/register invalid email -> 400 VALIDATION_ERROR
POST /api/auth/register duplicate email -> 409 EMAIL_ALREADY_EXISTS
POST /api/auth/login valid -> 200 + accessToken + Set-Cookie refreshToken
POST /api/auth/login wrong password -> 401 INVALID_CREDENTIALS
POST /api/auth/refresh no cookie -> 401 UNAUTHORIZED
POST /api/auth/logout no bearer -> 401/403 tùy security response
```

Ví dụ:

```java
mockMvc.perform(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {
                  "fullName": "Test User",
                  "email": "test@example.com",
                  "password": "Password123",
                  "phone": "0123456789"
                }
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true));
```

### MeControllerIntegrationTest

API hiện tại:

```text
GET /api/me
PATCH /api/me
```

Test:

```text
GET /api/me no token -> unauthorized/forbidden
GET /api/me valid JWT -> 200 và trả profile hiện tại
PATCH /api/me valid JWT -> update fullName/phone
PATCH /api/me invalid body -> 400 VALIDATION_ERROR
PATCH /api/me không được sửa email/role/status vì request DTO không có field này
```

Với app hiện tại dùng JWT thật, integration test nên generate token bằng `JwtService` hoặc gọi login trước rồi lấy token.

## Tầng 4: Security Test

Security test kiểm tra:

```text
Public endpoint được gọi không token
Protected endpoint không token bị chặn
JWT hợp lệ được phép
JWT invalid bị chặn
Role MEMBER/LIBRARIAN/ADMIN đúng quyền
```

Lưu ý quan trọng với project này:

```text
Principal hiện tại là MemberUserDetails
JWT được xử lý bởi JwtAuthFilter
```

Vì vậy `@WithMockUser` không phải lúc nào cũng phản ánh đúng production flow.

Ưu tiên test security theo 2 cách:

```text
1. Gọi API với JWT thật do JwtService tạo
2. Hoặc tạo custom annotation @WithMockMember cho unit/slice test
```

Khi có role API sau này:

```text
ADMIN API:
- no token -> 401/403
- MEMBER token -> 403
- ADMIN token -> allowed

LIBRARIAN API:
- MEMBER token -> 403
- LIBRARIAN token -> allowed
- ADMIN token -> allowed nếu rule cho phép
```

## Nghiệp Vụ Thư Viện Sau Này

Các phần này đúng về nghiệp vụ nhưng đang đi trước codebase. Khi implement service thật thì viết test ngay.

### BorrowServiceTest

Case quan trọng:

```text
borrowBook_shouldCreateBorrowRecord_whenRequestValid
borrowBook_shouldThrowMemberNotFound_whenMemberDoesNotExist
borrowBook_shouldThrowAccountInactive_whenMemberNotActive
borrowBook_shouldThrowExceedLimit_whenActiveBorrowCountReachedLimit
borrowBook_shouldThrowNoAvailableCopy_whenAllCopiesBorrowed
borrowBook_shouldNotCreateDuplicate_whenIdempotencyKeyExists
borrowBook_shouldMarkCopyAsBorrowed
borrowBook_shouldSetDueDateCorrectly
```

Assert:

```text
BorrowRecord được save
BookCopy status đổi sang BORROWED
Due date đúng policy
Không save khi lỗi
Không tạo duplicate khi idempotency key trùng
```

### ReturnBookServiceTest

Case:

```text
returnBook_shouldMarkBorrowReturned_whenRequestValid
returnBook_shouldThrowNotFound_whenBorrowRecordMissing
returnBook_shouldThrowAlreadyReturned_whenStatusReturned
returnBook_shouldMarkCopyAvailable
returnBook_shouldCreateFine_whenReturnOverdue
returnBook_shouldNotCreateFine_whenReturnOnTime
```

### FineServiceTest

Case:

```text
calculateFine_shouldReturnZero_whenReturnOnTime
calculateFine_shouldCreateFine_whenReturnLate
calculateFine_shouldCalculateAmountCorrectly_whenLateThreeDays
payFine_shouldMarkFinePaid_whenPaymentValid
payFine_shouldThrowAlreadyPaid_whenFinePaid
```

## TestDataFactory

Dùng `TestDataFactory` để tránh lặp setup.

Nên có factory cho:

```text
activeMember
pendingMember
inactiveMember
bannedMember
book
availableBookCopy
borrowedBookCopy
activeBorrowRecord
overdueBorrowRecord
paidFine
unpaidFine
```

Không nhồi logic phức tạp vào factory. Factory chỉ tạo object mẫu rõ ràng.

## Những Thứ Không Cần Test Nhiều

Không ưu tiên:

```text
Getter/setter
DTO record đơn giản
CRUD mặc định JpaRepository
Controller chỉ gọi service nếu đã có integration test
Mapper quá đơn giản
Enum bình thường
```

Ưu tiên:

```text
Business rules
Edge cases
Exception mapping
Security
Custom query
Transaction behavior
Cookie/header behavior của auth
```

## Cách Chạy Test

Chạy toàn bộ:

```powershell
./mvnw.cmd test
```

Chạy compile test:

```powershell
./mvnw.cmd -DskipTests test-compile
```

Chạy một class:

```powershell
./mvnw.cmd "-Dtest=com.vn.unit.service.AuthServiceTest" test
```

Chạy một method:

```powershell
./mvnw.cmd "-Dtest=com.vn.unit.service.AuthServiceTest#login_shouldThrowInvalidCredentials_whenPasswordInvalid" test
```

Chạy nhiều class:

```powershell
./mvnw.cmd "-Dtest=com.vn.unit.service.AuthServiceTest,com.vn.unit.service.MemberServiceTest,com.vn.unit.mapper.MemberMapperTest" test
```

Nếu chạy integration test với Testcontainers, mở Docker Desktop trước.

## CI/CD

Khi project ổn, thêm GitHub Actions:

```text
checkout code
setup Java 21
cache Maven
run ./mvnw.cmd test hoặc ./mvnw test trên Linux runner
```

Nếu integration test dùng Testcontainers, GitHub Actions Linux runner có Docker sẵn.

## Target Test Coverage

Không cần 100%.

Target thực tế:

```text
Service quan trọng: 80%+
Toàn project: 60-70%+
Controller/API chính: có integration test
Repository custom query: có PostgreSQL test
Security rule quan trọng: có test
```

Với project hiện tại:

```text
Ngắn hạn:
- AuthServiceTest đầy đủ
- MemberServiceTest
- AuthControllerIntegrationTest
- MeControllerIntegrationTest

Trung hạn:
- BorrowServiceTest
- ReturnBookServiceTest
- FineServiceTest
- Repository custom query test
- Security role test
```

## Cách Nói Khi Phỏng Vấn

Có thể nói:

> Em chia testing thành unit test, repository test, integration test và security test. Unit test dùng Mockito để kiểm tra business logic ở service như đăng nhập, xác thực email, refresh token, mượn sách, trả sách và tính tiền phạt. Integration test dùng MockMvc để kiểm tra API behavior thật, validation và exception handler. Với database, em ưu tiên Testcontainers PostgreSQL thay vì H2 để test sát môi trường thật. Em cũng test các edge case như invalid credentials, token rotation, unavailable book copies, duplicate idempotency key và role-based access control.

## Checklist Khi Viết Test Mới

- [ ] Test đúng behavior, không test implementation detail quá nhỏ
- [ ] Tên test theo `method_shouldExpectedResult_whenCondition`
- [ ] Có Arrange / Act / Assert rõ ràng
- [ ] Với exception, assert đúng `ErrorCode`
- [ ] Verify không gọi dependency khi fail sớm
- [ ] Không phụ thuộc DB thật ở unit test
- [ ] Integration test dùng Testcontainers hoặc test profile rõ ràng
- [ ] Không gửi email thật trong test
- [ ] Không phụ thuộc Redis thật nếu chưa có Redis container
- [ ] Test security theo JWT thật hoặc `@WithMockMember`, không lạm dụng `@WithMockUser`
