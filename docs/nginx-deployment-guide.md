# Nginx có cần thiết khi deploy backend không?

## Kết luận ngắn

Nginx **không bắt buộc** để Spring Boot có thể chạy. Backend vẫn có thể lắng nghe trực tiếp trên cổng `8080` và nhận request.

Tuy nhiên, với môi trường production, Nginx **nên được đặt trước Spring Boot** để làm public entry point. Nginx nhận traffic Internet trên cổng `80/443`, sau đó chuyển request hợp lệ vào backend trong private network.

Đối với đồ án này:

- Local development: chưa cần Nginx.
- Demo hoàn toàn trong máy cá nhân: không bắt buộc.
- Deploy lên VPS hoặc domain thật: nên dùng Nginx.
- Production có HTTPS, upload PDF và nhiều service: rất nên dùng Nginx hoặc một reverse proxy/API gateway tương đương.

## Kiến trúc đề xuất

```text
Browser / Mobile client
          |
          | HTTPS :443
          v
        Nginx
          |
          | HTTP trong private Docker network
          v
Spring Boot library-service:8080
          |
          +------> PostgreSQL
          +------> Redis
          +------> RAG API: rag-api:8000
          +------> SeaweedFS S3: rag-seaweedfs:8333
```

Chỉ Nginx cần được public ra Internet. PostgreSQL, Redis, RAG API và endpoint nội bộ của SeaweedFS không nên public trực tiếp nếu không có lý do rõ ràng.

## Nginx phục vụ những gì trong đồ án này?

### 1. Reverse proxy cho Spring Boot

Người dùng truy cập:

```text
https://api.library.example.com/api/books
```

Nginx nhận request và chuyển tiếp nội bộ tới:

```text
http://library-service:8080/api/books
```

Client không cần biết IP, cổng hoặc container name thật của Spring Boot.

### 2. Kết thúc kết nối HTTPS

Nginx có thể quản lý certificate TLS và tiếp nhận HTTPS. Kết nối từ Nginx tới Spring Boot có thể dùng HTTP trong private Docker network.

Điều này giúp:

- bảo vệ JWT, cookie và dữ liệu đăng nhập khi truyền trên Internet;
- hỗ trợ secure cookie;
- tránh cấu hình certificate trực tiếp trong Spring Boot;
- dễ gia hạn certificate và thay đổi domain.

Với production, không nên expose API đăng nhập, thanh toán hoặc reading session qua HTTP thuần.

### 3. Giữ một domain ổn định cho backend

Nginx cho phép frontend luôn gọi một địa chỉ ổn định:

```text
https://api.library.example.com
```

Spring Boot có thể đổi container, cổng nội bộ hoặc chạy nhiều instance mà frontend không cần thay đổi.

Các biến như callback URL của VNPAY và verification URL cũng nên sử dụng domain public này.

### 4. Giới hạn request và rate limiting

Nginx có thể giới hạn số request theo IP hoặc theo route, ví dụ:

- đăng nhập;
- gửi lại email xác thực;
- tạo payment;
- refresh reading session;
- endpoint tìm kiếm hoặc hỏi đáp RAG.

Đây là lớp bảo vệ ở biên hệ thống. Nó không thay thế rate limit và validation trong Spring Boot.

### 5. Kiểm soát upload PDF

Đồ án cho phép upload ebook PDF khoảng `100 MB`. Nginx cần được cấu hình giới hạn body lớn hơn mức này một chút, nếu không request có thể bị Nginx từ chối trước khi tới Spring Boot.

Cần đồng bộ ba giới hạn:

```text
Nginx request body limit
Spring multipart max file size
Spring multipart max request size
```

Timeout proxy cũng phải đủ cho upload file lớn. Không nên đặt timeout quá ngắn, nhưng cũng không nên để vô hạn.

### 6. Forward thông tin request gốc

Spring Boot cần biết:

- IP thật của client;
- request ban đầu dùng HTTP hay HTTPS;
- hostname public;
- port public.

Nginx phải forward các header chuẩn tương ứng. Backend chỉ nên tin các header này khi request thực sự đi qua reverse proxy được kiểm soát.

Thông tin này quan trọng cho logging, audit, rate limiting, tạo URL và chính sách cookie.

### 7. Access log và hỗ trợ điều tra lỗi

Nginx có thể ghi lại:

- thời điểm request;
- route;
- status code;
- response time;
- kích thước request/response;
- IP client;
- upstream Spring Boot nào xử lý request.

Log này giúp phân biệt lỗi xảy ra tại Nginx, network hay Spring Boot.

Không nên log JWT, cookie, mật khẩu, chữ ký thanh toán hoặc query parameter nhạy cảm.

### 8. Load balancing khi mở rộng backend

Nếu sau này chạy nhiều Spring Boot instance:

```text
library-service-1:8080
library-service-2:8080
library-service-3:8080
```

Nginx có thể phân phối request giữa các instance. Hiện tại đồ án chạy một backend instance nên chức năng này chưa cần thiết ngay.

PostgreSQL và Redis vẫn là state dùng chung. Backend không nên phụ thuộc vào HTTP session lưu trong memory của một instance.

### 9. Có thể phục vụ frontend tĩnh

Nếu frontend build ra HTML, CSS và JavaScript, Nginx có thể phục vụ các file này và proxy `/api` tới Spring Boot.

Một lựa chọn khác là tách domain:

```text
library.example.com       -> frontend
api.library.example.com   -> Spring Boot API
```

