# RAG Metadata Structure Reference

## Mục tiêu tài liệu

Tài liệu này ghi lại các cấu trúc metadata đang tồn tại trong hệ thống RAG hiện tại, đặc biệt quanh luồng:

```text
Spring Boot Library
  -> RAG internal ingestion API
  -> PostgreSQL document/artifact/job metadata
  -> SeaweedFS/S3 source validation
  -> PyMuPDF4LLM parser
  -> LlamaIndex Document adapter
  -> PdfCleaningTransformation
  -> chunk metadata
  -> future embedding/Qdrant payload
```

Điểm quan trọng:

```text
Metadata không phải text nội dung chính.
Metadata là thông tin phụ dùng để trace, filter, debug, citation, reprocess và permission.
```

Hiện tại project đã có `LlamaIndex Document adapter`, `PdfCleaningTransformation` và `SentenceSplitter/Node adapter`. Pipeline chính đã chuyển sang dùng B0+B1+B2 cho ingestion PDF. Vì vậy tài liệu này phân biệt rõ:

```text
Đang chạy trong pipeline chính
Đã tích hợp vào pipeline chính
Đang là kế hoạch bước tiếp theo
```

---

## 1. Quy tắc đặt tên metadata

### 1.1. Snake case nội bộ

Trong Python/PostgreSQL metadata nội bộ, ưu tiên dùng:

```text
snake_case
```

Ví dụ:

```json
{
  "book_id": 101,
  "ebook_id": 55,
  "page_number": 12,
  "cleaning_version": "pdf-clean-v1.0.0"
}
```

### 1.2. Camel case khi tương thích với API/Library

Một số field có camelCase vì đi theo contract với Spring Boot hoặc frontend:

```json
{
  "bookId": 101,
  "ebookId": 55,
  "documentId": "doc_ebook_55",
  "chunkIndex": 3,
  "pageStart": 12,
  "pageEnd": 12
}
```

Hiện tại chunk metadata có cả hai kiểu trong một số field để tiện tương thích:

```json
{
  "document_id": 7,
  "documentId": "doc_ebook_55",
  "chunk_index": 0,
  "chunkIndex": 0
}
```

Về lâu dài nên chuẩn hóa rõ:

```text
internal DB / worker: snake_case
API response / frontend contract: camelCase
```

---

## 2. Metadata flow tổng quan

```text
Library upload PDF vào SeaweedFS
  |
  | POST /internal/ingestions
  v
LibraryEbookIngestionRequest
  |
  | tạo/cập nhật
  v
documents.metadata
document_artifacts.metadata
ingestion_jobs.metadata
  |
  | Celery worker
  v
ObjectMetadata từ S3 HEAD
  |
  | download temp + validate
  v
ParsedDocument.metadata
  |
  | B0 adapter
  v
LlamaIndex Document.metadata
  |
  | B1 PdfCleaningTransformation
  v
Cleaned LlamaIndex Document.metadata
  |
  | B2 NodeParser - chưa tích hợp
  v
Node.metadata
  |
  | chunk persistence
  v
document_chunks.metadata
  |
  | future embedding/upsert
  v
Qdrant payload metadata
```

---

## 3. Internal ingestion API payload từ Spring Boot

Nguồn code:

```text
app/api/internal/routes_ingestions.py
class LibraryEbookIngestionRequest
```

Spring Boot gọi RAG sau khi đã upload PDF vào SeaweedFS/S3.

Shape:

```json
{
  "sourceType": "LIBRARY_EBOOK",
  "bookId": 101,
  "ebookId": 55,
  "bucket": "library-private",
  "objectKey": "ebooks/101/55/original.pdf",
  "checksumSha256": "64-char-lowercase-hex",
  "originalFilename": "clean-code.pdf",
  "contentType": "application/pdf",
  "fileSizeBytes": 5242880
}
```

Field:

| Field | Bắt buộc | Ý nghĩa |
|---|---:|---|
| `sourceType` | Có | Hiện chỉ nhận `"LIBRARY_EBOOK"`. |
| `bookId` | Có | ID sách bên Library. |
| `ebookId` | Có | ID ebook/file bên Library. |
| `bucket` | Có | Phải là bucket cấu hình `library-private`. |
| `objectKey` | Có | Phải đúng `ebooks/{bookId}/{ebookId}/original.pdf`. |
| `checksumSha256` | Có | SHA-256 của file PDF gốc, normalize lowercase. |
| `originalFilename` | Không | Tên file gốc để trace/debug. |
| `contentType` | Không | Thường là `application/pdf`. |
| `fileSizeBytes` | Không | Size Spring Boot biết khi upload. |

Validation quan trọng:

```text
bucket == LIBRARY_EBOOK_BUCKET
objectKey == ebooks/{bookId}/{ebookId}/original.pdf
checksumSha256 phải là 64 hex chars
```

Lý do strict:

```text
RAG không tự ý index object ngoài namespace ebook của Library.
```

---

## 4. Internal ingestion API response

Nguồn code:

```text
app/api/internal/routes_ingestions.py
class IngestionAcceptedResponse
class InternalIngestionStatusResponse
```

Khi nhận job:

