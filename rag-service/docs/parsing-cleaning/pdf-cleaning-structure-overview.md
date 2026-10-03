# PDF Cleaning Structure Overview

## PDF cleaning là gì?

Trong hệ RAG của mình, cleaning không phải là đọc PDF.

Thứ tự đúng là:

```text
PDF gốc
  -> validate
  -> parse ra text/Markdown theo page
  -> clean
  -> quality check
  -> chunk
  -> embedding
  -> Qdrant
```

Vậy phần clean nhận đầu vào là:

```text
page 1 raw markdown/text
page 2 raw markdown/text
page 3 raw markdown/text
...
```

và trả ra:

```text
page 1 cleaned markdown/text
page 2 cleaned markdown/text
page 3 cleaned markdown/text
quality_report
manifest
```

## Scope hiện tại

Làm:

```text
Clean text/Markdown từ PyMuPDF4LLM
Giữ page boundary
Giữ heading/list/table/code/caption cơ bản
Tạo quality report
Tạo cleaned artifacts
Chuẩn bị data cho chunking
```

Không làm trong phase này:

```text
Docling
OCR
Vision model
Crop image/table/formula
Embedding
Qdrant upsert
```

Nếu PDF không có text layer, validation trước đó đã mark:

```text
PDF_OCR_REQUIRED
```

Cleaner không cố xử lý case đó.

## Mental model dễ hiểu

Hãy tưởng tượng PDF là sách photo thành file.

Parser làm việc:

```text
bóc chữ từ từng trang
```

Cleaner làm việc:

```text
sửa chữ bị bẩn
giữ cấu trúc quan trọng
bỏ nhiễu như page number/header/footer
không làm mất page gốc
```

Chunker làm việc:

```text
chia chữ đã sạch thành đoạn nhỏ để embedding
```

Nói ngắn:

```text
parse = lấy chữ ra
clean = làm chữ sạch và giữ cấu trúc
chunk = chia chữ sạch thành đoạn truy xuất được
```

## Luồng tổng quan

```text
RAG Worker
  |
  |-- original.pdf từ temp file
  |
  |-- PdfLoader / PyMuPDF4LLM
  |     |
  |     |-- RawDocument(page_number=1, text="...")
  |     |-- RawDocument(page_number=2, text="...")
  |
  |-- PdfCleaner
  |     |
  |     |-- UnicodeNormalizer
  |     |-- WhitespaceNormalizer
  |     |-- PageNumberRemover
  |     |-- HeaderFooterCleaner
  |     |-- HyphenationFixer
  |     |-- LineBreakFixer
  |     |-- StructurePreserver
  |     |-- QualityReporter
  |
  |-- Cleaned pages
  |
  |-- ArtifactWriter
  |     |
  |     |-- cleaned/page_0001.clean.md
  |     |-- processed_doc.md
  |     |-- quality_report.json
  |     |-- manifest.json
  |
  |-- Chunker
```

## Các thành phần chính

### 1. Parsed page

Đây là dữ liệu sau parser.

Ví dụ:

```json
{
  "page_number": 12,
  "raw_text": "3.2 Borrowing Module\nThe borrowing module allows...\nPage 12",
  "metadata": {
    "parser": "pymupdf4llm",
    "source": "original.pdf"
  }
}
```

Cleaner không cần biết PDF gốc nằm ở đâu. Nó chỉ cần biết page text và metadata.

### 2. Cleaned page

Đây là dữ liệu sau cleaner.

Ví dụ:

```json
{
  "page_number": 12,
  "cleaned_text": "3.2 Borrowing Module\n\nThe borrowing module allows...",
  "section_title": "3.2 Borrowing Module",
  "warnings": [],
  "metadata": {
    "cleaning_version": "pdf-clean-v1.0.0",
    "removed_page_number": true
  }
}
```

### 3. Quality report

Report giúp trả lời câu hỏi:

```text
Text sau clean có đủ tốt để chunk không?
Cleaner có xóa quá nhiều không?
PDF có page rỗng không?
Có warning gì cần review không?
```

Ví dụ:

```json
{
  "cleaning_version": "pdf-clean-v1.0.0",
  "page_count": 120,
  "raw_char_count": 240000,
  "cleaned_char_count": 225000,
  "empty_pages": 2,
  "quality_status": "GOOD",
  "warnings": []
}
```

### 4. Manifest

Manifest là bản đồ của artifacts.

Nó nói rõ file nào nằm ở đâu:

```json
{
  "documentId": "doc_ebook_55",
  "version": "v9f3a2a7e",
  "cleaningVersion": "pdf-clean-v1.0.0",
  "artifacts": {
    "processedMarkdown": "processed_doc.md",
    "qualityReport": "quality_report.json",
    "cleanedPagesPrefix": "cleaned/"
  }
}
```

Manifest giúp:

- debug
- reprocess
- biết parser/cleaner version
- nối các file artifacts lại với nhau

## Cleaner sẽ làm gì cụ thể?

### Unicode normalization

Sửa các ký tự PDF hay sinh lỗi:

```text
ﬁ -> fi
ﬂ -> fl
non-breaking space -> normal space
zero-width char -> remove
```

