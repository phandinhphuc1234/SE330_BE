# Grafana trong đồ án thư viện

Grafana dùng để trực quan hóa metric mà Prometheus đã thu thập.

Trong project này, Grafana được chạy bằng Docker Compose và tự động có sẵn:

- Datasource `Prometheus`
- Dashboard `Library Service Overview`

## Truy cập

```text
http://localhost:3001
```

Thông tin đăng nhập mặc định local:

```text
username: admin
password: admin
```

Bạn có thể đổi bằng biến môi trường:

```properties
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=your-password
```

## Dashboard hiện có

Dashboard `Library Service Overview` gồm:

- Service Up
- HTTP request rate
- Average HTTP latency
- Process CPU usage
- JVM memory used
- HikariCP active/idle connections
- HTTP requests by endpoint/status
- JVM live threads

## Nên demo thế nào trong đồ án

1. Start backend.
2. Start Prometheus và Grafana.
3. Gọi vài API như login, search books, checkout preview.
4. Mở Grafana dashboard và chỉ ra request rate/latency thay đổi.
5. Mở Prometheus target page để chứng minh backend đang được scrape.

## Lưu ý nâng cao

- Grafana chỉ hiển thị dữ liệu, không tự thu thập metric.
- Datasource Prometheus được provision từ file, nên container restart vẫn giữ cấu hình.
- Dashboard JSON nằm trong repo, phù hợp để nộp đồ án vì có thể tái tạo trên máy khác.
- Production nên đổi password Grafana và đặt sau reverse proxy/VPN.