```json
{
  "documentId": "doc_ebook_55",
  "ingestionJobId": 9,
  "status": "QUEUED"
}
```

Khi poll status:

```json
{
  "documentId": "doc_ebook_55",
  "ingestionJobId": 9,
  "status": "PROCESSING",
  "stage": "parsing_pdf",
  "errorCode": null,
  "errorMessage": null
}
```

Field:

| Field | Ý nghĩa |
|---|---|
| `documentId` | External RAG document ID, hiện là `doc_ebook_{ebookId}`. |
| `ingestionJobId` | ID job trong PostgreSQL RAG. |
| `status` | Trạng thái job: `QUEUED`, `PROCESSING`, `PARSED`, `CHUNKED`, `FAILED`, ... |
| `stage` | Stage nhỏ hơn của job, ví dụ `loading_document`, `parsing_pdf`. |
| `errorCode` | Hiện trả `"INGESTION_FAILED"` nếu job failed. |
| `errorMessage` | Message lỗi đã persist trong job. |

---

## 5. PostgreSQL `documents.metadata`

Nguồn code:

```text
app/documents/models.py
class Document.metadata_

app/api/internal/routes_ingestions.py
create_library_ebook_ingestion()

app/ingestion/pipeline.py
IngestionPipeline.run()
```

`documents` là record đại diện cho một document RAG độc lập với user cuối.

Các cột định danh chính không nằm trong JSON metadata:

| Column | Ví dụ | Ý nghĩa |
|---|---|---|
| `id` | `7` | ID nội bộ RAG DB. |
| `external_document_id` | `doc_ebook_55` | ID ổn định để Library biết document nào. |
| `source_type` | `LIBRARY_EBOOK` | Nguồn document. |
| `source_id` | `ebook:55` | ID nguồn theo hệ Library. |
| `book_id` | `101` | ID sách bên Library. |
| `ebook_id` | `55` | ID ebook/file bên Library. |
| `filename` | `clean-code.pdf` | Tên file gốc. |
| `storage_path` | `ebooks/101/55/original.pdf` | Object key/source path. |

Metadata khi API nhận request:

```json
{
  "storage_backend": "s3",
  "ingestion_status": "QUEUED",
  "source_type": "LIBRARY_EBOOK"
}
```

Metadata sau khi pipeline chunk xong hiện tại:

```json
{
  "storage_backend": "s3",
  "ingestion_status": "CHUNKED",
  "ingestion_stage": "chunks_persisted",
  "source_type": "pdf",
  "page_count": 120,
  "text_page_count": 118,
  "chunk_count": 450,
  "chunker": "recursive",
  "vector_indexing_status": "pending_embedding_provider_and_vector_store"
}
```

Lưu ý quan trọng:

```text
source_type trong Document column là LIBRARY_EBOOK.
source_type trong metadata sau pipeline hiện đang set "pdf".
```

Điểm này có thể gây hơi rối. Nên cân nhắc tách:

```json
{
  "source_system": "LIBRARY",
  "source_type": "LIBRARY_EBOOK",
  "content_type": "pdf"
}
```

hoặc giữ column `source_type=LIBRARY_EBOOK`, metadata dùng `content_type=pdf`.

---

## 6. PostgreSQL `document_artifacts`

Nguồn code:

```text
app/documents/models.py
class DocumentArtifact

app/documents/repository.py
create_document_artifact()
update_document_artifact()

app/api/internal/routes_ingestions.py
```

Artifact hiện tại quan trọng nhất:

```text
artifact_type = RAW_ORIGINAL
```

Nó trỏ tới file PDF gốc trong `library-private`.

Columns:

| Column | Ví dụ | Ý nghĩa |
|---|---|---|
| `document_id` | `7` | FK tới `documents`. |
| `document_version_id` | `null` | Chưa dùng version model đầy đủ. |
| `artifact_type` | `RAW_ORIGINAL` | Loại artifact. |
| `bucket` | `library-private` | Bucket chứa object. |
| `object_key` | `ebooks/101/55/original.pdf` | Key của PDF gốc. |
| `content_type` | `application/pdf` | MIME/content type. |
| `size_bytes` | `5242880` | Size PDF nếu Spring Boot gửi. |
| `checksum_sha256` | `...` | Checksum PDF gốc. |
| `metadata` | `{...}` | Metadata phụ. |

`document_artifacts.metadata` hiện có:

```json
{
  "original_filename": "clean-code.pdf"
}
```

Tương lai khi lưu artifacts của RAG vào bucket `rag-artifacts`, có thể có:

```text
PARSED_PAGES
CLEANED_PAGES
PROCESSED_DOC
QUALITY_REPORT
CHUNKS_JSONL
FAILED_PARSE_LOG
```

Nhưng hiện tại pipeline chính chưa ghi đầy đủ các artifact này.

---

## 7. Object storage metadata

Nguồn code:

```text
app/documents/object_storage.py
ObjectMetadata
StoredObject
S3CompatibleStorageAdapter
```

### 7.1. `ObjectMetadata` từ HEAD object

Được tạo bởi:

```text
storage.head_object(object_key, bucket=source_bucket)
```

Shape:

```json
{
  "bucket": "library-private",
  "object_key": "ebooks/101/55/original.pdf",
  "content_length": 5242880,
  "content_type": "application/pdf",
  "metadata": {
    "sha256": "optional-s3-user-metadata"
  }
}
```

Field:

| Field | Ý nghĩa |
|---|---|
| `bucket` | Bucket thực sự đã HEAD. |
| `object_key` | Key đã HEAD. |
| `content_length` | Size từ S3 HEAD. |
| `content_type` | ContentType từ object metadata. |
| `metadata` | User metadata của object trên S3/SeaweedFS. |

Pipeline dùng `content_length` để so với `DocumentArtifact.size_bytes`.

### 7.2. `StoredObject` khi RAG upload artifact

Được tạo bởi:

```text
storage.upload_fileobj(...)
```

Shape:

```json
{
  "bucket": "rag-artifacts",
  "object_key": "documents/doc_ebook_55/versions/vabc123/cleaned/processed_doc.md",
  "content_type": "text/markdown",
  "size_bytes": 123456,
  "sha256": "64-char-hex"
}
```

Khi upload artifact, adapter cũng ghi S3 user metadata:

```json
{
  "sha256": "64-char-hex",
  "size_bytes": "123456"
}
```

Lưu ý:

```text
Source PDF thuộc library-private.
RAG-derived artifacts thuộc rag-artifacts.
library-temp dành cho batch/import tạm bên Library.
```

---

## 8. PostgreSQL `ingestion_jobs.metadata`

Nguồn code:

```text
app/ingestion/models.py
class IngestionJob.metadata_

app/ingestion/repository.py
create_ingestion_job()
mark_job_status()

app/api/internal/routes_ingestions.py
app/ingestion/pipeline.py
```

Metadata khi tạo job:

```json
{
  "source_type": "LIBRARY_EBOOK",
  "source_id": "ebook:55",
  "book_id": 101,
  "ebook_id": 55,
  "bucket": "library-private",
  "object_key": "ebooks/101/55/original.pdf",
  "checksum_sha256": "64-char-hex"
}
```

Metadata khi parse xong:

```json
{
  "page_count": 120,
  "text_page_count": 118
}
```

Metadata khi chunk xong:

```json
{
  "chunk_count": 450
}
```

Metadata khi hoàn tất phase chunk hiện tại:

```json
{
  "page_count": 120,
  "text_page_count": 118,
  "chunk_count": 450,
  "vector_indexing_status": "pending_embedding_provider_and_vector_store"
}
```

Job columns quan trọng:

| Column | Ý nghĩa |
|---|---|
| `status` | Trạng thái tổng thể: `QUEUED`, `PROCESSING`, `PARSED`, `CHUNKED`, `FAILED`. |
| `stage` | Bước cụ thể: `queued`, `loading_document`, `parsing_pdf`, `cleaning_text`, `persisting_chunks`, `chunks_persisted`, `failed`. |
| `task_id` | Celery task id. |
| `attempts` | Số lần worker thử xử lý. |
| `error_message` | Lỗi cuối cùng nếu failed. |
| `completed_at` | Thời điểm kết thúc nếu completed hoặc failed. |

---

## 9. Parser metadata: `ParsedDocument.metadata`

Nguồn code:

```text
app/ingestion/parsers/base.py
class ParsedDocument

app/ingestion/parsers/pdf/pymupdf4llm_parser.py
class PyMuPDF4LLMParser
```

`ParsedDocument`:

```python
ParsedDocument(
    text: str,
    metadata: dict
)
```

Với PDF, parser hiện trả về một `ParsedDocument` cho mỗi page.

Shape phổ biến:

```json
{
  "source": "C:/temp/rag-document-7-xxx/original.pdf",
  "parser": "pymupdf4llm",
  "page_number": 1,
  "title": "Optional PDF title",
  "page_count": 120,
  "toc_items": [],
  "table_count": 2,
  "image_count": 3,
  "graphics_count": 1
}
```

Field:

| Field | Bắt buộc | Ý nghĩa |
|---|---:|---|
| `source` | Có | Local temp path của file parser đang đọc. |
| `parser` | Có | Tên parser, hiện là `pymupdf4llm`. |
| `page_number` | Có với PDF page chunks | Số trang bắt đầu từ 1. |
| `title` | Không | Title từ PDF metadata nếu PyMuPDF4LLM trả. |
| `page_count` | Không | Tổng số trang nếu parser trả. |
| `toc_items` | Không | Mục lục parser phát hiện. |
| `table_count` | Không | Số bảng parser thấy trên page. |
| `image_count` | Không | Số ảnh parser thấy trên page. |
| `graphics_count` | Không | Số graphic parser thấy trên page. |

Lưu ý:

```text
Parser metadata là page-level.
Không nên lưu local temp path lâu dài như artifact/public metadata.
```

Nếu cần lưu lâu dài, nên dùng:

```text
bucket + object_key + artifact key
```

thay vì local temp path.

---

## 10. LlamaIndex raw `Document.metadata` sau B0 adapter

Nguồn code:

```text
app/ingestion/llama_index/document_adapter.py
parsed_document_to_llama_document()
parsed_documents_to_llama_documents()
```

B0 adapter chuyển:

```text
ParsedDocument -> llama_index.core.Document
```

