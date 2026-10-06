# PDF Cleaning Stepwise Implementation Plan

## Mục tiêu

Implement phần clean PDF theo từng bước nhỏ để có thể kiểm tra sau mỗi bước.

Không làm một lần hết.

Scope hiện tại:

```text
Không Docling
Không OCR
Không vision model
Không embedding/Qdrant
```

Input của cleaner:

```text
PyMuPDF4LLM parsed pages
```

Output của cleaner:

```text
cleaned pages
quality report
metadata đủ để chunk
```

## Trạng thái hiện tại trước khi bắt đầu

Đã có:

```text
PDF validation từ S3
PyMuPDF4LLM parser
B0 ParsedDocument -> LlamaIndex Document adapter
B1 PdfCleaningTransformation
B2 SentenceSplitter/Node adapter
document_chunks persistence
```

Pipeline chính hiện đã chuyển sang:

```text
ParsedDocument
  -> LlamaIndex Document
  -> PdfCleaningTransformation
  -> LlamaIndex SentenceSplitter Nodes
  -> internal Chunk dataclass
  -> document_chunks persistence
```

`clean_text()` và `RecursiveChunker` vẫn còn trong codebase như phần cũ/tiện ích, nhưng ingestion PDF chính không còn dùng đường đó.

## Trạng thái hiện tại sau Step 9B

Đã hoàn thành phần cleaner độc lập đến hết Step 9B và đã tích hợp B0-B2 vào pipeline chính:

```text
Step 1  Cleaning contract
Step 2  Config/version
Step 3  Unicode normalization
Step 4  Whitespace normalization
Step 5  Structural line classification/protection
Step 6  Page number removal
Step 7  Line-end hyphenation repair
Step 8  Paragraph line-break repair
Step 9A Repeated header/footer detect/report
Step 9B Repeated header/footer removal behind disabled-by-default flag
```

Các điểm chưa tích hợp:

```text
IngestionPipeline chính đã dùng PdfCleaner thông qua PdfCleaningTransformation.
IngestionPipeline chính đã dùng LlamaIndex SentenceSplitter/Node adapter.
Đã có LlamaIndex Document adapter bước B0.
Đã có PdfCleaningTransformation của LlamaIndex bước B1.
Chưa dùng LlamaIndex S3 Reader; hiện vẫn dùng S3-compatible adapter + local temp file.
```

Cập nhật sau khi bắt đầu hướng LlamaIndex:

```text
Step B0 đã có adapter ParsedDocument -> LlamaIndex Document.
Adapter mới chỉ đóng gói parsed page vào Document, chưa thay parser và chưa thay chunker.
Step B1 đã có PdfCleaningTransformation.
Transformation gọi PdfCleaner hiện có và trả về LlamaIndex Document đã clean.
Step B2 đã có SentenceSplitter/NodeParser adapter.
Adapter tạo LlamaIndex Nodes, enrich metadata chunk, và map được về internal Chunk dataclass.
IngestionPipeline chính đã dùng B0+B1+B2 để tạo chunks persist vào PostgreSQL.
```

Quyết định thiết kế hiện tại:

```text
Vẫn giữ S3-compatible object storage adapter để HEAD/download/validate PDF.
Không đổi sang LlamaIndex S3 Reader ở phase clean này.
LlamaIndex nên bắt đầu sau parse: ParsedPage -> Document -> clean transform -> NodeParser.
```

Lý do chưa đổi sang LlamaIndex S3 Reader:

```text
Validation hiện cần HEAD object, checksum, magic bytes, pypdf structure check.
PyMuPDF4LLM/pypdf xử lý ổn nhất với local seekable file.
Temp file chỉ sống trong task worker và được cleanup sau job.
S3 Reader phù hợp hơn cho ingestion đơn giản, không phù hợp để thay thế validation storage layer ngay.
```

## Bước tiếp theo ngay sau Step 9B

Nếu tiếp tục đúng thứ tự clean ban đầu:

```text
Step 10 — Heading và section metadata
```

Nếu muốn bắt đầu chuyển sang hướng LlamaIndex Direction B ngay:

```text
Step B0 — LlamaIndex page document adapter
  ParsedPage -> llama_index.core.Document
  giữ metadata page_number/document_id/book_id/ebook_id/cleaning_version

Step B1 — PdfCleaningTransformation
  nhận Documents raw theo page
  gọi PdfCleaner hiện có
  trả Documents đã clean

Step B2 — NodeParser/SentenceSplitter proof
  cleaned Document -> Nodes
  kiểm tra Node giữ metadata page/document
```

Khuyến nghị:

```text
Làm Step B0-B2 trước nếu mục tiêu là đổi kiến trúc theo LlamaIndex.
Sau đó quay lại Step 10-14 để tăng chất lượng clean.
```

## Nguyên tắc implement

Sau mỗi bước:

```text
1. Có unit test riêng.
2. Có thể chạy pytest riêng cho cleaner.
3. Không thay đổi quá nhiều file không liên quan.
4. Nếu kết quả không ổn thì rollback/sửa dễ.
5. Chỉ tích hợp vào pipeline khi cleaner đã có test đủ.
```

## Step 1 — Tạo cleaning contract và test fixture

Mục tiêu:

- Tạo data contract cho clean page-wise.
- Chưa thay đổi behavior pipeline.

Đề xuất file:

```text
app/ingestion/cleaners/pdf_cleaner.py
tests/unit/test_pdf_cleaner.py
```

Model tối thiểu:

```text
ParsedPage
  page_number
  raw_text
  metadata

CleanedPage
  page_number
  cleaned_text
  section_title
  warnings
  metadata

CleaningResult
  pages
  quality_report
```

Test:

- Input nhiều page.
- Output giữ đúng page number.
- Empty input fail rõ hoặc trả report rõ.

Điểm dừng để kiểm tra:

```text
Cleaner module tồn tại nhưng pipeline chưa dùng.
```

## Step 2 — Thêm config và version

Mục tiêu:

- Không hard-code rule.
- Có cleaning version.

Đề xuất settings:

```text
PDF_CLEANING_VERSION=pdf-clean-v1.0.0
PDF_CLEAN_NORMALIZE_UNICODE=true
PDF_CLEAN_COLLAPSE_SPACES=true
PDF_CLEAN_MAX_BLANK_LINES=2
```

Test:

- Default config load được.
- `cleaning_version` xuất hiện trong report.

Điểm dừng:

```text
Config có nhưng chưa thay đổi clean logic phức tạp.
```

## Step 3 — Unicode normalization an toàn

Mục tiêu:

- Sửa ký tự lỗi phổ biến từ PDF.

Rule ban đầu:

```text
ﬁ -> fi
ﬂ -> fl
\u00A0 -> normal space
zero-width chars -> remove
control chars không cần thiết -> remove
```

Không làm:

```text
Không lowercase toàn bộ.
Không xóa dấu tiếng Việt.
Không normalize toán học quá mạnh.
```

Test:

- Ligature được sửa.
- Tiếng Việt có dấu còn nguyên.
- Greek/math symbol cơ bản không bị xóa.

Điểm dừng:

```text
Chỉ Unicode rule, chưa line merge/header/footer.
```

## Step 4 — Whitespace normalization cơ bản

Mục tiêu:

- Làm sạch whitespace nhưng không phá cấu trúc.

Rule:

```text
CRLF/CR -> LF
trim trailing spaces
collapse nhiều blank lines
collapse nhiều spaces trong paragraph thường
```

Chưa xử lý:

```text
header/footer
hyphenation
line wrapping
```

Test:

- Paragraph bớt spaces.
- Blank lines giảm còn tối đa 2.
- List markdown không bị merge.

Điểm dừng:

```text
Cleaner vẫn còn tương đối đơn giản nhưng an toàn hơn clean_text cũ.
```

## Step 5 — Protect structural blocks

Mục tiêu:

- Trước khi clean mạnh hơn, phải biết block nào không được phá.

Detect cơ bản:

```text
markdown table
bullet list
numbered list
fenced code block
indented config/code block
caption line
heading line
```

Output có thể chỉ là metadata nội bộ:

```text
line_type = paragraph | heading | list | table | code | caption | blank
```

Test:

- Markdown table giữ row.
- YAML/JSON/code không bị collapse spacing quá tay.
- Bullet list giữ newline.

Điểm dừng:

```text
Chưa cần clean thông minh, chỉ phân loại line/block.
```

## Step 6 — Remove page number

Mục tiêu:

- Remove page number nhiễu ở đầu/cuối page.

Pattern:

```text
12
- 12 -
Page 12
Page 12 of 200
12 / 200
Trang 12
Trang 12/200
```

An toàn:

- Chỉ apply với top/bottom lines.
- Không apply giữa page.
- Không xóa `3.2 Borrowing Module`.

Test:

- Page number cuối page bị xóa.
- Section number còn nguyên.

Điểm dừng:

```text
Có report số page number removed.
```

## Step 7 — Fix hyphenation cuối dòng

Mục tiêu:

- Nối từ bị tách bởi PDF line wrap.

Rule:

```text
line endswith "-"
and next line starts lowercase/letter
and current line is paragraph
and next line is paragraph
```

Test:

- `genera-\ntion` -> `generation`.
- `state-of-the-art` không bị đổi.
- Bullet/list/table không bị ảnh hưởng.

Điểm dừng:

```text
Chỉ hyphenation, chưa merge paragraph general.
```

## Step 8 — Fix paragraph line breaks

Mục tiêu:

- Merge các dòng paragraph bị wrap.

Rule ban đầu:

```text
current line không kết thúc bằng dấu câu mạnh
next line bắt đầu như continuation
cả hai line đều là paragraph
```

Không merge:

```text
heading
list
table
code
caption
blank line boundary
```

Test:

- Paragraph nhiều dòng thành một paragraph tự nhiên.
- List không bị merge.
- Heading không dính vào paragraph nếu không rõ.

Điểm dừng:

```text
Cleaner đã xử lý lỗi PDF phổ biến nhất.
```

## Step 9A — Repeated header/footer detect/report

Mục tiêu:

- Detect header/footer lặp lại giữa các page nhưng chưa xóa.

Config:

```text
PDF_CLEAN_HEADER_FOOTER_DETECTION_ENABLED=true
PDF_CLEAN_HEADER_FOOTER_TOP_LINES=3
PDF_CLEAN_HEADER_FOOTER_BOTTOM_LINES=3
PDF_CLEAN_HEADER_FOOTER_MIN_REPEAT_RATIO=0.4
```

Flow:

```text
collect candidate top/bottom lines
normalize for comparison
count frequency
mark repeated lines
record candidate lines in metadata/report
```

Test:

- Header lặp ở nhiều page được report.
- Heading chapter xuất hiện ít page không bị report sai.
- Page number removal vẫn hoạt động.

Điểm dừng:

```text
Đây là bước detect-only để review output mẫu trước khi bật remove.
```

## Step 9B — Remove repeated header/footer behind flag

Mục tiêu:

- Xóa header/footer lặp lại giữa các page, nhưng chỉ khi bật flag riêng.
- Mặc định production/local vẫn tắt removal để tránh xóa nhầm.

Config:

```text
PDF_CLEAN_HEADER_FOOTER_REMOVAL_ENABLED=false
```

Flow:

```text
detect candidates ở Step 9A
if removal flag disabled:
  giữ nguyên text, chỉ report candidates
if removal flag enabled:
  remove exact repeated top/bottom lines
  record removed_header_footer_count
  record removed_header_footer_lines
```

Test:

- Khi flag off: header/footer candidate vẫn còn trong text.
- Khi flag on: header/footer lặp bị xóa.
- Heading/chapter title không bị xóa khi không đủ repeat ratio.
- Metadata có candidate count và removed count.

Điểm dừng:

```text
Cleaner đã có cơ chế detect/report/remove header-footer nhưng remove vẫn an toàn vì opt-in.
```

## Step 10 — Heading và section metadata

Mục tiêu:

- Detect heading để chunk sau này có context.

Pattern:

```text
1. Introduction
1.1 Background
Chapter 2: ...
CHAPTER 3
III. Methodology
```

Output:

```text
CleanedPage.section_title
line metadata: heading_level
```

Test:

- Heading detect được.
- Section number không bị xóa.
- Section title carry-forward sang page sau nếu hợp lý.

Điểm dừng:

```text
Chunker chưa dùng metadata này ngay, nhưng cleaner đã tạo được.
```

## Step 11 — Classify TOC, references, caption

Mục tiêu:

- Không xóa, chỉ classify.

Detect:

```text
table_of_contents
references
caption
```

Policy ban đầu:

```text
Artifact vẫn lưu đầy đủ.
Chunking sau có thể quyết định skip/reduce priority.
```

Test:

- TOC pattern được tag.
- References section được tag.
- Figure/Table/Hình/Bảng caption được tag.

Điểm dừng:

```text
Chưa thay đổi chunking behavior.
```

## Step 12 — Quality report và quality gate

Mục tiêu:

- Không cho text quá tệ đi tiếp sang chunk.

Metric:

```text
page_count
raw_char_count
cleaned_char_count
empty_pages
cleaned_to_raw_ratio
weird_char_count
removed_header_footer_count
removed_page_number_count
warning_count
quality_status
```

Status:

```text
GOOD
ACCEPTABLE
NEEDS_REVIEW
FAILED
```

Không dùng OCR status ở đây vì OCR detection đã nằm ở validation trước đó.

Test:

- Cleaned text rỗng -> FAILED.
- Cleaned text giảm bất thường -> NEEDS_REVIEW.
- Normal document -> GOOD/ACCEPTABLE.

Điểm dừng:

```text
Cleaner có thể quyết định dừng trước chunking.
```

## Step 13 — Cleaned artifacts local first

Mục tiêu:

- Trước khi upload S3, ghi artifact vào temp folder để dễ inspect.

Output local:

```text
cleaned/page_0001.clean.md
processed_doc.md
quality_report.json
manifest.json
```

Test:

- File được tạo đúng path.
- UTF-8.
- Manifest trỏ đúng page files.

Điểm dừng:

```text
Vẫn chưa upload rag-artifacts, chỉ local temp trong worker.
```

## Step 14 — Upload cleaned artifacts vào `rag-artifacts`

Mục tiêu:

- Durable artifacts cho debug/reprocess.

Object key:

```text
documents/doc_ebook_{ebookId}/versions/v{checksumPrefix}/cleaned/page_0001.clean.md
documents/doc_ebook_{ebookId}/versions/v{checksumPrefix}/processed_doc.md
documents/doc_ebook_{ebookId}/versions/v{checksumPrefix}/quality_report.json
documents/doc_ebook_{ebookId}/versions/v{checksumPrefix}/manifest.json
```

Test:

- Object storage adapter upload được text artifacts.
- Metadata có content-type hợp lý.
- Failure cleanup/log rõ.

Điểm dừng:

```text
Artifacts có trên S3-compatible storage, nhưng pipeline chunking vẫn có thể dùng memory output.
```

## Step 15A — Tích hợp cleaner mới vào `IngestionPipeline` theo hướng hiện tại

Trạng thái:

```text
Đã hoàn thành theo hướng LlamaIndex B0+B1+B2, không theo clean_text/RecursiveChunker cũ.
```

Mục tiêu đã đạt:

- Thay `clean_text(raw_document.text)` bằng `PdfCleaningTransformation`.
- Dùng `SentenceSplitter/NodeParser` thay cho `RecursiveChunker` trong path PDF chính.
- Chỉ chunk khi quality pass.

Flow hiện tại:

```text
raw_documents = parser.parse(file_path)
llama_documents = parsed_documents_to_llama_documents(raw_documents)
cleaned_documents = PdfCleaningTransformation()(llama_documents)
if cleaning_can_chunk is false:
    fail job
nodes = documents_to_sentence_nodes(cleaned_documents)
chunks = llama_nodes_to_chunks(nodes)
```

Test:

- Pipeline happy path vẫn pass.
- Quality failed thì job FAILED/NEEDS_REVIEW rõ.
- Chunk metadata vẫn có page number.

Điểm dừng:

```text
Pipeline chính persist chunks tạo từ LlamaIndex nodes.
```

## Step 15B — Chuẩn bị LlamaIndex Direction B

Trạng thái:

```text
Đã hoàn thành B0+B1+B2 và đã tích hợp vào IngestionPipeline chính.
```

Mục tiêu:

- Chuyển luồng clean/chunk sang style LlamaIndex nhưng vẫn dùng lại cleaner nội bộ.
- Mỗi page parsed trở thành một `llama_index.core.Document`.
- Cleaner chạy như một transformation trước khi NodeParser cắt chunk.

Flow mục tiêu:

```text
PDF local temp file
  -> parser.parse()
  -> ParsedPage[]
  -> LlamaIndex Document per page, raw text + metadata
  -> PdfCleaningTransformation
  -> cleaned LlamaIndex Documents
  -> SentenceSplitter/NodeParser
  -> Nodes
  -> convert Node metadata/text sang internal chunk model
```

Ý nghĩa:

```text
Document là container page-level.
Node là chunk-level.
1 Document/page có thể sinh nhiều Node/chunk.
Metadata page/book/ebook/cleaning_version phải đi từ Document xuống Node.
```

Không làm trong bước này:

```text
Không đổi sang LlamaIndex S3 Reader.
Không để LlamaIndex tự embedding/upsert Qdrant ngay.
Không bỏ validation S3 hiện tại.
```

Test:

- Parsed page tạo được LlamaIndex Document đúng metadata.
- Cleaning transformation trả về Document sạch.
- NodeParser tạo nhiều Node từ một Document dài.
- Node giữ được `page_number`, `document_id`, `cleaning_version`.

Điểm dừng:

```text
LlamaIndex mới được dùng cho Document/Transformation/NodeParser, chưa thay toàn bộ RAG pipeline.
```

## Step 16 — Chuẩn bị cho chunking tốt hơn

Mục tiêu:

- Không implement chunker mới ngay.
- Chỉ đảm bảo cleaned pages có metadata đủ.

Metadata cần:

```text
page_number
section_title
line/block types
quality warnings
cleaning_version
```

Test:

- Chunk metadata có `sectionTitle` hoặc `section_title` khi detect được.
- PageStart/pageEnd vẫn đúng.

Điểm dừng:

```text
Kết thúc phase clean. Sau đó mới sang chunking.
```

## Acceptance criteria cho toàn phase clean

Phase clean được coi là xong khi:

```text
Cleaner chạy page-wise
Unicode/whitespace sạch hơn
Page number/header/footer cơ bản được xử lý
Hyphenation/line break được xử lý an toàn
Heading/list/code/table/caption không bị phá nghiêm trọng
Quality report được tạo
Cleaned artifacts được lưu
Pipeline chỉ chunk khi quality pass
Unit tests pass
Full unit tests pass
```

Không coi là scope hoàn thành nếu:

```text
Chưa OCR mà vẫn cố xử lý PDF scan
Cleaner làm mất page number metadata
Cleaner gộp toàn document thành một string
Không có quality report
Không có cleaning version
Không debug được cleaned output
```
