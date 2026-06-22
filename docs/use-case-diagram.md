# Use Case Diagram - QuanLyThuVien

File PlantUML chính: [`docs/use-case-diagram.puml`](./use-case-diagram.puml)

## Nguồn phân tích

Sơ đồ được rút ra từ code hiện tại trong các nhóm chính:

- Controller/API: `AuthController`, `BookController`, `CirculationController`, `HoldController`, `PaymentController`, `EbookLoanController`, `EbookReaderController`, các `Staff*Controller`, `AdminPaymentController`.
- Phân quyền: `SecurityConfig`, các `@PreAuthorize`.
- Service/use case: checkout, check-in, renewal, hold queue, payment applier, ebook reader session, scheduled jobs.
- Job nền: overdue marking, hold expiry, due-soon reminder, auto-renewal, book image cleanup.

## Actor

- **Khách**: tra cứu catalog, đăng ký, xác thực email, đăng nhập.
- **Người dùng đã đăng nhập**: refresh/logout, xem/cập nhật hồ sơ.
- **Bạn đọc (`MEMBER`)**: mượn/gia hạn/xem lịch sử, giữ chỗ, xem và thanh toán phạt, mượn/đọc ebook, xem payment/biên lai.
- **Thủ thư (`LIBRARIAN`)**: quản lý catalog/media, checkout/check-in/gia hạn hộ, quản lý bạn đọc/lượt mượn/hold.
- **Quản trị viên (`ADMIN`)**: kế thừa nhóm thủ thư, thêm quản lý thể loại, xóa sách/bản sao, dashboard và tra cứu thanh toán.
- **VNPAY**: tạo URL thanh toán, gửi IPN, cung cấp dữ liệu return để backend xác nhận fallback.
- **Email/SMTP**: gửi email xác thực, nhắc trả sách, auto-renewal.
- **Cloudinary**: lưu/xóa media và tạo signed URL đọc ebook.
- **Scheduler**: kích hoạt các job định kỳ.

## Cách render nhanh

Nếu máy đã có PlantUML:

```powershell
plantuml docs\use-case-diagram.puml
```

Hoặc mở file `.puml` bằng plugin PlantUML trong IDE để xuất PNG/SVG.
