# Resend Email Setup Guide

## Mục tiêu

Tài liệu này mô tả cách dùng Resend để gửi email trong hệ thống `QuanLyThuVien`, cụ thể là email xác nhận tài khoản đang được gửi trong flow:

```text
POST /api/auth/register
        -> AuthServiceImpl.register(...)
        -> EmailService.sendVerificationEmail(...)
        -> EmailServiceImpl gửi email HTML bằng Thymeleaf template
```

Hiện tại project đang dùng:

```text
spring-boot-starter-mail
JavaMailSender
Thymeleaf template: templates/email-verification.html
SMTP Gmail
```

Nếu chuyển sang Resend, có 2 hướng:

```text
Hướng 1: Resend SMTP
Hướng 2: Resend Java SDK / REST API
```

Khuyến nghị cho project này:

```text
Dùng Resend SMTP trước.
```

Lý do:

- Code hiện tại đã dùng `JavaMailSender`.
- Không cần đổi flow gửi email.
- Không cần thêm Resend SDK ngay.
- Chỉ cần đổi config SMTP và chỉnh nhẹ field `from`.
- Rất phù hợp cho đồ án và demo intern.

Nguồn chính thức:

- Resend Java quickstart: https://resend.com/docs/send-with-java
- Resend SMTP quickstart: https://resend.com/docs/send-with-smtp
- Resend API reference: https://resend.com/docs/api-reference/introduction
- Resend send email API: https://resend.com/docs/api-reference/emails/send-email
- Resend API keys: https://resend.com/docs/dashboard/api-keys/introduction
- Resend sender/from address behavior: https://resend.com/docs/knowledge-base/how-do-I-create-an-email-address-or-sender-in-resend
- Resend test domain: https://resend.dev/
- Resend error reference: https://www.resend.com/docs/api-reference/errors

## Resend hoạt động như thế nào?

Resend là email API/service cho developer. Nó hỗ trợ gửi email qua:

```text
1. HTTP API / SDK
2. SMTP
```

Với SMTP, app Spring Boot của mình vẫn gửi email như bình thường qua `JavaMailSender`, nhưng thay vì kết nối Gmail SMTP:

```text
smtp.gmail.com
```

thì chuyển sang:

```text
smtp.resend.com
```

Theo tài liệu Resend SMTP, credentials là:

```text
Host: smtp.resend.com
Port: 25, 465, 587, 2465, hoặc 2587
Username: resend
Password: YOUR_API_KEY
```

Với Spring Boot, nên dùng:

```text
Port: 587
Security: STARTTLS
```

## Điểm rất quan trọng trong code hiện tại

Hiện tại `EmailServiceImpl` đang có:

```java
@Value("${spring.mail.username}")
private String fromEmail;
```

và khi gửi:

```java
helper.setFrom(fromEmail);
```

Cách này tạm ổn với Gmail vì:

```text
spring.mail.username = email Gmail thật
```

Nhưng với Resend SMTP thì:

```text
spring.mail.username = resend
```

`resend` không phải địa chỉ email hợp lệ để làm `From`.

Vì vậy nếu chuyển Resend, bắt buộc nên tách:

```text
spring.mail.username = tài khoản SMTP để login
app.mail.from = địa chỉ gửi email hiển thị trong email
```

Ví dụ:

```properties
spring.mail.username=resend
spring.mail.password=${RESEND_API_KEY}
app.mail.from=The Athenaeum <onboarding@resend.dev>
```

hoặc production:

```properties
spring.mail.username=resend
spring.mail.password=${RESEND_API_KEY}
app.mail.from=The Athenaeum <no-reply@your-domain.com>
```

## Local, staging, production khác nhau thế nào?

### Local development

Mục tiêu:

```text
Chạy được app local.
Test được gửi email xác nhận.
Không lộ API key lên GitHub.
Không cần domain production nếu chỉ test cơ bản.
```

Có 2 kiểu local:

```text
1. Local smoke test với resend.dev
2. Local gửi email thật bằng domain đã verify
```

### Local smoke test với resend.dev

Resend có domain `resend.dev` để simulate email events. Theo docs `resend.dev`, bạn có thể dùng các email như:

```text
delivered@resend.dev
bounced@resend.dev
complained@resend.dev
suppressed@resend.dev
```

Ví dụ khi muốn test email gửi thành công:

```text
to = delivered@resend.dev
from = onboarding@resend.dev
```

Nhưng trong hệ thống của mình, email xác nhận được gửi đến email user nhập lúc register:

```text
RegistrationRequest.email
```

Vì vậy nếu muốn test qua `delivered@resend.dev`, bạn có thể đăng ký bằng email:

```text
delivered@resend.dev
```

Điểm cần hiểu:

```text
resend.dev phù hợp để smoke test gửi email.
Nó không thay thế flow production với domain thật.
```

### Local gửi email thật

Nếu muốn đăng ký bằng email cá nhân thật, ví dụ:

```text
your-email@gmail.com
```

thì cần chú ý restriction của Resend. Theo error reference, nếu chưa verify domain, bạn có thể gặp lỗi kiểu:

```text
You can only send testing emails to your own email address...
To send emails to other recipients, please verify a domain...
```

Vì vậy để local gửi email thật ổn định, nên:

```text
1. Verify domain trong Resend.
2. Dùng From address thuộc domain đã verify.
3. Dùng API key có quyền Sending access.
```

### Production/deploy thật

Production nên dùng:

```text
Verified domain
Sending access API key
Environment variables trên server
Không commit secret
HTTPS base URL
```

Ví dụ:

```text
Domain: your-domain.com
From: The Athenaeum <no-reply@your-domain.com>
Verification base URL: https://api.your-domain.com
```

Không nên dùng:

```text
onboarding@resend.dev
localhost base URL
API key full access nếu chỉ cần gửi email
```

## Setup Resend Dashboard

### Bước 1: Tạo tài khoản Resend

Truy cập:

```text
https://resend.com
```

Đăng ký tài khoản.

### Bước 2: Tạo API key

Vào Dashboard:

```text
API Keys -> Create API Key
```

Resend có quyền:

```text
Full access
Sending access
```

Với project này, nên chọn:

```text
Sending access
```

Lý do:

```text
App chỉ cần gửi email xác nhận.
Không cần quyền quản lý domain, logs, contacts...
Nếu key bị lộ, damage nhỏ hơn full access.
```

Lưu ý:

```text
API key chỉ xem được một lần sau khi tạo.
Copy vào .env ngay.
Không commit API key.
```

Ví dụ:

```env
RESEND_API_KEY=re_xxxxxxxxxxxxxxxxxxxxxxxxx
```

### Bước 3: Verify domain

Vào:

```text
Domains -> Add Domain
```

Nên dùng subdomain cho transactional email:

```text
mail.your-domain.com
```

hoặc:

```text
notifications.your-domain.com
```

Sau đó Resend sẽ đưa DNS records cần thêm vào DNS provider.

Thông thường sẽ có các record phục vụ:

```text
DKIM
SPF / sending authorization
Return-path / bounce handling
```

Bạn thêm record trong nơi quản lý domain, ví dụ:

```text
Cloudflare
Namecheap
GoDaddy
Hostinger
Vercel DNS
AWS Route 53
```

Sau khi thêm DNS:

```text
Click Verify trong Resend Dashboard
Đợi DNS propagate
```

Docs Resend nói flow bắt đầu bằng:

```text
Sign up
Add and verify domain
Create API key
Start sending from any address at verified domain
```

### Bước 4: Chọn From address

Theo Resend, bạn không cần tạo trước từng sender/email address. Sau khi domain đã verify, bạn có thể gửi từ bất kỳ address nào thuộc domain đó.

Ví dụ domain đã verify:

```text
your-domain.com
```

Thì có thể gửi từ:

```text
no-reply@your-domain.com
support@your-domain.com
library@your-domain.com
```

Khuyến nghị cho hệ thống:

```text
The Athenaeum <no-reply@your-domain.com>
```

Không nên dùng email cá nhân làm sender production.

## Hướng 1: Dùng Resend SMTP

Đây là hướng nên làm trước.

### Ưu điểm

```text
Ít sửa code nhất
Tận dụng JavaMailSender hiện tại
Không cần thêm dependency Resend SDK
Vẫn dùng Thymeleaf template hiện tại
```

### Nhược điểm

```text
Không lấy được response id của Resend dễ như API
Không tận dụng sâu tags, idempotency key, typed response
Debug lỗi chi tiết thường kém hơn API
```

## Config application.properties cho Resend SMTP

