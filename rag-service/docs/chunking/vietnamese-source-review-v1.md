# Phiếu review bộ câu hỏi tiếng Việt v1

Trạng thái: **chủ đồ án đã duyệt toàn bộ**, ngày 2026-10-06, qua xác nhận
`mình duyệt hết nha`. Phạm vi: 20 câu hỏi/23 evidence anchors và 7 heading
trong phiếu này. [Biên bản approval theo đúng hash/version](../../tests/fixtures/chunking/vietnamese_source_owner_review_v1.json).

Đây là xác nhận trực tiếp của chủ đồ án, không phải đánh giá blind độc lập của
chuyên gia. Manifest v1 và hai báo cáo BM25 trước review giữ nguyên trạng thái
lịch sử `assistant_source_checked`/`pending`; approval được ghi riêng, không
sửa hồi tố. Approval này không tự cho phép provider calls hay promote production.

## Nguồn và cách đối chiếu

Nguồn: Ngân hàng Thế giới. 2022. *Từ chặng đường cuối đến chặng đường kế tiếp -
Đánh giá thực trạng nghèo và bình đẳng của Việt Nam năm 2022 - Tổng quan
(Tiếng Việt).* Washington, DC: Ngân hàng Thế giới.
[PDF chính thức](https://documents1.worldbank.org/curated/en/099910004262241595/pdf/P1762610100be30c90a1a7053e9b39aa347.pdf).
[Giấy phép CC BY 3.0 IGO](https://creativecommons.org/licenses/by/3.0/igo/),
ghi trên trang PDF vật lý 3. Các câu hỏi/đáp án tóm tắt là phần chỉnh lý phục vụ
evaluation của dự án, không phải sản phẩm được World Bank chứng thực. Những
quan điểm và nhận định trong phần chỉnh lý thuộc trách nhiệm của tác giả phần
chỉnh lý, không phải của World Bank. Không tái sử dụng ảnh/biểu đồ bên thứ ba.

Đây là **bản dịch tiếng Việt chính thức của một báo cáo**, không phải tiểu
thuyết gốc tiếng Việt. Câu hỏi chỉ hỏi nội dung/số liệu lịch sử trong báo cáo,
không khẳng định số liệu hoặc chính sách đó còn đúng tại thời điểm hiện tại.

File local: `data/chunking-comparison/vietnam-poverty-overview-2022-vi.pdf`.
Chọn trang bằng số **PDF vật lý** trong bảng; từ trang PDF 9 đến 29,
số trang in trên giấy bằng `PDF - 8`. Không dùng số trang in làm label.

[Manifest câu hỏi và quote chính xác](../../tests/fixtures/chunking/vietnamese_source_v1.json)
đã khóa trước khi chạy retrieval. Có 20 câu và 23 evidence anchors. Tại lần đầu
chấm điểm, đây là source mới; sau khi đọc kết quả, nó trở thành regression data,
không tiếp tục gọi là blind holdout.

## 20 câu đã duyệt

Mỗi dòng cần kiểm tra: câu hỏi rõ nghĩa, đáp án đủ các ý, quote trên trang gốc
thật sự hỗ trợ đáp án, đơn vị/thời gian đúng. Bạn có thể trả lời
`đồng ý 1–20` sau khi đọc, hoặc chỉ ra số câu và điều cần sửa. Xác nhận duyệt
toàn bộ đã được ghi nhận. Nếu cần điều chỉnh sau này, lưu review riêng hoặc tạo
dataset v2; không sửa âm thầm labels v1 để tăng điểm.

| # / ID | Câu hỏi tóm tắt | Đáp án đối chiếu dự kiến | Trang PDF / in | Review của bạn |
| --- | --- | --- | --- | --- |
| 1 `vi-gdp-per-person` | GDP/người theo giá cố định 2015 thay đổi thế nào từ 1986 đến 2020? | 481 lên 2.655 USD. | 9 / 1 | Đã duyệt |
| 2 `vi-poverty-rate` | Tỷ lệ nghèo theo chuẩn 3,20 USD/ngày PPP 2011 ở 2010 và 2020? | 16,8% và 5,0%. | 9 / 1 | Đã duyệt |
| 3 `vi-high-income-goal` | Mốc thu nhập cao và mức tăng trưởng GDP theo giá so sánh cần có? | Năm 2045; gần 7% mỗi năm. Không lẫn với GDP bình quân đầu người ở câu 14. | 11 / 3 | Đã duyệt |
| 4 `vi-poverty-population` | Số người thoát nghèo và số còn nghèo trong giai đoạn 2010–2020? | 10 triệu thoát nghèo; còn 5 triệu người vào 2020. | 12 / 4 | Đã duyệt |
| 5 `vi-leading-region` | Vùng giảm nghèo tuyệt đối tốt nhất và yếu tố được báo cáo gắn với kết quả đó? | Đông Bắc; các hoạt động công nghiệp phát triển. | 12 / 4 | Đã duyệt |
| 6 `vi-underemployment-definition` | Chú giải định nghĩa thiếu việc làm thế nào? | Làm dưới 35 giờ/tuần **và** muốn làm nhiều hơn. | 29 / 21 | Đã duyệt |
| 7 `vi-covid-labor-loss` | Lao động mất việc/giảm lương quý I/2021 và tỷ lệ trong tổng lao động? | 9,1 triệu người; 12,8%. | 14 / 6 | Đã duyệt |
| 8 `vi-poor-group-shares` | Dân tộc thiểu số và thuần nông chiếm bao nhiêu trong người nghèo và dân số năm 2020? | DTTS: 79% người nghèo, 15% dân số; thuần nông: 66% người nghèo, 16% dân số. | 15 / 7 | Đã duyệt |
| 9 `vi-mtqg-investment` | Nguồn lực MTQG cấp xã 2010–2019 bằng tiền Việt và USD? | Gần 560 nghìn tỷ đồng, tương đương 25 tỷ USD. | 17 / 9 | Đã duyệt |
| 10 `vi-middle-class-mobility` | Trung lưu năm 2016 rơi xuống nhóm thấp hơn vào 2018 chiếm tỷ lệ nào? | Gần 40%. | 17 / 9 | Đã duyệt |
| 11 `vi-human-capital` | Thành phần vốn nhân lực và vai trò với nghèo liên thế hệ? | Giáo dục, kỹ năng, sức khỏe; quyết định năng suất, duy trì tăng trưởng và góp phần phá bẫy nghèo liên thế hệ. | 18 / 10 | Đã duyệt |
| 12 `vi-tutoring-inequality` | Hộ giàu/nghèo chênh chi học thêm thế nào ở các cấp học? | Tiểu học/THCS công lập: 5,6 lần (2020); THPT: 10 lần. Cần đủ 2 anchors. | 19 / 11 | Đã duyệt |
| 13 `vi-school-closure` | Hộ có con 6–18 tuổi bị nghỉ học từ 9/2020 đến 3/2021? | 72% hộ. | 19 / 11 | Đã duyệt |
| 14 `vi-productivity-target` | Tăng trưởng năng suất/lao động cần chuyển từ mức nào sang mức nào? | 5,3%/năm trong 2012–2018 lên 6,6%/năm. Không nhầm với tăng GDP 7%. | 19 / 11 | Đã duyệt |
| 15 `vi-aging-population` | Dự báo tỷ trọng người từ 65 tuổi ở 2045 so với thời điểm báo cáo? | 10% lên 20% dân số. | 21 / 13 | Đã duyệt |
| 16 `vi-shock-definitions` | Cú sốc riêng một hộ khác cú sốc chung cộng đồng thế nào? | Đặc thù: cá nhân/hộ cụ thể; đồng biến: cộng đồng/khu vực/quốc gia. Không cần tư vấn kinh tế ngoài nguồn. | 22 / 14 | Đã duyệt |
| 17 `vi-relief-reach` | Gói hỗ trợ lao động phi chính thức: mục tiêu và thực tế tiếp cận? | 5 triệu so với 1 triệu người. | 23 / 15 | Đã duyệt |
| 18 `vi-cash-electricity-impact` | Hỗ trợ tiền mặt và trợ giá điện giảm nghèo thêm bao nhiêu? | Lần lượt 1,05 và 0,15 **điểm phần trăm**, không phải phần trăm tương đối. | 23 / 15 | Đã duyệt |
| 19 `vi-not-poor-not-secure` | Người thoát nghèo nhưng chưa an toàn kinh tế cần hỗ trợ gì? | Lưới an sinh để ngăn tái nghèo; vốn nhân lực/kỹ năng để làm việc năng suất cao hơn. | 26 / 18 | Đã duyệt |
| 20 `vi-digital-two-domains` | Công nghệ số giúp gì cho nông nghiệp và chi trả an sinh? | Nông nghiệp: tăng năng suất thay thâm dụng lao động; an sinh: chi trả nhanh, an toàn, hiệu quả, đúng người/đúng lúc. Cần cả 2 trang. | 26–27 / 18–19 | Đã duyệt |

## Review ranh giới section

Đối chiếu 7 heading trên trang PDF 9, 11, 18, 25, 28, 29, 32: Giới thiệu;
Phần I; Phần II; Các chính sách cho thời gian tới; Tham khảo; Chú giải;
Với sự hỗ trợ của. Front matter trước trang 9 không nằm trong section oracle.
Các tiểu mục bên trong chưa có hierarchy oracle; đây chỉ là top-level section
audit. Không lấy `chapter_title` do chunker sinh ra làm ground truth.

Trạng thái: **đã duyệt cả 7 heading** trong xác nhận duyệt toàn bộ ngày 2026-10-06.

## Cách ghi nhận review sau này

- Ghi tên/ngày và ID câu được chấp thuận trong artifact review riêng, gắn với
  SHA256 manifest v1: `b69caf1f7ba2dcd5a83f5e264613e9e2776700c4ab387bf3ee71beeac8758ba6`.
- Câu cần sửa: tạo phiên bản mới, ghi lý do và giữ nguyên v1 cùng report lần đầu.
- Việc user chấp thuận không làm v1 trở lại thành blind holdout và không cho
  phép tự động promote ranking/chunker. Gate human review ở report cũ không
  được sửa hồi tố.

Flow/purpose: đọc câu hỏi → mở trang PDF vật lý → đối chiếu quote và đầy đủ ý
→ ghi review theo ID/hash. Mục đích là tạo ground truth đáng tin trước bước
đánh giá Dense/hybrid, không biến nhãn assistant thành nhãn do người duyệt.