Shape:

```json
{
  "document_id": 7,
  "book_id": 101,
  "ebook_id": 55,
  "source": "C:/temp/rag-document-7-xxx/original.pdf",
  "parser": "pymupdf4llm",
  "source_parser": "pymupdf4llm",
  "page_number": 1,
  "table_count": 2,
  "image_count": 3,
  "ingestion_adapter": "llama_index_document_adapter"
}
```

Merge rule:

```text
metadata = base_metadata + parsed_document.metadata
```

Nếu cùng key bị trùng:

```text
parsed_document.metadata thắng
```

Lý do:

```text
Parser metadata cụ thể theo page hơn base metadata.
```

Field adapter thêm:

| Field | Ý nghĩa |
|---|---|
| `page_number` | Nếu parser thiếu, adapter fallback theo index page trong list. |
| `source_parser` | Copy từ `parser` nếu có. |
| `ingestion_adapter` | Luôn là `llama_index_document_adapter`. |

Trạng thái tích hợp:

```text
Đã implement và test.
Chưa thay thế pipeline chính.
```

---

## 11. Cleaner output: `CleanedPage.metadata`

Nguồn code:

```text
app/ingestion/cleaners/models.py
class CleanedPage

app/ingestion/cleaners/pdf_cleaner.py
class PdfCleaner
```

`CleanedPage`:

```python
CleanedPage(
    page_number: int,
    cleaned_text: str,
    section_title: str | None,
    warnings: list[str],
    metadata: dict,
)
```

Cleaner metadata được tạo bằng:

```text
parsed metadata
  + cleaning metadata
  + line/block metadata
  + header/footer metadata
```

Shape đầy đủ hiện tại:

```json
{
  "source": "C:/temp/rag-document-7-xxx/original.pdf",
  "parser": "pymupdf4llm",
  "page_number": 1,

  "cleaning_stage": "contract",
  "cleaning_version": "pdf-clean-v1.0.0",
  "unicode_normalized": true,
  "spaces_collapsed": true,
  "max_blank_lines": 2,

  "page_number_removal_enabled": true,
  "page_number_scan_lines": 2,
  "removed_page_number_count": 1,
  "removed_page_number_lines": ["Page 1"],

  "hyphenation_fix_enabled": true,
  "fixed_hyphenation_count": 2,

  "line_break_fix_enabled": true,
  "fixed_line_break_count": 5,

  "header_footer_detection_enabled": true,
  "header_footer_top_lines": 3,
  "header_footer_bottom_lines": 3,
  "header_footer_min_repeat_ratio": 0.4,
  "header_footer_min_repeat_count": 3,

  "header_footer_removal_enabled": false,
  "repeated_header_footer_candidate_count": 2,
  "repeated_header_footer_candidate_lines": ["Library System", "Footer Text"],
  "repeated_header_footer_candidates": [
    {
      "line": "Library System",
      "position": "top",
      "occurrence_count": 12
    },
    {
      "line": "Footer Text",
      "position": "bottom",
      "occurrence_count": 12
    }
  ],
  "removed_header_footer_count": 0,
  "removed_header_footer_lines": [],

  "line_count": 42,
  "line_type_counts": {
    "blank": 4,
    "heading": 2,
    "list": 3,
    "table": 0,
    "code": 0,
    "caption": 1,
    "paragraph": 32
  },
  "has_structural_blocks": true
}
```

### 11.1. Cleaning version

| Field | Ý nghĩa |
|---|---|
| `cleaning_version` | Version rule clean, mặc định `pdf-clean-v1.0.0`. |

Dùng để:

```text
biết document/chunk được clean bằng rule nào
reprocess khi đổi rule
debug tại sao output cũ khác output mới
```

### 11.2. Page number removal metadata

| Field | Ý nghĩa |
|---|---|
| `page_number_removal_enabled` | Có bật rule xóa page number không. |
| `page_number_scan_lines` | Số dòng đầu/cuối page được scan. |
| `removed_page_number_count` | Số dòng page number đã xóa. |
| `removed_page_number_lines` | Dòng cụ thể đã xóa. |

### 11.3. Hyphenation metadata

| Field | Ý nghĩa |
|---|---|
| `hyphenation_fix_enabled` | Có bật sửa từ bị ngắt dòng bằng `-` không. |
| `fixed_hyphenation_count` | Số lần sửa. |

### 11.4. Paragraph line-break metadata

| Field | Ý nghĩa |
|---|---|
| `line_break_fix_enabled` | Có bật merge line wrap paragraph không. |
| `fixed_line_break_count` | Số lần merge line break. |

### 11.5. Header/footer metadata

| Field | Ý nghĩa |
|---|---|
| `header_footer_detection_enabled` | Có bật detect repeated header/footer không. |
| `header_footer_top_lines` | Số dòng đầu page dùng làm candidate. |
| `header_footer_bottom_lines` | Số dòng cuối page dùng làm candidate. |
| `header_footer_min_repeat_ratio` | Tỷ lệ page tối thiểu để xem là lặp. |
| `header_footer_min_repeat_count` | Count thực tế được tính từ ratio/page count. |
| `header_footer_removal_enabled` | Có thật sự xóa candidate không. Mặc định false. |
| `repeated_header_footer_candidate_count` | Số candidate detect được. |
| `repeated_header_footer_candidate_lines` | Chỉ danh sách text line candidate. |
| `repeated_header_footer_candidates` | Candidate đầy đủ: line, position, occurrence_count. |
| `removed_header_footer_count` | Số line đã xóa thật. |
| `removed_header_footer_lines` | Line đã xóa thật. |