Hiện tại bạn đang có Gmail SMTP:

```properties
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${MAIL_USERNAME}
spring.mail.password=${MAIL_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

Nếu chuyển Resend SMTP, nên đổi thành:

```properties
# ======================
# MAIL / SMTP CONFIG
# ======================
spring.mail.host=${MAIL_HOST:smtp.resend.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:resend}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=5000
spring.mail.properties.mail.smtp.writetimeout=5000

# Email sender shown to recipients.
# For local smoke test, use: The Athenaeum <onboarding@resend.dev>
# For production, use a verified domain: The Athenaeum <no-reply@your-domain.com>
app.mail.from=${MAIL_FROM:The Athenaeum <onboarding@resend.dev>}
```

Giải thích:

```text
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=Resend API key
MAIL_FROM=sender email hiển thị cho user
```

Với Resend SMTP:

```text
Username phải là resend
Password là API key
From phải là email hợp lệ, ví dụ onboarding@resend.dev hoặc no-reply@domain đã verify
```

## .env local

File `.env` local nên có:

```env
# Mail provider
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxx
MAIL_FROM=The Athenaeum <onboarding@resend.dev>
```

Nếu dùng domain production đã verify:

```env
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxx
MAIL_FROM=The Athenaeum <no-reply@your-domain.com>
APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

Lưu ý:

```text
.env không commit lên GitHub.
Nên có .env.example không chứa secret nếu muốn hướng dẫn người khác chạy.
```

## Sửa EmailServiceImpl

Hiện tại:

```java
@Value("${spring.mail.username}")
private String fromEmail;
```

Nên đổi thành:

```java
@Value("${app.mail.from}")
private String fromEmail;
```

Lý do:

```text
spring.mail.username là username SMTP.
Với Resend, username SMTP là resend.
fromEmail là địa chỉ người gửi hiển thị cho người nhận.
Hai thứ này không giống nhau.
```

Code sau khi chỉnh:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.verification.base-url}")
    private String baseUrl;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Override
    @Async
    public void sendVerificationEmail(Long memberId, String toEmail, String fullName, String token) {
        try {
            String verifyLink = baseUrl + "/api/auth/verify-email?token=" + token;

            Context context = new Context();
            context.setVariable("fullName", fullName);
            context.setVariable("verifyLink", verifyLink);

            String htmlContent = templateEngine.process("email-verification", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Xác nhận tài khoản - Hệ thống Quản lý Thư viện");
            helper.setText(htmlContent, true);

            mailSender.send(message);

            log.info("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION",
                    LogEvent.SEND_VERIFICATION_EMAIL, LogResult.SUCCESS, memberId);
        } catch (MessagingException e) {
            log.error("eventType={} result={} memberId={} entityType=EMAIL_VERIFICATION reason={}",
                    LogEvent.SEND_VERIFICATION_EMAIL, LogResult.FAILED, memberId, e.getClass().getSimpleName(), e);
        }
    }
}
```

## app.verification.base-url

Hiện tại config:

```properties
app.verification.base-url=http://localhost:8080
```

Email verification link được tạo:

```java
String verifyLink = baseUrl + "/api/auth/verify-email?token=" + token;
```

Local:

```env
APP_VERIFICATION_BASE_URL=http://localhost:8080
```

Production:

```env
APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

Nên đổi `application.properties` thành:

```properties
app.verification.base-url=${APP_VERIFICATION_BASE_URL:http://localhost:8080}
```

Nếu không đổi, deploy production sẽ gửi link localhost, user click không dùng được.

## Cấu hình production trên Render/Railway/Fly.io/VPS

Tuỳ nền tảng deploy, bạn set environment variables:

```env
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxx
MAIL_FROM=The Athenaeum <no-reply@your-domain.com>
APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

Không cần upload `.env` lên server nếu nền tảng có UI quản lý environment variables.

Ví dụ Render:

```text
Dashboard -> Service -> Environment -> Add Environment Variables
```

Ví dụ Railway:

```text
Project -> Service -> Variables
```

Ví dụ VPS:

```bash
export MAIL_HOST=smtp.resend.com
export MAIL_PORT=587
export MAIL_USERNAME=resend
export MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxx
export MAIL_FROM="The Athenaeum <no-reply@your-domain.com>"
export APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

