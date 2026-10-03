# PDF Cleaning Work Items By Priority

## Scope

Tài liệu này là danh sách công việc cho phần **PDF cleaning** sau khi PDF đã được validate và parse ra text/Markdown theo page.

Hiện tại chưa làm:

```text
Docling
OCR
Vision model
Image/table/formula description
```

Hiện tại chỉ tập trung vào:

```text
PyMuPDF4LLM parsed pages
  -> clean text/Markdown an toàn
  -> giữ page metadata
  -> tạo cleaned artifacts
  -> chuẩn bị cho chunking
```

## Trạng thái hiện tại

Đã implement và có unit test đến hết phần repeated header/footer removal:

```text
Step 1  Cleaning contract
Step 2  Cleaning config/version
Step 3  Unicode normalization
Step 4  Whitespace normalization
Step 5  Structural line classification/protection
Step 6  Page number removal
Step 7  Line-end hyphenation repair
Step 8  Paragraph line-break repair
Step 9A Repeated header/footer detect/report
Step 9B Repeated header/footer removal behind a disabled-by-default flag
```

Lưu ý integration hiện tại:

```text
PdfCleaner đã tồn tại và test pass.
IngestionPipeline chính vẫn chưa dùng PdfCleaner page-wise.
IngestionPipeline chính vẫn đang dùng clean_text() + RecursiveChunker.
LlamaIndex Document/Node adapter chưa được tích hợp.
SeaweedFS hiện được đọc bằng S3-compatible adapter tự viết, chưa dùng LlamaIndex S3 Reader.
```

## Hướng LlamaIndex được chọn

Có thể chuyển sang style LlamaIndex theo hướng:

```text
ParsedDocument page-wise
  -> LlamaIndex Document per page, raw text + metadata
  -> PdfCleaningTransformation dùng lại PdfCleaner nội bộ
  -> cleaned LlamaIndex Documents
  -> LlamaIndex NodeParser/SentenceSplitter
  -> Nodes/chunks
```

Điểm cần giữ:

```text
1 page = 1 LlamaIndex Document
1 Document có thể sinh nhiều Node
Node mới là chunk thật sự đem đi embedding/Qdrant
Metadata page_number/bookId/ebookId/cleaning_version phải đi từ Document xuống Node
```

Không khuyến nghị đổi sang LlamaIndex S3 Reader ở phase này.
RAG worker vẫn nên dùng S3-compatible adapter hiện tại để:

```text
HEAD object trước khi download
validate size/checksum/magic bytes/PDF structure/text layer
dùng local temp file seekable cho pypdf/PyMuPDF4LLM
cleanup rõ trong task worker
```

LlamaIndex nên bắt đầu từ sau bước parse page-wise, không phải thay thế storage adapter ngay.

## Bước tiếp theo khuyến nghị sau Step 9B

Có 2 nhánh có thể đi tiếp:

```text
Nhánh A — tiếp tục hoàn thiện cleaner:
  Step 10: detect heading và section metadata
  Step 11: classify TOC/references/caption
  Step 12: quality report/gate mạnh hơn
  Step 13-14: lưu cleaned artifacts local rồi upload rag-artifacts

Nhánh B — bắt đầu kiến trúc LlamaIndex:
  tạo adapter ParsedPage -> LlamaIndex Document per page
  tạo PdfCleaningTransformation dùng lại PdfCleaner hiện có
  dùng LlamaIndex NodeParser/SentenceSplitter để tạo Node
  map Node về chunk model hiện tại
```

Khuyến nghị hiện tại:

```text
Nếu mục tiêu là học/đổi kiến trúc theo LlamaIndex Direction B:
  làm nhánh B nền tảng trước, phạm vi thật nhỏ, có test riêng.

Nếu mục tiêu là tăng chất lượng clean text:
  tiếp tục Step 10 trước.
```

## Nguyên tắc ưu tiên

Ưu tiên cao nhất là những việc giúp:

1. Không làm hỏng nội dung.
2. Giữ citation theo page.
3. Không đẩy dữ liệu lỗi vào chunking/embedding.
4. Debug được khi RAG trả lời sai.
5. Có thể reprocess khi đổi rule clean.

Các việc “đẹp hơn”, “thông minh hơn”, hoặc “nâng cao chất lượng semantic” để sau.

## Priority 1 — Bắt buộc để cleaner có nền đúng

### 1. Định nghĩa cleaning contract

Công việc:

- Xác định input/output chính thức của cleaner.
- Input không phải PDF gốc, mà là list page đã parse.
- Output là list page đã clean + report.

Input mục tiêu:

```text
ParsedPage
  page_number
  raw_markdown
  metadata
```

Output mục tiêu:

```text
CleanedPage
  page_number
  cleaned_markdown
  section_title
  warnings
  metadata
```

Lý do ưu tiên cao:

- Nếu không có contract rõ, cleaner sẽ biến thành một hàm `clean_text(str)` quá đơn giản và khó mở rộng.
- Sau này chunker cần `page_number`, `section_title`, `chunk_type`.

