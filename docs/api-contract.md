# Quy ước HTTP và API response

## Response thông thường

Controller API trả `ResponseEntity<ApiResponse<T>>`. Format thành công:

```json
{
  "success": true,
  "message": "Mô tả kết quả",
  "data": {},
  "meta": null,
  "timestamp": "2026-09-08T16:00:00"
}
```

Danh sách phân trang giữ items trong `data`; `meta` là `PageMeta` gồm `page`,
`size`, `totalElements`, `totalPages`, `first`, `last`. Danh sách không có kết
quả trả `data: []`, không dùng lỗi 404.

Lỗi dùng cùng envelope và có trace ID khi đi qua global exception handler hoặc
security filter:

```json
{
  "success": false,
  "code": "UNAUTHORIZED",
  "message": "Bạn chưa đăng nhập hoặc token không hợp lệ",
  "traceId": "...",
  "timestamp": "2026-09-08T16:00:00"
}
```

## HTTP status

| Trường hợp | Status |
| --- | --- |
| Đọc/cập nhật/xóa thành công theo contract hiện có | `200 OK` |
| Tạo resource | `201 Created` |
| Tạo Book/Payment | `201 Created` + `Location` đến endpoint đọc lại được |
| Nhận import CSV bất đồng bộ | `202 Accepted` |
| Validation/input sai | `400 Bad Request` |
| Chưa đăng nhập/token không hợp lệ | `401 Unauthorized` |
| Sai role hoặc không sở hữu resource | `403 Forbidden` |
| Trạng thái xung đột/idempotency key không hợp lệ | `409 Conflict` |

`timestamp` đang giữ định dạng cũ `LocalDateTime` để không làm vỡ client hiện
có. Chỉ đổi sang `Instant`/offset sau khi đối chiếu frontend consumer.

## Ngoại lệ contract có chủ đích

- `GET /api/payments/ipn/vnpay`: trả `PaymentIpnResponse` với `RspCode` và
  `Message` đúng yêu cầu VNPAY, không bọc `ApiResponse`.
- `GET /api/books/import-csv/{jobId}/events`: trả `text/event-stream` SSE cho
  EventSource, không bọc `ApiResponse`.
- Các endpoint mutation mượn/trả/hold/payment yêu cầu `Idempotency-Key` theo
  contract từng endpoint; retry cùng request nhận lại kết quả đã cache, còn
  cùng key nhưng body khác trả conflict.