## Kiểm tra bằng flow thật

### Local smoke test

1. Chạy app:

```powershell
mvn spring-boot:run
```

2. Register bằng email test:

```http
POST /api/auth/register
Content-Type: application/json

{
  "fullName": "Test User",
  "email": "delivered@resend.dev",
  "password": "Password123"
}
```

3. Kiểm tra log:

```text
eventType=SEND_VERIFICATION_EMAIL result=SUCCESS
```

4. Kiểm tra Resend Dashboard:

```text
Emails / Logs
```

Theo Resend SMTP docs, email gửi qua SMTP sẽ hiển thị trong emails table.

### Test production domain

1. Verify domain.
2. Set:

```env
MAIL_FROM=The Athenaeum <no-reply@your-domain.com>
APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

3. Register bằng email thật:

```text
your-real-email@gmail.com
```

4. Click verification link trong email.

5. Kiểm tra member status trong DB:

```sql
SELECT email, status
FROM members
WHERE email = 'your-real-email@gmail.com';
```

Kết quả mong muốn:

```text
ACTIVE
```

## Những lỗi thường gặp

### 1. Không resolve được MAIL_PASSWORD hoặc RESEND_API_KEY

Triệu chứng:

```text
Could not resolve placeholder
Authentication failed
```

Cách xử lý:

```text
Kiểm tra .env có MAIL_PASSWORD chưa.
Kiểm tra application.properties có spring.config.import=optional:file:.env[.properties] chưa.
Kiểm tra IntelliJ working directory là root project.
Kiểm tra biến env trên server deploy.
```

### 2. Gửi bị 403 vì domain chưa verify

Triệu chứng từ Resend error docs:

```text
You can only send testing emails to your own email address...
```

hoặc:

```text
domain is not verified
```

Cách xử lý:

```text
Verify domain trong Resend.
Đổi MAIL_FROM sang email thuộc domain đã verify.
```

### 3. From address sai

Sai:

```env
MAIL_FROM=resend
```

Đúng:

```env
MAIL_FROM=The Athenaeum <no-reply@your-domain.com>
```

hoặc local smoke test:

```env
MAIL_FROM=The Athenaeum <onboarding@resend.dev>
```

### 4. Dùng Gmail username/password cũ với Resend host

Sai:

```env
MAIL_HOST=smtp.resend.com
MAIL_USERNAME=23521216@gm.uit.edu.vn
MAIL_PASSWORD=gmail-app-password
```

Đúng:

```env
MAIL_HOST=smtp.resend.com
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxx
```

### 5. Link verify vẫn là localhost khi deploy

Sai production:

```env
APP_VERIFICATION_BASE_URL=http://localhost:8080
```

Đúng:

```env
APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

## Có nên dùng Resend Java SDK không?

Resend Java SDK dùng dependency:

```gradle
implementation 'com.resend:resend-java:+'
```

Với Maven, nên khai báo version cụ thể thay vì `+`:

```xml
<dependency>
    <groupId>com.resend</groupId>
    <artifactId>resend-java</artifactId>
    <version><!-- chọn version mới nhất từ Maven Central --></version>
</dependency>
```

Docs Java official có ví dụ:

```java
Resend resend = new Resend("re_xxxxxxxxx");

CreateEmailOptions params = CreateEmailOptions.builder()
        .from("Acme <onboarding@resend.dev>")
        .to("delivered@resend.dev")
        .subject("it works!")
        .html("<strong>hello world</strong>")
        .build();

CreateEmailResponse data = resend.emails().send(params);
```

### Khi nào nên dùng SDK/API?

Nên dùng SDK/API khi bạn muốn:

```text
Lấy email id rõ ràng sau khi gửi
Dùng tags
Dùng idempotency key dễ hơn
Dùng Resend API logs tốt hơn
Dễ mở rộng sang batch emails, attachments, templates
```

### Vì sao hiện tại chưa cần?

Project của mình hiện chỉ cần:

```text
Gửi email xác nhận tài khoản
Render HTML bằng Thymeleaf
Log success/failed
```

SMTP đủ tốt cho nhu cầu này.

## Nếu vẫn muốn dùng Resend REST API bằng Spring RestClient

Project hiện đã có:

```xml
spring-boot-starter-restclient
```

Có thể gọi API trực tiếp mà không cần Resend SDK.

