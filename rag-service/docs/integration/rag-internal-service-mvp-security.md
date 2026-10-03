# RAG Internal Service, Integration, and Security Model

Tài liệu này mô tả phần RAG service sau khi được chuyển từ một service có bề mặt public sang một internal service phục vụ riêng cho Spring Boot backend của hệ thống thư viện.

Mục tiêu của thay đổi này là:

- giữ RAG ở đúng vai trò xử lý ingestion, indexing, retrieval;
- để Spring Boot sở hữu toàn bộ luồng nghiệp vụ phía người dùng cuối;
- giảm bề mặt tấn công của RAG;
- làm cho luồng local dev và production-like dễ chạy, ít conflict, và rõ contract.

## 1. Bối cảnh kiến trúc sau khi chỉnh

Hiện tại hệ thống được chia theo trách nhiệm như sau:

- Spring Boot:
  - quản lý books, members, payments, ebook loans, reading sessions, permissions;
  - xử lý upload PDF ebook lên SeaweedFS;
  - lưu metadata file vào database của backend;
  - gọi RAG qua internal API sau khi upload và commit dữ liệu thành công.

- RAG service:
  - nhận ingestion request từ backend;
  - lưu document/job metadata trong PostgreSQL riêng;
  - enqueue tác vụ xử lý qua Celery;
  - worker tải file từ SeaweedFS;
  - trích xuất text, chunk, embed và upsert vào Qdrant;
  - phục vụ truy vấn nội bộ cho các use case RAG sau này.

Điểm quan trọng là: RAG không còn là một web app độc lập cho user cuối, mà là một internal processing service.

## 2. Những gì đã thay đổi trong RAG repo

### 2.1. Đã bỏ các bề mặt public không cần thiết

Các phần sau đã bị loại khỏi RAG repo vì không thuộc scope hiện tại:

- user/auth UI logic riêng của RAG;
- workspace management;
- chat UI hoặc flow public cho người dùng cuối;
- feedback/public endpoints;
- trực tiếp upload file từ browser vào RAG.

Lý do:

- backend Spring Boot đã là app chính;
- nếu để RAG giữ thêm user-facing surface thì contract bị chồng chéo;
- việc tách trách nhiệm làm giảm nguy cơ “hai nơi cùng xử lý một nghiệp vụ”.

### 2.2. RAG API được chuyển thành internal API

Endpoint chính phục vụ ingestion hiện tại là:

- `POST /internal/ingestions`

Endpoint này không dành cho browser client công khai. Nó chỉ nhận request từ backend sau khi backend đã:

- tạo ebook record thành công;
- upload PDF lên SeaweedFS thành công;
- lưu metadata file thành công;
- có checksum và object key rõ ràng.

### 2.3. Spring Boot chỉ gửi pointer metadata, không gửi file lại

Payload ingestion từ backend sang RAG chỉ chứa metadata cần thiết, ví dụ:

- `sourceType`
- `bookId`
- `ebookId`
- `bucket`
- `objectKey`
- `checksumSha256`

RAG sẽ tự tải file từ SeaweedFS bằng internal network, thay vì nhận lại toàn bộ file từ Spring Boot.

Điều này tránh:

- upload file 2 lần;
- tăng latency không cần thiết;
- tăng tải network giữa backend và RAG;
- làm phức tạp retry của file transfer.

## 3. Luồng xử lý hiện tại

Luồng hiện tại được thiết kế theo thứ tự an toàn:

1. Staff upload PDF qua Spring Boot.
2. Spring Boot upload file lên SeaweedFS bucket `library-private`.
3. Spring Boot lưu metadata file vào database của backend.
4. Sau khi dữ liệu backend đã commit thành công, Spring Boot gọi `POST /internal/ingestions`.
5. RAG tạo document/job metadata trong PostgreSQL riêng.
6. RAG enqueue Celery task.
7. Worker tải PDF trực tiếp từ SeaweedFS.
8. Worker xử lý extract/chunk/embed/index.
9. Worker cập nhật trạng thái job.

Lý do phải đợi upload và commit thành công rồi mới ingestion:

- nếu upload thất bại thì không nên tạo job RAG;
- nếu DB backend rollback thì RAG không nên ingest một file “chưa tồn tại về mặt nghiệp vụ”;
- ingest chỉ có ý nghĩa khi backend đã có record ổn định cho ebook.

## 4. Bảo mật đã triển khai ở MVP

### 4.1. Network isolation

RAG được chạy trên internal Docker network riêng:

- `rag-internal-net`

và nối thêm shared network:

- `library-platform-net`

Ý nghĩa:

- service nội bộ RAG chỉ giao tiếp qua network container-to-container;
- không dựa vào public host port để gọi service nội bộ;
- Spring Boot và RAG có contract rõ ràng qua alias DNS nội bộ.

### 4.2. Alias service name rõ ràng

Các alias chính trên shared network:

- `library-api` cho backend;
- `rag-api` cho RAG API;
- `rag-seaweedfs` cho SeaweedFS S3;
- `rag-qdrant` cho Qdrant;
- `rag-postgres-exporter` cho exporter;
- `rag-redis-exporter` cho exporter.

Điểm này giúp backend gọi RAG bằng DNS nội bộ, tránh phụ thuộc host port.

### 4.3. Internal API key cho service-to-service auth

RAG API hiện dùng header nội bộ:

- `X-RAG-API-Key`

Spring Boot phải gửi key này khi gọi `POST /internal/ingestions`.

Mục tiêu của lớp bảo vệ này là:

- ngăn các request ngẫu nhiên gọi nhầm vào internal endpoint;
- thêm một lớp xác thực đơn giản cho MVP;
- giảm rủi ro nếu một container khác trong cùng network cố tình gọi sai contract.

