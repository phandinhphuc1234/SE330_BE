# Monitoring với Prometheus và Grafana

Tài liệu này giải thích cách áp dụng monitoring vào hệ thống quản lý thư viện Spring Boot.

## 1. Monitoring là gì?

Monitoring là việc theo dõi trạng thái hệ thống bằng dữ liệu định lượng. Thay vì chỉ nhìn log sau khi lỗi xảy ra, monitoring giúp trả lời các câu hỏi:

```text
Backend còn sống không?
API nào đang chậm?
Số request tăng bất thường không?
Database connection pool có bị đầy không?
JVM memory có tăng liên tục không?
Scheduled job có làm hệ thống nặng không?
```

Trong project này:

```text
Spring Boot Actuator + Micrometer -> tạo metric
Prometheus                       -> thu thập/lưu metric
Grafana                          -> vẽ dashboard
```

## 2. Kiến trúc local

```text
Browser/Postman/Frontend
        |
        v
Spring Boot app :8080
        |
        v
/actuator/prometheus
        ^
        |
Prometheus :9090
        ^
        |
Grafana :3001
```

Prometheus không gọi API nghiệp vụ như `/api/books`. Nó chỉ gọi:

```text
GET /actuator/prometheus
```

Endpoint này trả về metric dạng text mà Prometheus hiểu được.

## 3. Những gì project đã có

Trong `pom.xml` đã có:

```xml
spring-boot-starter-actuator
micrometer-registry-prometheus
```