Config:

```properties
resend.api-key=${RESEND_API_KEY:}
resend.api-base-url=${RESEND_API_BASE_URL:https://api.resend.com}
app.mail.from=${MAIL_FROM:The Athenaeum <onboarding@resend.dev>}
```

DTO request:

```java
record ResendEmailRequest(
        String from,
        List<String> to,
        String subject,
        String html
) {
}
```

Service gọi API:

```java
@Service
@RequiredArgsConstructor
public class ResendEmailClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${resend.api-key}")
    private String apiKey;

    @Value("${resend.api-base-url}")
    private String baseUrl;

    public void send(String from, String to, String subject, String html) {
        RestClient client = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("User-Agent", "QuanLyThuVien/1.0")
                .build();

        client.post()
                .uri("/emails")
                .body(new ResendEmailRequest(from, List.of(to), subject, html))
                .retrieve()
                .toBodilessEntity();
    }
}
```

Lưu ý nếu gọi HTTP API trực tiếp:

```text
Resend API yêu cầu HTTPS.
Authorization header là Bearer API_KEY.
Direct HTTP request cần User-Agent header.
```

Với SDK, các phần này thường được xử lý sẵn.

## Đề xuất implementation cho project hiện tại

### Phase 1: Chuyển Gmail SMTP sang Resend SMTP

Sửa:

```text
application.properties
.env
EmailServiceImpl
```

Không cần sửa:

```text
AuthServiceImpl
EmailService interface
email-verification.html
pom.xml
```

Checklist:

```text
1. Thêm app.mail.from vào application.properties.
2. Đổi spring.mail.host sang smtp.resend.com.
3. Đổi spring.mail.username default sang resend.
4. Đổi spring.mail.password đọc từ MAIL_PASSWORD hoặc RESEND_API_KEY.
5. Sửa EmailServiceImpl dùng app.mail.from.
6. Test register.
7. Kiểm tra Resend Dashboard.
```

### Phase 2: Production hardening

Sau khi gửi email ổn:

```text
Verify domain
Set MAIL_FROM dùng domain thật
Set APP_VERIFICATION_BASE_URL dùng HTTPS domain thật
Dùng Sending access API key
Rotate key nếu từng lộ trong local/log/Git
Theo dõi Resend dashboard logs
```

### Phase 3: Nâng cấp API/SDK nếu cần

Chỉ làm nếu có nhu cầu:

```text
Lưu resendEmailId
Retry có idempotency key
Batch email
Email template hosted trên Resend
Webhook delivered/bounced/complained
```

## Config đề xuất cuối cùng

### application.properties

```properties
# ======================
# MAIL / SMTP CONFIG
# ======================
spring.mail.host=${MAIL_HOST:smtp.resend.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:resend}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=5000
spring.mail.properties.mail.smtp.writetimeout=5000

app.mail.from=${MAIL_FROM:The Athenaeum <onboarding@resend.dev>}
app.verification.base-url=${APP_VERIFICATION_BASE_URL:http://localhost:8080}
```

### .env local smoke test

```env
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxx
MAIL_FROM=The Athenaeum <onboarding@resend.dev>
APP_VERIFICATION_BASE_URL=http://localhost:8080
```

### .env production

```env
MAIL_HOST=smtp.resend.com
MAIL_PORT=587
MAIL_USERNAME=resend
MAIL_PASSWORD=re_xxxxxxxxxxxxxxxxxxxxxxxxx
MAIL_FROM=The Athenaeum <no-reply@your-domain.com>
APP_VERIFICATION_BASE_URL=https://api.your-domain.com
```

## Kết luận

Với codebase hiện tại, Resend SMTP là lựa chọn hợp lý nhất:

```text
Ít sửa code
Không thêm dependency
Tận dụng JavaMailSender + Thymeleaf hiện tại
Local test được
Production deploy được
```

Điểm bắt buộc phải sửa là không dùng `spring.mail.username` làm `fromEmail` nữa. Với Resend SMTP, `spring.mail.username` phải là `resend`, còn sender thật nên nằm ở `app.mail.from`.

Khi deploy thật, phần quan trọng nhất là:

```text
Verify domain
MAIL_FROM thuộc domain đã verify
APP_VERIFICATION_BASE_URL là HTTPS URL thật
RESEND API key nằm trong environment variables
```