Mục tiêu:

```text
text dễ search hơn
không phá tiếng Việt/ký hiệu kỹ thuật
```

### Whitespace normalization

Làm gọn khoảng trắng:

```text
nhiều spaces -> một space
quá nhiều blank lines -> tối đa 2 blank lines
CRLF -> LF
```

Nhưng phải giữ:

```text
list
table
code block
config
```

### Page number removal

Xóa nhiễu:

```text
Page 12
12 / 200
- 12 -
Trang 12
```

Nhưng không xóa:

```text
3.2 Borrowing Module
```

### Header/footer cleaning

Nhiều sách có header/footer lặp:

```text
Library Management System
Chapter 3
Page 12 of 200
```

Cleaner sẽ:

```text
so sánh top/bottom lines giữa nhiều page
dòng nào lặp quá nhiều thì remove
ghi lại trong report
```

### Hyphenation fix

Sửa từ bị tách:

```text
genera-
tion
```

thành:

```text
generation
```

Nhưng không phá:

```text
state-of-the-art
```

### Line break fix

Sửa paragraph bị wrap:

```text
The RAG system retrieves
relevant chunks from vector
database.
```

thành:

```text
The RAG system retrieves relevant chunks from vector database.
```

Nhưng không merge list/table/code.

### Structure preservation

Cleaner phải giữ những phần có cấu trúc:

```text
heading
bullet list
numbered list
code block
table-like block
caption
```

Vì nếu clean quá tay, chunking và retrieval sẽ mất nghĩa.

## Artifact output trong `rag-artifacts`

Production không lưu `doc_assets` trong source project.

Output nên lưu ở bucket:

```text
rag-artifacts
```

Đề xuất structure:

```text
rag-artifacts/
  documents/
    doc_ebook_{ebookId}/
      versions/
        v{checksumPrefix}/
          cleaned/
            page_0001.clean.md
            page_0002.clean.md
          processed_doc.md
          quality_report.json
          manifest.json
```

Local folder chỉ dùng tạm trong worker:

```text
/tmp/rag/jobs/ingestion_job_{jobId}/
```

Sau khi upload artifacts xong thì cleanup.

## Code structure đề xuất

Không nhét toàn bộ clean logic vào `pipeline.py`.

Đề xuất:

```text
app/ingestion/cleaners/
  pdf_cleaner.py
  models.py
  quality.py
  rules/
    unicode_normalizer.py
    whitespace_normalizer.py
    page_number_remover.py
    header_footer.py
    hyphenation.py
    line_breaks.py
    structure.py

app/ingestion/artifacts/
  manifest.py
  markdown_writer.py
  artifact_writer.py
```

Vai trò:

```text
pipeline.py
  -> điều phối

pdf_cleaner.py
  -> gọi các rule clean theo thứ tự

rules/*
  -> mỗi rule nhỏ, dễ test

quality.py
  -> tạo quality report

artifact_writer.py
  -> ghi cleaned artifacts ra temp/S3
```

## Pipeline sau khi có cleaner

```text
validate PDF
  -> parse pages
  -> clean pages
  -> quality check
  -> save clean artifacts
  -> chunk cleaned pages
```

Trong code, hướng mong muốn:

```python
raw_pages = pdf_loader.load(file_path)
cleaning_result = pdf_cleaner.clean(raw_pages)

if not cleaning_result.quality_report.can_chunk:
    fail_job(...)

await artifact_writer.write_cleaning_outputs(cleaning_result)
chunks = chunker.chunk_pages(cleaning_result.pages)
```

## Quality gate

Cleaner không chỉ trả text. Cleaner còn quyết định text có đủ tốt để chunk không.

Ví dụ không cho chunk:

```text
cleaned text rỗng
quá nhiều page rỗng
cleaned_char_count quá thấp
weird_char_ratio quá cao
quality_status = FAILED
```

Nếu quality chưa đẹp nhưng vẫn dùng được:

```text
quality_status = ACCEPTABLE
```

Nếu tốt:

```text
quality_status = GOOD
```

## Tại sao chưa dùng Docling/OCR?

Docling và OCR rất hữu ích, nhưng chưa nên đưa vào phase này vì:

```text
dependency nặng hơn
runtime lâu hơn
cần nhiều config hơn
có thể làm pipeline khó debug khi nền clean chưa ổn
OCR cần confidence score và review flow riêng
```

Hướng đúng:

```text
1. Làm sạch text-based PDF thật chắc.
2. Lưu artifacts rõ ràng.
3. Chunk tốt.
4. Sau đó mới mở advanced parser/OCR nếu cần.
```

## Kết luận

Phần clean sẽ là lớp nằm giữa parser và chunker.

Nó có trách nhiệm:

```text
biến raw parsed text thành cleaned page-wise text
giữ cấu trúc quan trọng
remove nhiễu phổ biến của PDF
tạo quality report
lưu artifacts để debug/reprocess
chặn dữ liệu tệ trước khi chunking
```

Nếu làm đúng, các bước sau như chunking, embedding và retrieval sẽ ổn hơn rất nhiều.