Trong `application.properties` đã bật:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.prometheus.metrics.export.enabled=true
management.metrics.tags.application=${app.service-name}
management.metrics.tags.environment=${APP_ENV:local}
```

Trong `SecurityConfig`, các endpoint monitoring local được permit:

```text
GET /actuator/health
GET /actuator/info
GET /actuator/prometheus
```

## 4. Cách chạy

Start backend từ IntelliJ trước.

Sau đó chạy:

```powershell
docker compose up -d prometheus grafana
```

Kiểm tra Prometheus:

```text
http://localhost:9090
```

Vào:

```text
Status > Targets
```

Target `library-service` phải là `UP`.

Mở Grafana:

```text
http://localhost:3001
```

Login local:

```text
admin / admin
```

Dashboard:

```text
Library Monitoring > Library Service Overview
```

## 5. Metric quan trọng cho đồ án

### 5.1 Service health

```promql
up{job="library-service"}
```

Ý nghĩa:

```text
1 = Prometheus scrape được backend
0 = backend chết, sai port, hoặc endpoint bị chặn
```

### 5.2 Request rate

```promql
sum(rate(http_server_requests_seconds_count{job="library-service"}[5m]))
```

Ý nghĩa:

```text
Mỗi giây backend xử lý bao nhiêu request.
```

Dùng để demo khi bạn spam search books hoặc checkout preview.

### 5.3 Latency trung bình

```promql
sum(rate(http_server_requests_seconds_sum{job="library-service"}[5m]))
/
sum(rate(http_server_requests_seconds_count{job="library-service"}[5m]))
```

Ý nghĩa:

```text
Trung bình một request mất bao lâu.
```

Nếu API import CSV, checkout, checkin hoặc search chậm, metric này tăng.

### 5.4 Request theo endpoint/status

```promql
sum by (method, uri, status) (
  rate(http_server_requests_seconds_count{job="library-service"}[5m])
)
```

Ý nghĩa:

```text
Biết endpoint nào bị gọi nhiều và trả status nào.
Ví dụ: nhiều 400/409 ở circulation có thể do lỗi nghiệp vụ hoặc frontend retry sai.
```

### 5.5 JVM memory

```promql
sum by (area) (jvm_memory_used_bytes{job="library-service"})
```

Ý nghĩa:

```text
Theo dõi heap/nonheap memory.
```

Nếu import CSV lớn làm memory tăng liên tục và không giảm, cần kiểm tra memory leak hoặc batch size.

### 5.6 Database connection pool

```promql
hikaricp_connections_active{job="library-service"}
hikaricp_connections_idle{job="library-service"}
```

Ý nghĩa:

```text
Theo dõi số connection đang bận/rảnh.
```

Nếu active connection thường xuyên chạm max pool, API có thể chậm hoặc timeout.

## 6. Ứng dụng vào nghiệp vụ thư viện

Bạn có thể nói trong báo cáo:

```text
Hệ thống sử dụng Spring Boot Actuator và Micrometer để expose metric,
Prometheus để scrape metric định kỳ,
Grafana để xây dashboard theo dõi health, request rate, latency, JVM memory và database connection pool.
```

Các flow nên quan sát:

```text
Auth/login
Search books
Checkout preview
Checkout thật
Checkin
Renewal
Hold checkout
CSV import
Scheduled jobs: overdue, auto-renewal, due-soon reminder, hold expiry
```

Ví dụ thực tế:

```text
Nếu CSV import làm JVM memory tăng bất thường, dashboard phát hiện được.
Nếu checkout/checkin chậm do database connection pool căng, HikariCP metric thể hiện được.
Nếu frontend retry sai tạo nhiều 409 idempotency, HTTP status metric phát hiện được.
```

## 7. Cơ bản cần hiểu

### Metric khác log thế nào?

```text
Log: sự kiện chi tiết, dùng để debug một request/job cụ thể.
Metric: số liệu tổng hợp, dùng để theo dõi xu hướng.
```

Ví dụ:

```text
Log cho biết checkout borrowId=100 lỗi vì MEMBER_HAS_OVERDUE_ITEMS.
Metric cho biết 5 phút qua có nhiều request checkout trả 409.
```

### Prometheus khác Grafana thế nào?

```text
Prometheus thu thập và lưu metric.
Grafana đọc metric từ Prometheus và vẽ dashboard.
```

Grafana không thay thế Prometheus.

### Pull model là gì?

Prometheus tự đi lấy metric từ backend theo chu kỳ:

```text
Prometheus -> GET /actuator/prometheus -> Spring Boot
```

Backend không cần chủ động gửi metric.

## 8. Nâng cao cần lưu ý

### 8.1 Không public actuator production

Local có thể permit:

```text
/actuator/prometheus
```

Production nên:

```text
Chỉ expose trong internal network
Chặn public internet
Dùng reverse proxy/VPN/basic auth nếu cần
```

Vì metric có thể lộ thông tin nội bộ như endpoint, JVM, database pool.

### 8.2 Cardinality

Cardinality là số lượng combination label.

Không nên tạo metric label chứa:

```text
userId
email
barcode
borrowId
idempotencyKey
raw URL có path variable thật
```

Sai:

```text
checkout_total{memberId="123", barcode="LIB-..."}
```

Đúng:

```text
checkout_total{result="success"}
checkout_total{result="failed", reason="BORROW_LIMIT_EXCEEDED"}
```

Lý do:

```text
Label quá nhiều giá trị sẽ làm Prometheus phình bộ nhớ và query chậm.
```

### 8.3 Histogram latency

Dashboard hiện dùng average latency vì dễ hiểu.

Production nên dùng percentile:

```text
p95 latency
p99 latency
```

Muốn vậy cần bật histogram cho HTTP server requests rồi query bucket:

```promql
histogram_quantile(
  0.95,
  sum(rate(http_server_requests_seconds_bucket{job="library-service"}[5m])) by (le, uri, method)
)
```

### 8.4 Alerting

Sau dashboard, bước nâng cao là alert.

Ví dụ rule nên có:

```text
ServiceDown: up == 0 trong 1 phút
HighErrorRate: 5xx > 5% trong 5 phút
HighLatency: average latency > 1s trong 5 phút
HighDbPoolUsage: active connections gần max
```

Có thể dùng Alertmanager để gửi email/Slack/Discord.

### 8.5 Custom business metrics

Hiện project đã có technical metrics tự động.

Nâng cao hơn, bạn có thể thêm business metrics bằng Micrometer:

```text
library_checkout_total{result="success|failed"}
library_checkin_total{result="success|failed"}
library_hold_created_total
library_fine_calculated_total
library_csv_import_rows_total{result="success|failed"}
library_auto_renewal_total{result="success|failed", reason="..."}
```

Business metrics rất gây ấn tượng trong đồ án vì nó gắn monitoring với nghiệp vụ thư viện, không chỉ JVM/HTTP.

### 8.6 Logs, metrics, traces

Monitoring production thường gồm 3 trụ:

```text
Metrics: Prometheus + Grafana
Logs: Logback/file/ELK/Loki
Traces: OpenTelemetry/Tempo/Jaeger
```

Project hiện có:

```text
Metrics: Actuator/Micrometer/Prometheus
Logs: structured logging với traceId
```

Nếu nâng cấp nữa:

```text
Thêm OpenTelemetry tracing để trace request qua Controller -> Service -> DB.
```

## 9. Troubleshooting

### Prometheus target DOWN

Kiểm tra:

```text
Backend có chạy port 8080 không?
Mở được http://localhost:8080/actuator/prometheus không?
Docker có resolve host.docker.internal không?
```

Nếu backend chạy trong container, sửa target trong `prometheus/prometheus.yml`:

```yaml
targets:
  - app:8080
```

### Grafana không có dashboard

Kiểm tra:

```text
docker compose logs grafana
```

Và volume mount:

```text
./grafana/provisioning:/etc/grafana/provisioning
./grafana/dashboards:/var/lib/grafana/dashboards
```

### Dashboard không có data

Kiểm tra Prometheus query:

```promql
up{job="library-service"}
```

Nếu không có data, vấn đề nằm ở Prometheus scrape, không phải Grafana.