Lưu ý:

```text
9A detect/report.
9B remove nhưng chỉ khi PDF_CLEAN_HEADER_FOOTER_REMOVAL_ENABLED=true.
```

### 11.6. Line structure metadata

| Field | Ý nghĩa |
|---|---|
| `line_count` | Tổng số line sau clean. |
| `line_type_counts` | Count từng loại line. |
| `has_structural_blocks` | Có heading/list/table/code/caption hay không. |

`line_type_counts` hiện có key:

```json
{
  "blank": 0,
  "heading": 0,
  "list": 0,
  "table": 0,
  "code": 0,
  "caption": 0,
  "paragraph": 0
}
```

---

## 12. Cleaner `QualityReport`

Nguồn code:

```text
app/ingestion/cleaners/models.py
class QualityReport
```

Shape:

```json
{
  "page_count": 120,
  "cleaning_version": "pdf-clean-v1.0.0",
  "raw_char_count": 850000,
  "cleaned_char_count": 790000,
  "empty_pages": 2,
  "text_pages_ratio": 0.9833,
  "quality_status": "ACCEPTABLE",
  "warnings": ["page_12:EMPTY_PAGE_TEXT"]
}
```

Computed property:

```text
can_chunk = quality_status in {"GOOD", "ACCEPTABLE"}
```

Quality status hiện tại:

| Status | Ý nghĩa |
|---|---|
| `GOOD` | Có text, không có empty page. |
| `ACCEPTABLE` | Có text nhưng có một số page rỗng. |
| `FAILED` | Không có page hoặc không có text sau clean. |

Chưa có:

```text
NEEDS_REVIEW
cleaned_to_raw_ratio gate mạnh
weird char count
quality artifact upload
```

Những phần này nằm ở step clean sau.

---

## 13. LlamaIndex cleaned `Document.metadata` sau B1 transformation

Nguồn code:

```text
app/ingestion/llama_index/pdf_cleaning_transformation.py
class PdfCleaningTransformation
```

B1 transformation nhận:

```text
Sequence[BaseNode]
```

Trong thực tế B1 đang kỳ vọng input là page-level `Document` từ B0.

Output:

```text
Document đã clean text
metadata = CleanedPage.metadata + quality metadata + transformation marker
```

Shape:

```json
{
  "document_id": 7,
  "book_id": 101,
  "ebook_id": 55,
  "source": "C:/temp/rag-document-7-xxx/original.pdf",
  "parser": "pymupdf4llm",
  "source_parser": "pymupdf4llm",
  "page_number": 1,
  "ingestion_adapter": "llama_index_document_adapter",

  "cleaning_stage": "contract",
  "cleaning_version": "pdf-clean-v1.0.0",
  "unicode_normalized": true,
  "spaces_collapsed": true,

  "cleaning_quality_status": "GOOD",
  "cleaning_can_chunk": true,
  "cleaning_warnings": [],
  "cleaning_quality_warnings": [],
  "llama_index_transformation": "PdfCleaningTransformation"
}
```

Field B1 thêm:

| Field | Ý nghĩa |
|---|---|
| `cleaning_quality_status` | Copy từ `QualityReport.quality_status`. |
| `cleaning_can_chunk` | Copy từ `QualityReport.can_chunk`. |
| `cleaning_warnings` | Warning riêng của page. |
| `cleaning_quality_warnings` | Warning tổng từ quality report. |
| `llama_index_transformation` | Tên transformation đã chạy. |

ID behavior:

```text
Output Document giữ id_ của input Document.
```

Trạng thái tích hợp:

```text
Đã implement và test.
Chưa thay pipeline chính.
```

---

## 14. LlamaIndex Node metadata sau B2

Trạng thái:

```text
Đã implement B2 proof.
Chưa thay pipeline chính.
```

B2 dự kiến:

```text
Cleaned LlamaIndex Document
  -> SentenceSplitter / NodeParser
  -> TextNode[]
```

Metadata kỳ vọng mỗi Node nên giữ:

```json
{
  "document_id": 7,
  "documentId": "doc_ebook_55",
  "book_id": 101,
  "bookId": 101,
  "ebook_id": 55,
  "ebookId": 55,
  "page_number": 12,
  "pageStart": 12,
  "pageEnd": 12,
  "source_type": "LIBRARY_EBOOK",
  "source_parser": "pymupdf4llm",
  "cleaning_version": "pdf-clean-v1.0.0",
  "cleaning_quality_status": "GOOD",
  "chunker": "llamaindex_sentence_splitter",
  "node_id": "llamaindex-node-id",
  "chunk_index": 0,
  "chunk_hash": "sha256-of-node-text"
}
```

Nguồn code:

```text
app/ingestion/llama_index/node_adapter.py
```

Điểm quan trọng trong implementation hiện tại:

```text
Cleaner metadata khá dài.
SentenceSplitter của LlamaIndex có thể tính metadata vào chunk size.
Vì vậy B2 split bằng Document tạm chỉ chứa text + id,
sau đó copy metadata thật xuống Node.
```

Lý do nên giữ các field này:

| Field | Dùng để |
|---|---|
| `document_id` | Join về PostgreSQL document. |
| `documentId` | Trả về cho Library/frontend nếu cần. |
| `bookId`, `ebookId` | Filter theo sách/ebook. |
| `pageStart`, `pageEnd` | Citation. |
| `cleaning_version` | Reprocess/debug. |
| `chunk_hash` | Idempotency/dedup/re-upsert vector. |
| `chunker` | Biết chunk được tạo bởi splitter nào. |

---

## 15. Chunk metadata hiện tại trong pipeline chính

Nguồn code:

```text
app/ingestion/pipeline.py
IngestionPipeline._build_chunks()

app/ingestion/chunkers/recursive_chunker.py
app/ingestion/chunkers/section_chunker.py
```

Quan trọng:

```text
Pipeline chính đã dùng PdfCleaningTransformation + LlamaIndex SentenceSplitter/Node adapter.
clean_text() + RecursiveChunker là đường cũ/tiện ích, không còn là path chính cho PDF ingestion.
```

Chunk metadata hiện được tạo bằng:

```text
page_chunk.metadata
  + pipeline document metadata
  + chunk identity metadata
  + embedding status metadata
```

Shape:

```json
{
  "source": "C:/temp/rag-document-7-xxx/original.pdf",
  "parser": "pymupdf4llm",
  "page_number": 12,

  "document_id": 7,
  "documentId": "doc_ebook_55",
  "sourceType": "LIBRARY_EBOOK",
  "bookId": 101,
  "ebookId": 55,

  "chunk_index": 0,
  "chunkIndex": 0,
  "pageStart": 12,
  "pageEnd": 12,
  "chunk_hash": "64-char-sha256",
  "vector_id": "doc-7-chunk-0-a1b2c3d4e5f6",

  "source_type": "pdf",
  "chunker": "llamaindex_sentence_splitter",
  "embedding_status": "pending"
}
```

Field:

| Field | Ý nghĩa |
|---|---|
| `document_id` | RAG DB document primary key. |
| `documentId` | External document id. |
| `sourceType` | Document source type, ví dụ `LIBRARY_EBOOK`. |
| `bookId` | Library book id. |
| `ebookId` | Library ebook id. |
| `chunk_index` / `chunkIndex` | Index chunk trong document. |
| `pageStart` / `pageEnd` | Page citation. Hiện same page vì chunk theo page. |
| `chunk_hash` | SHA-256 của chunk text. |
| `vector_id` | Deterministic vector id cho upsert sau này. |
| `source_type` | Hiện đang là `"pdf"`. |
| `chunker` | Hiện `"llamaindex_sentence_splitter"`. |
| `embedding_status` | Hiện `"pending"`. |

`vector_id` hiện sinh theo:

```text
doc-{document.id}-chunk-{chunk_index}-{chunk_hash[:12]}
```

Ví dụ:

```text
doc-7-chunk-0-a1b2c3d4e5f6
```

### 15.1. RecursiveChunker

Nguồn:

```text
app/ingestion/chunkers/recursive_chunker.py
```

Behavior:

```text
Input metadata được copy nguyên sang mọi chunk con.
```

### 15.2. SectionChunker

Nguồn:

```text
app/ingestion/chunkers/section_chunker.py
```

Nếu detect section, nó thêm:

```json
{
  "section_header": "Chương 1"
}
```

Hiện pipeline chính đang dùng LlamaIndex `SentenceSplitter`, không dùng `RecursiveChunker` hoặc `SectionChunker` cho PDF ingestion chính.

---

## 16. PostgreSQL `document_chunks.metadata`

Nguồn code:

```text
app/documents/models.py
class DocumentChunk.metadata_

app/documents/repository.py
create_document_chunk()
```

`document_chunks` lưu:

| Column | Ý nghĩa |
|---|---|
| `document_id` | FK tới document. |
| `chunk_index` | Thứ tự chunk. |
| `content` | Text chunk. |
| `metadata` | JSON metadata của chunk. |
| `vector_id` | ID vector tương ứng trong Qdrant sau này. |

Hiện tại `metadata` chính là shape ở mục 15.

Ví dụ:

```json
{
  "document_id": 7,
  "documentId": "doc_ebook_55",
  "bookId": 101,
  "ebookId": 55,
  "pageStart": 12,
  "pageEnd": 12,
  "chunk_hash": "64-char-sha256",
  "vector_id": "doc-7-chunk-0-a1b2c3d4e5f6",
  "embedding_status": "pending"
}
```

---

## 17. Qdrant payload metadata tương lai

Trạng thái:

```text
QdrantVectorStore upsert method đã có.
Pipeline chính đã gọi embedding + Qdrant upsert.
document_chunks.metadata được update thêm qdrant_point_id/qdrant_point_key sau khi upsert thành công.
```

Khi implement embedding/Qdrant, payload nên copy subset cần thiết từ `document_chunks.metadata`:

```json
{
  "qdrant_point_id": "deterministic-uuid",
  "qdrant_point_key": "doc-7-chunk-0-a1b2:gemini-embedding-2-3072-v1",
  "vector_id": "doc-7-chunk-0-a1b2",
  "active": true,
  "metadata_schema_version": "rag-vector-payload-v1",
  "content": "chunk text...",
  "document_id": 7,
  "documentId": "doc_ebook_55",
  "bookId": 101,
  "ebookId": 55,
  "sourceType": "LIBRARY_EBOOK",
  "pageStart": 12,
  "pageEnd": 12,
  "chunkIndex": 0,
  "chunk_hash": "64-char-sha256",
  "cleaning_version": "pdf-clean-v1.0.0",
  "chunker": "llamaindex_sentence_splitter",
  "embedding_model": "gemini-embedding-2",
  "embedding_version": "gemini-embedding-2-3072-v1"
}
```

Không nên đưa tất cả metadata debug vào Qdrant payload nếu không cần filter/retrieve, vì payload quá lớn làm index/filter nặng hơn.

Nên chia:

```text
Qdrant payload cần cho retrieval/filter/citation
PostgreSQL metadata giữ đầy đủ audit/debug
rag-artifacts giữ file report chi tiết
```

---

## 18. Artifact key metadata đề xuất cho `rag-artifacts`

Trạng thái:

```text
Đã có bucket rag-artifacts.
Pipeline chính chưa upload đầy đủ artifacts clean/chunk.
```

Key đề xuất:

```text
documents/
  doc_ebook_{ebookId}/
    versions/
      v{checksumPrefix}/
        parsed/
          page_0001.raw.md
        cleaned/
          page_0001.clean.md
          processed_doc.md
        reports/
          quality_report.json
          manifest.json
        chunks/
          chunks.jsonl
        logs/
          failed_parse.log
```

Manifest metadata đề xuất:

```json
{
  "document_id": 7,
  "external_document_id": "doc_ebook_55",
  "book_id": 101,
  "ebook_id": 55,
  "source": {
    "bucket": "library-private",
    "object_key": "ebooks/101/55/original.pdf",
    "checksum_sha256": "64-char-hex"
  },
  "parser": {
    "name": "pymupdf4llm",
    "page_count": 120
  },
  "cleaning": {
    "version": "pdf-clean-v1.0.0",
    "quality_status": "GOOD"
  },
  "artifacts": {
    "processed_doc": "cleaned/processed_doc.md",
    "quality_report": "reports/quality_report.json",
    "chunks": "chunks/chunks.jsonl"
  }
}
```

---

## 19. Error/validation metadata và error codes

Không phải tất cả lỗi đều lưu vào metadata, nhưng các error code này ảnh hưởng status/debug.

Nguồn code:

```text
app/ingestion/pipeline.py
app/api/internal/routes_ingestions.py
app/documents/object_storage.py
```

Một số error code hiện có:

| Error code | Ý nghĩa |
|---|---|
| `INVALID_SOURCE_BUCKET` | Payload bucket không phải bucket ebook private. |
| `INVALID_LIBRARY_EBOOK_OBJECT_KEY` | Object key không đúng convention. |
| `SOURCE_OBJECT_NOT_FOUND` | S3 HEAD không thấy object. |
| `SOURCE_SIZE_MISMATCH` | Size object khác metadata đã đăng ký. |
| `PDF_CHECKSUM_MISMATCH` | Checksum file download khác checksum Spring Boot gửi. |
| `PDF_MAGIC_BYTES_INVALID` | File `.pdf` nhưng không có header PDF. |
| `PDF_STRUCTURE_INVALID` | pypdf không parse được cấu trúc PDF. |
| `PDF_ENCRYPTED` | PDF có password/encrypted. |
| `PDF_PAGE_COUNT_INVALID` | PDF không có page. |
| `PDF_PAGE_LIMIT_EXCEEDED` | PDF vượt giới hạn page. |
| `PDF_OCR_REQUIRED` | PDF có quá ít text layer, cần OCR. |
| `PDF_TEXT_NOT_FOUND` | Parser không tạo text page nào. |
| `PDF_CHUNKS_NOT_FOUND` | Clean/chunk không tạo chunk nào. |
| `MAX_CHUNKS_EXCEEDED` | Document vượt max chunk cấu hình. |
| `INGESTION_ENQUEUE_FAILED` | API không enqueue được Celery task. |

Khi job fail:

```text
ingestion_jobs.status = FAILED
ingestion_jobs.stage = failed
ingestion_jobs.error_message = str(exc)
completed_at = now
```

---

## 20. Current state: cái nào đang chạy, cái nào mới chuẩn bị

### Đang chạy trong pipeline chính

```text
Spring Boot payload metadata
documents.metadata
document_artifacts RAW_ORIGINAL metadata
ingestion_jobs.metadata
ObjectMetadata HEAD validation
ParsedDocument.metadata từ PyMuPDF4LLM
B0 LlamaIndex Document adapter
B1 PdfCleaningTransformation
B2 LlamaIndex SentenceSplitter/Node adapter
document_chunks.metadata
```

### Đã implement và đã thay pipeline chính

