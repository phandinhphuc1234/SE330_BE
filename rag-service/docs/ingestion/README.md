# Ingestion Documentation

Folder này gom các tài liệu về luồng ingest tài liệu từ Library/Spring Boot vào
RAG service.

Ingestion là phần:

```text
Spring Boot upload PDF vào SeaweedFS/S3
  -> Spring Boot gọi internal RAG API
  -> RAG tạo document/job
  -> worker validate/parse/clean/chunk
  -> PostgreSQL + artifacts
  -> sau này embedding + Qdrant
```

---

## Thứ tự đọc đề xuất

### 1. Luồng ingest PDF tổng quan

```text
pdf-data-ingestion.md
```

Mô tả flow dữ liệu PDF vào hệ thống RAG.

### 2. SeaweedFS + PostgreSQL upload flow

```text
seaweedfs-postgres-upload-flow.md
```

Mô tả cách object storage và PostgreSQL phối hợp:

- file gốc ở `library-private`;
- temp/import ở `library-temp`;
- artifact RAG ở `rag-artifacts`;
- DB giữ metadata, bucket, object key, status.

---

## Folder liên quan

Contract giữa Spring Boot và RAG nằm ở:

```text
docs/integration/
```

Parsing/cleaning sau khi worker nhận job nằm ở:

```text
docs/parsing-cleaning/
```

Chunking sau cleaning nằm ở:

```text
docs/chunking/
```

---

## Theo dõi ingestion trên terminal

Khi test upload một PDF thật, mở log:

```bash
docker compose logs -f api worker
```

Nếu muốn nhìn từng service riêng:

```bash
docker compose logs -f api
docker compose logs -f worker
```

### Log tốt cần thấy

Luồng bình thường sẽ đi qua các event chính sau:

```text
internal_ingestion_request_received
internal_ingestion_document_registered
internal_ingestion_raw_artifact_registered / internal_ingestion_raw_artifact_updated
internal_ingestion_job_created
internal_ingestion_job_enqueued

ingestion_task_started
ingestion_pipeline_started
ingestion_document_loaded
ingestion_source_head_started
ingestion_source_object_validated
ingestion_source_download_started
ingestion_source_download_completed
ingestion_source_checksum_validated
ingestion_pdf_magic_bytes_validated
ingestion_pdf_structure_validated
ingestion_pdf_text_layer_validated

ingestion_parse_started
ingestion_parse_completed
ingestion_clean_chunk_started
ingestion_chunking_completed

ingestion_artifact_write_started
ingestion_artifact_uploaded
ingestion_artifact_write_completed
ingestion_chunks_persisted

ingestion_embedding_started
chunk_embedding_started
chunk_embedding_completed
ingestion_embedding_completed

ingestion_qdrant_upsert_started
qdrant_collection_ready
qdrant_upsert_started
qdrant_upsert_completed
ingestion_qdrant_upsert_completed

ingestion_pipeline_completed
ingestion_task_succeeded
```

Khi tới:

```text
ingestion_pipeline_completed status=INDEXED
```

thì ebook đã có vectors trong Qdrant và có thể chạy retrieval smoke test.

### Log lỗi thường gặp

| Event / error_code | Ý nghĩa |
|---|---|
| `internal_ingestion_rejected_invalid_bucket` | Spring Boot gửi bucket không phải `library-private`. |
| `internal_ingestion_rejected_invalid_object_key` | Object key không đúng `ebooks/{bookId}/{ebookId}/original.pdf`. |
| `SOURCE_OBJECT_NOT_FOUND` | SeaweedFS/S3 không có object tương ứng. |
| `SOURCE_SIZE_MISMATCH` | Size Spring Boot gửi khác size trên S3. |
| `PDF_CHECKSUM_MISMATCH` | File tải xuống không khớp checksum. |
| `PDF_MAGIC_BYTES_INVALID` | File giả PDF hoặc upload sai định dạng. |
| `PDF_ENCRYPTED` | PDF có mật khẩu/mã hóa. |
| `PDF_OCR_REQUIRED` | PDF scan/image-only, chưa có OCR pipeline. |
| `CHUNK_QUALITY_FAILED` | Clean/chunk tạo output không đạt quality gate. |
| `chunk_embedding_failed` | Gemini embedding lỗi/rate-limit/config. |
| `qdrant_upsert_started` có nhưng không có `qdrant_upsert_completed` | Qdrant lỗi hoặc timeout khi index. |
| `ingestion_task_retrying` | Celery sẽ retry job. |
| `ingestion_pipeline_failed` / `ingestion_task_failed` | Job đã fail, xem `error=` trong log. |

### Kiểm tra trạng thái DB/Qdrant

Sau khi upload PDF, có thể kiểm tra số chunk trong Postgres:

```bash
docker compose exec -T postgres psql -U user -d rag_db -c "select id, status, stage, error_message from ingestion_jobs order by id desc limit 5;"
docker compose exec -T postgres psql -U user -d rag_db -c "select count(*) from document_chunks;"
```

Kiểm tra Qdrant:

```bash
docker compose exec -T api python -c "from qdrant_client import QdrantClient; from app.core.config import get_settings; s=get_settings(); c=QdrantClient(url=s.qdrant_url, api_key=s.qdrant_api_key or None); print(c.get_collection(s.qdrant_collection_name).points_count)"
```
