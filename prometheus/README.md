# Prometheus trong đồ án thư viện

Prometheus là hệ thống thu thập metric theo mô hình pull. Thay vì backend chủ động gửi dữ liệu đi, Prometheus định kỳ gọi endpoint metric của backend.

Trong project này, Spring Boot expose metric tại:

```text
GET http://localhost:8080/actuator/prometheus
```

Prometheus trong Docker sẽ scrape endpoint đó qua:

```text
host.docker.internal:8080/actuator/prometheus
```

## Chạy local

1. Start backend bằng IntelliJ hoặc Maven.
2. Start Docker Compose:

```powershell
docker compose up -d prometheus grafana
```

3. Mở Prometheus:

```text
http://localhost:9090
```

4. Vào `Status > Targets`, kiểm tra job `library-service` phải ở trạng thái `UP`.

## Query cơ bản

Kiểm tra service có sống không:

```promql
up{job="library-service"}
```

Tổng request HTTP trong 5 phút:

```promql
sum(rate(http_server_requests_seconds_count{job="library-service"}[5m]))
```

Latency trung bình:

```promql
sum(rate(http_server_requests_seconds_sum{job="library-service"}[5m]))
/
sum(rate(http_server_requests_seconds_count{job="library-service"}[5m]))
```

JVM memory đang dùng:

```promql
sum(jvm_memory_used_bytes{job="library-service"})
```

HikariCP active connections:

```promql
hikaricp_connections_active{job="library-service"}
```

## Lưu ý

- Nếu job `library-service` bị `DOWN`, kiểm tra backend đã chạy ở port `8080` chưa.
- Nếu chạy backend trong container thay vì IntelliJ, target nên đổi từ `host.docker.internal:8080` sang tên service Docker của backend.
- Không expose `/actuator/prometheus` public trên production nếu chưa có network/security bảo vệ.