Đây là biện pháp MVP, chưa phải chuẩn production mạnh nhất, nhưng đủ rõ và thực dụng cho giai đoạn hiện tại.

### 4.4. Không public hóa RAG API docs theo mặc định

Swagger, ReDoc, OpenAPI docs được tắt theo mặc định cho RAG.

Lý do:

- RAG là internal service;
- hạn chế lộ surface và contract nội bộ ra ngoài không cần thiết;
- giảm khả năng user nhầm RAG là API công khai.

### 4.5. SeaweedFS access được giới hạn theo contract nội bộ

RAG và backend dùng cùng object storage credentials theo contract:

- access key
- secret key
- bucket name rõ ràng

Nhưng RAG chỉ cần truy cập SeaweedFS qua shared network endpoint nội bộ, không cần public S3 endpoint.

## 5. Những gì chưa làm trong MVP

Các phần sau hiện chưa phải lớp bảo vệ production đầy đủ:

- mTLS giữa các service;
- JWT service-to-service;
- secret rotation nhiều key cùng lúc;
- policy-based authorization theo scope;
- ingress gateway rate limiting / WAF;
- network policy ở Kubernetes;
- audit log đầy đủ cho mọi internal call;
- chống replay token theo timestamp/nonce.

Nói ngắn gọn: MVP đang dùng mô hình “internal network + shared secret header + hidden service surface”.

## 6. Mô hình bảo mật khuyến nghị cho production

Nếu triển khai production thật, nên nâng thêm các lớp sau:

### 6.1. mTLS giữa Spring Boot và RAG

Mỗi service có certificate riêng, caller và callee xác thực lẫn nhau.

Ưu điểm:

- không chỉ dựa vào shared secret;
- khó giả mạo service hơn;
- phù hợp nếu hệ thống chạy trên orchestrator hoặc service mesh.

### 6.2. Service-to-service JWT hoặc signed token

Thay vì static API key, backend ký token ngắn hạn cho từng request hoặc từng job.

Ưu điểm:

- token có thể expire;
- có thể encode `issuer`, `audience`, `request id`, `job id`;
- dễ audit và rotate hơn API key tĩnh.

### 6.3. Secret rotation

Nên có ít nhất hai key hoạt động song song trong giai đoạn rotate:

- key cũ để tương thích;
- key mới để rollout dần.

### 6.4. Reverse proxy hoặc API gateway nội bộ

Nếu cần expose một phần RAG API trong network lớn hơn:

- thêm gateway;
- chặn toàn bộ route không cần thiết;
- áp rate limit và request size limit;
- log request metadata ở layer trung gian.

### 6.5. Kubernetes NetworkPolicy hoặc cloud firewall

Khi không còn chạy bằng docker compose local:

- chỉ cho Spring Boot pod gọi RAG pod;
- chỉ cho RAG worker gọi SeaweedFS/Qdrant/Postgres/Redis;
- chặn default deny giữa các namespace.

## 7. Vì sao chọn cách này thay vì để RAG tự upload file

Có hai cách thiết kế:

1. Spring Boot upload file trước, rồi gọi RAG ingestion sau.
2. Gửi file thẳng vào RAG và để RAG tự upload + ingest.

Thiết kế hiện tại chọn cách 1 vì:

- Spring Boot là hệ thống nghiệp vụ chính;
- ownership của ebook upload nằm ở backend;
- file chỉ nên được persist một lần;
- RAG chỉ cần pointer tới object storage;
- retry và audit dễ hơn.

Thiết kế này tránh việc RAG trở thành một file-upload boundary khác, vốn sẽ làm hệ thống khó kiểm soát hơn.

## 8. Contract giữa backend và RAG

Backend gọi RAG theo contract nội bộ:

- `RAG_SERVICE_URL=http://rag-api:8000`
- endpoint: `POST /internal/ingestions`
- header: `X-RAG-API-Key`

RAG đọc file từ SeaweedFS theo contract object storage:

- bucket: `library-private`
- key: `ebooks/{bookId}/{ebookId}/original.pdf`

Ba bucket dùng chung có ownership rõ ràng:

- `library-private`: Spring Boot sở hữu PDF ebook gốc; RAG chỉ đọc để ingest.
- `library-temp`: Spring Boot dùng cho batch/CSV/ZIP và dữ liệu import tạm.
- `rag-artifacts`: RAG ghi parsed text, cleaned text, chunks và parse logs.

## 9. Trạng thái hiện tại và giới hạn

Hiện tại hệ thống đã được tối giản theo mô hình internal service.

Tuy nhiên, cần lưu ý:

- endpoint internal chỉ là lớp bảo vệ MVP;
- production nên nâng thêm mTLS/JWT/gateway;
- nếu về sau cần public search/chat API, nên tách thành một gateway hoặc BFF riêng thay vì mở lại RAG trực tiếp;
- nếu muốn an toàn hơn nữa, nên chuyển sang outbox pattern từ backend để phát ingestion event thay vì gọi sync sau commit.

## 10. Kết luận

Thiết kế hiện tại là hợp lý cho giai đoạn đồ án và cho một MVP có khả năng chạy local:

- backend sở hữu upload và nghiệp vụ;
- RAG là internal processing service;
- file chỉ upload một lần;
- ingestion diễn ra sau khi backend commit thành công;
- API RAG được bảo vệ bằng network isolation và internal API key;
- bề mặt public của RAG đã được dọn sạch.

Nếu sau này đi production, phần cần nâng cấp ưu tiên là:

1. mTLS hoặc JWT giữa service-to-service.
2. Secret rotation.
3. NetworkPolicy / gateway / rate limit.
4. Outbox hoặc event-driven ingestion để tăng độ bền.