Done khi:

- Có model hoặc dataclass rõ.
- Unit test có thể gọi cleaner bằng pages giả.

### 2. Thêm cleaning version

Công việc:

- Đặt version cho bộ rule cleaning.
- Ví dụ: `pdf-clean-v1.0.0`.
- Version phải đi theo artifact/chunk metadata.

Lý do:

- Sau này đổi rule clean thì biết document nào đã clean bằng version cũ.
- Reprocess không bị mù.

Done khi:

- Config có `PDF_CLEANING_VERSION`.
- Output cleaned report/chunk metadata có `cleaning_version`.

### 3. Giữ page-wise cleaning

Công việc:

- Cleaner xử lý từng page, không gộp toàn bộ document thành một string lớn.
- Có thể dùng toàn bộ pages để detect header/footer, nhưng output vẫn theo page.

Lý do:

- Giữ citation.
- Header/footer detection cần so sánh giữa các page.
- Debug page lỗi dễ hơn.

Done khi:

- Cleaned output vẫn giữ `page_number`.
- Không có bước nào làm mất page boundary.

### 4. Tạo quality report cơ bản

Công việc:

- Tạo report sau cleaning.
- Report không cần phức tạp ngay, nhưng phải có metric nền.

Metric tối thiểu:

```text
page_count
raw_char_count
cleaned_char_count
empty_pages
text_pages_ratio
warnings
quality_status
```

Lý do:

- Không thể biết clean tốt hay tệ nếu không có số liệu.
- Dùng để quyết định có cho đi tiếp sang chunking không.

Done khi:

- Cleaner trả về `quality_report`.
- Nếu cleaned text rỗng hoặc quá ít thì có warning/fail rõ.

## Priority 2 — Làm sạch an toàn, ít rủi ro

### 5. Normalize Unicode nhẹ

Công việc:

- Chuẩn hóa ligature và ký tự invisible phổ biến.
- Ví dụ:

```text
ﬁ -> fi
ﬂ -> fl
non-breaking space -> normal space
zero-width char -> remove
```

Lưu ý:

- Với tiếng Việt và ký hiệu kỹ thuật, không normalize quá mạnh.
- Ưu tiên NFC hoặc rule thủ công an toàn trước.

Done khi:

- Test ligature pass.
- Không phá tiếng Việt có dấu.
- Không phá công thức cơ bản.

### 6. Normalize whitespace cơ bản

Công việc:

- Xóa trailing spaces.
- Collapse nhiều blank lines.
- Convert CRLF/CR về LF.
- Collapse nhiều spaces trong paragraph thường.

Không được phá:

```text
code block
markdown table
bullet list
numbered list
```

Done khi:

- Paragraph bớt khoảng trắng.
- Code/table/list vẫn giữ cấu trúc.

### 7. Remove page number đơn giản

Công việc:

- Xóa các dòng chỉ là số trang hoặc pattern page number.

Pattern ban đầu:

```text
12
- 12 -
Page 12
Page 12 of 200
12 / 200
Trang 12
Trang 12/200
```

Lý do:

- Page number nhiễu embedding và retrieval.

Done khi:

- Chỉ remove page number ở đầu/cuối page.
- Không xóa section number như `3.2 Borrowing Module`.

### 8. Fix hyphenation cuối dòng

Công việc:

- Sửa từ bị tách do wrap dòng PDF.

Ví dụ:

```text
retrieval augmen-
ted generation
```

thành:

```text
retrieval augmented generation
```

Không được phá:

```text
state-of-the-art
long-term
well-known
```

Done khi:

- Chỉ xử lý hyphen ở cuối dòng.
- Test case từ ghép thật không bị sửa sai.

### 9. Fix line breaks trong paragraph

Công việc:

- Merge các dòng paragraph bị xuống dòng do PDF wrap.

Ví dụ:

```text
The RAG system retrieves
relevant chunks from vector
database.
```

thành:

```text
The RAG system retrieves relevant chunks from vector database.
```

Không merge:

```text
heading
bullet list
numbered list
table row
code block
caption
```

Done khi:

- Paragraph đọc tự nhiên hơn.
- List/table/code vẫn còn newline.

## Priority 3 — Giữ cấu trúc quan trọng cho chunking

### 10. Detect heading cơ bản

Công việc:

- Nhận diện heading/section title.

Pattern:

```text
1. Introduction
1.1 Background
Chapter 2: Literature Review
CHAPTER 3
III. Methodology
```

Lý do:

- Chunk nên biết nó thuộc section nào.
- Citation và answer context tốt hơn.

Done khi:

- `section_title` được gắn vào page/chunk metadata.
- Không xóa số section.

### 11. Preserve bullet list và numbered list

Công việc:

- Normalize bullet symbol về `-`.
- Giữ mỗi item trên một dòng.

Ví dụ:

```text
• Validate PDF
• Extract text
```

thành:

```text
- Validate PDF
- Extract text
```

Done khi:

- List không bị merge thành paragraph.
- Numbered list vẫn giữ `1.`, `2.`, `3.`.

### 12. Preserve code/config/log block cơ bản

Công việc:

- Detect block có vẻ là code/config/log.
- Không collapse whitespace quá tay trong block đó.

Dấu hiệu:

```text
{ }
public class
SELECT *
spring:
docker compose
Exception
at com.example...
```

Done khi:

- JSON/YAML/SQL/stack trace không bị biến thành một dòng văn xuôi.

### 13. Preserve table-like block cơ bản

Công việc:

- Detect table markdown hoặc table-like lines.
- Không merge các row.
- Không collapse toàn bộ spacing nếu spacing đang đóng vai trò cột.

Dấu hiệu:

```text
| col1 | col2 |
nhiều dòng có nhiều khoảng trắng cột
nhiều số liệu theo hàng
```

Done khi:

- Markdown table không bị phá.
- Table-like block có thể được chunk riêng sau này.

### 14. Preserve caption

Công việc:

- Giữ các dòng caption.

Pattern:

```text
Figure 3.1: ...
Table 2.1: ...
Hình 4.2: ...
Bảng 5.1: ...
```

Lý do:

- Caption thường chứa ý nghĩa của ảnh/bảng dù chưa làm vision/OCR.

Done khi:

- Caption không bị xóa như header/footer.
- Caption có thể được gắn `chunk_type=caption` sau này.

## Priority 4 — Giảm nhiễu nâng cao

### 15. Detect và remove repeated header/footer

Công việc:

- Lấy top/bottom lines của mỗi page.
- Đếm tần suất.
- Dòng lặp ở nhiều page thì detect/report.
- Removal chỉ bật khi có flag riêng.

Config đề xuất:

```text
PDF_CLEAN_HEADER_FOOTER_DETECTION_ENABLED=true
PDF_CLEAN_HEADER_FOOTER_TOP_LINES=3
PDF_CLEAN_HEADER_FOOTER_BOTTOM_LINES=3
PDF_CLEAN_HEADER_FOOTER_MIN_REPEAT_RATIO=0.4
PDF_CLEAN_HEADER_FOOTER_REMOVAL_ENABLED=false
```

Lý do:

- Header/footer lặp làm retrieval nhiễu.

Rủi ro:

- Có thể xóa nhầm chapter title nếu rule quá mạnh.

Done khi:

- Có report dòng nào đã remove.
- Có test không xóa heading thật.
- Removal mặc định tắt để tránh xóa nhầm, chỉ bật sau khi inspect output mẫu.

### 16. Classify table of contents

Công việc:

- Detect mục lục.
- Không xóa khỏi artifact.
- Có thể mark để chunker bỏ qua hoặc giảm priority.

Pattern:

```text
1. Introduction ........ 1
2. Related Work ........ 5
```

Done khi:

- Page/block có metadata `section_type=table_of_contents`.

### 17. Classify references/bibliography

Công việc:

- Detect section References/Bibliography/Tài liệu tham khảo.
- Không xóa khỏi cleaned artifact.
- Có config sau này quyết định index hoặc không.

Done khi:

- Metadata biết block/page thuộc references.

### 18. Deduplicate liền kề

Công việc:

- Xóa line/paragraph trùng lặp liền kề do extractor lỗi.

Không làm:

- Dedup toàn document quá mạnh.

Lý do:

- Tránh xóa nhầm warning/template lặp có ý nghĩa trong tài liệu kỹ thuật.

Done khi:

- Chỉ dedup gần nhau hoặc trong header/footer scope.

## Priority 5 — Policy/observability sau khi cleaner ổn

### 19. Detect language mức document/page

Công việc:

- Detect ngôn ngữ chính ở mức đơn giản.
- Có thể để sau, không chặn chunking.

Lý do:

- Tiếng Việt/tiếng Anh có cách chunk và normalize khác nhau.

Done khi:

- Quality report có `language` hoặc `languages`.

### 20. Sensitive data detect-only

Công việc:

- Detect email/phone/token-like text ở mức warning.
- Chưa tự redact.

Lý do:

- Redact sai có thể phá tài liệu.
- Nhưng log warning hữu ích cho production.

Done khi:

- Quality report có warnings.
- Không thay đổi content khi chưa có policy rõ.

### 21. Upload cleaned artifacts vào `rag-artifacts`

Công việc:

- Lưu:

```text
parsed/page_0001.raw.md
cleaned/page_0001.clean.md
processed_doc.md
quality_report.json
manifest.json
```

Lý do:

- Debug được parse/clean.
- Re-chunk không cần parse PDF lại.

Done khi:

- Artifacts có object key ổn định theo `doc_ebook_{ebookId}/versions/v{checksumPrefix}`.

## Không làm trong clean phase này

Không làm:

```text
Docling parser
OCR
Vision description
Image crop
Formula crop
Embedding
Qdrant upsert
Airflow DAG
```

Những phần đó nằm sau khi basic cleaning ổn.