Cả hai phương án đều hợp lệ. Tách domain cần cấu hình CORS và cookie chính xác hơn.

## Nginx liên quan thế nào tới RAG?

Spring Boot đang gọi RAG bằng địa chỉ nội bộ:

```text
http://rag-api:8000
```

Đây là giao tiếp service-to-service trong shared Docker network. Luồng này **không cần đi qua Nginx**.

RAG API `/internal/ingestions` cũng không nên public trực tiếp. Spring Boot là service chịu trách nhiệm xác thực người dùng, kiểm tra quyền và gửi ingestion request nội bộ.

Nếu sau này frontend có API hỏi đáp, nên cân nhắc một trong hai hướng:

1. Frontend gọi Spring Boot, Spring Boot gọi RAG.
2. Nginx route một nhóm API public riêng tới RAG, nhưng RAG phải có authentication/authorization phù hợp.

Với đồ án hiện tại, hướng 1 an toàn và dễ kiểm soát hơn.

## Nginx liên quan thế nào tới SeaweedFS?

Backend upload object bằng endpoint nội bộ:

```text
http://rag-seaweedfs:8333
```

Trình duyệt không phân giải được hostname Docker `rag-seaweedfs`. Vì vậy presigned URL trả cho frontend phải dùng một endpoint public, ví dụ:

```text
https://storage.library.example.com
```

Nginx có thể đứng trước SeaweedFS cho hostname public này. Khi đó:

```text
OBJECT_STORAGE_ENDPOINT=http://rag-seaweedfs:8333
OBJECT_STORAGE_PUBLIC_ENDPOINT=https://storage.library.example.com
```

Chỉ nên cho phép truy cập object bằng presigned URL hoặc cơ chế authorization phù hợp. Không được biến bucket `library-private` thành bucket public.

Cần kiểm thử kỹ chữ ký S3 khi proxy qua domain khác vì hostname, scheme và path là một phần quan trọng của request đã ký.

## Những việc Nginx không thay thế

Nginx không thay thế:

- Spring Security và JWT;
- phân quyền member/staff/admin;
- validation file PDF;
- kiểm tra ebook loan và reading session;
- idempotency payment;
- chữ ký VNPAY;
- database constraints;
- signed URL của object storage;
- business rate limit cần chính xác theo member/account;
- Docker shared network giữa Spring Boot và RAG.

Nginx là lớp network ở phía trước ứng dụng, không phải lớp nghiệp vụ.

## Scenario production cụ thể

Giả sử hệ thống có các domain:

```text
library.example.com
api.library.example.com
storage.library.example.com
```

Luồng đăng nhập:

```text
Browser
  -> HTTPS api.library.example.com/api/auth/login
  -> Nginx
  -> library-service:8080
  -> Spring Security xử lý đăng nhập
  -> response quay lại qua Nginx
```

Luồng upload ebook:

```text
Staff browser
  -> HTTPS API qua Nginx
  -> Spring Boot kiểm tra JWT và quyền staff
  -> Spring Boot upload PDF trực tiếp tới rag-seaweedfs:8333
  -> Spring Boot lưu bucket/object key vào PostgreSQL
  -> sau này Spring Boot gọi rag-api:8000 để tạo ingestion job
```

Luồng đọc ebook:

```text
Member browser
  -> Nginx -> Spring Boot tạo reading session
  -> Nginx -> Spring Boot kiểm tra ebook loan
  -> Spring Boot tạo presigned URL dùng storage.library.example.com
  -> Browser tải PDF qua hostname public của object storage
```

## Mức triển khai đề xuất cho đồ án

### Giai đoạn local

- Không cần Nginx.
- Frontend gọi `http://localhost:8080`.
- Backend gọi RAG và SeaweedFS qua Docker network.

### Giai đoạn demo trên VPS

- Thêm một Nginx public.
- Chỉ expose cổng `80/443` của Nginx.
- Spring Boot, RAG, PostgreSQL và Redis nằm trong private network.
- Dùng HTTPS cho API.
- Cấu hình upload limit và timeout phù hợp PDF 100 MB.
- Không public `/actuator/prometheus`, Swagger hoặc internal RAG endpoint nếu không có lớp bảo vệ.

### Giai đoạn production

- HTTPS và tự động gia hạn certificate.
- Rate limit cho endpoint nhạy cảm.
- Access log có rotation và không chứa secret.
- Health check cho upstream.
- Security headers phù hợp.
- Backup và monitoring độc lập.
- Có domain public riêng cho object storage nếu frontend tải bằng presigned URL.
- Chỉ cho phép Nginx hoặc private network truy cập trực tiếp cổng backend.

## Quyết định cho repo hiện tại

Chưa cần thêm Nginx vào `compose.yaml` backend ngay lúc này nếu RAG/SeaweedFS và Spring Boot vẫn đang được phát triển local.

Nên thêm Nginx khi đã xác định:

- domain deploy;
- nơi chạy frontend;
- cách cấp TLS certificate;
- public hostname cho SeaweedFS;
- có public Swagger/Actuator hay không;
- giới hạn upload và timeout production;
- VPS chạy một compose chung hay nhiều compose trên shared network.

Việc trì hoãn cấu hình Nginx đến khi các thông tin trên rõ ràng giúp tránh hardcode domain và routing sai. Nhưng kiến trúc nên mặc định rằng production sẽ có một reverse proxy ở phía trước Spring Boot.