```text
PdfCleaner page-wise metadata
QualityReport
LlamaIndex Document adapter B0
PdfCleaningTransformation B1
LlamaIndex SentenceSplitter/Node adapter B2
```

### Chưa implement

```text
Cleaned artifact upload vào rag-artifacts
Embedding provider
Qdrant upsert payload
OCR path
Docling path
```

---

## 21. Metadata cần giữ xuyên suốt từ đầu đến cuối

Các field này nên sống từ source đến chunk/vector:

```json
{
  "document_id": 7,
  "external_document_id": "doc_ebook_55",
  "source_type": "LIBRARY_EBOOK",
  "source_id": "ebook:55",
  "book_id": 101,
  "ebook_id": 55,
  "bucket": "library-private",
  "object_key": "ebooks/101/55/original.pdf",
  "checksum_sha256": "64-char-hex",
  "page_number": 12,
  "parser": "pymupdf4llm",
  "cleaning_version": "pdf-clean-v1.0.0",
  "chunk_index": 0,
  "chunk_hash": "64-char-sha256",
  "vector_id": "doc-7-chunk-0-a1b2c3d4e5f6"
}
```

Nếu chỉ được chọn metadata tối thiểu cho retrieval/citation:

```json
{
  "documentId": "doc_ebook_55",
  "bookId": 101,
  "ebookId": 55,
  "pageStart": 12,
  "pageEnd": 12,
  "chunkIndex": 0
}
```

Nếu chọn metadata tối thiểu cho debug/reprocess:

```json
{
  "bucket": "library-private",
  "object_key": "ebooks/101/55/original.pdf",
  "checksum_sha256": "64-char-hex",
  "parser": "pymupdf4llm",
  "cleaning_version": "pdf-clean-v1.0.0",
  "chunker": "llamaindex_sentence_splitter"
}
```

---

## 22. Gợi ý chỉnh sau này để metadata sạch hơn

### 22.1. Chuẩn hóa key casing

Hiện chunk metadata có cả:

```text
bookId và book_id style
chunkIndex và chunk_index style
sourceType và source_type style
```

Gợi ý:

```text
Trong DB internal: snake_case
Khi trả API cho Library/frontend: convert sang camelCase
```

### 22.2. Tách source type và content type

Hiện có thể lẫn:

```text
source_type = LIBRARY_EBOOK
source_type = pdf
```

Gợi ý:

```json
{
  "source_system": "LIBRARY",
  "source_type": "LIBRARY_EBOOK",
  "content_type": "application/pdf",
  "document_kind": "ebook_pdf"
}
```

### 22.3. Không đưa debug metadata quá lớn xuống Qdrant

Các field như:

```text
repeated_header_footer_candidates
removed_header_footer_lines
line_type_counts
cleaning_quality_warnings
```

rất hữu ích cho PostgreSQL/artifact debug, nhưng không nhất thiết phải nằm trong Qdrant payload.

### 22.4. Thêm metadata version riêng

Ngoài `cleaning_version`, nên có:

```json
{
  "metadata_schema_version": "rag-metadata-v1"
}
```

Dùng để migrate metadata sau này.

### 22.5. Thêm document version rõ ràng

Hiện có `document_version_id` nhưng chưa dùng đầy đủ.

Khi ebook upload file mới cùng `ebookId`, nên có version model rõ:

```json
{
  "document_version_id": 3,
  "source_checksum_sha256": "64-char-hex",
  "artifact_version_key": "vabc123"
}
```

---

## 23. Bảng tóm tắt nhanh

| Layer | Object | Metadata shape chính | Trạng thái |
|---|---|---|---|
| API | `LibraryEbookIngestionRequest` | `sourceType`, `bookId`, `ebookId`, `bucket`, `objectKey`, `checksumSha256` | Đang chạy |
| DB | `documents.metadata` | `storage_backend`, `ingestion_status`, counts, `vector_indexing_status` | Đang chạy |
| DB | `document_artifacts.metadata` | `original_filename` | Đang chạy |
| DB | `ingestion_jobs.metadata` | source ids, bucket/key/checksum, page/chunk counts | Đang chạy |
| Storage | `ObjectMetadata` | bucket/key/content_length/content_type/S3 user metadata | Đang chạy |
| Parser | `ParsedDocument.metadata` | `source`, `parser`, `page_number`, table/image/graphics counts | Đang chạy |
| Cleaner | `CleanedPage.metadata` | cleaning rule/version/report metadata | Implement, chưa tích hợp chính |
| Cleaner | `QualityReport` | quality status/counts/warnings | Implement, chưa tích hợp chính |
| LlamaIndex | Raw `Document.metadata` | base + parser metadata + adapter marker | B0 done |
| LlamaIndex | Cleaned `Document.metadata` | cleaner metadata + quality fields + transformation marker | B1 done |
| LlamaIndex | `Node.metadata` | page/document/chunk metadata | B2 proof done |
| Chunk | `document_chunks.metadata` | document/book/page/chunk/vector metadata | Đang chạy theo LlamaIndex B0-B2 pipeline |
| Vector DB | Qdrant payload | retrieval/filter/citation subset | Store method và pipeline wiring đã làm |
