# Integration Documentation

Folder này gom các tài liệu liên quan đến việc RAG service tích hợp với hệ thống
Library/Spring Boot.

Product flow mục tiêu là
[Secure AI Ebook Reader](../../../docs/secure-ai-ebook-reader-roadmap.md). Spring
Boot luôn xác thực quyền đọc trước; frontend không gọi RAG trực tiếp.

---

## Files

### Spring Boot -> RAG ingestion contract

```text
springboot-rag-ingestion-contract.md
```

Contract để Spring Boot báo cho RAG biết PDF đã được upload vào object storage.

### Internal service MVP security

```text
rag-internal-service-mvp-security.md
```

Ghi chú API key nội bộ, network và bảo vệ internal API.

### Library direct upload SeaweedFS report

```text
library-direct-upload-seaweedfs-implementation-report.md
```

Báo cáo hướng Library backend upload trực tiếp PDF vào SeaweedFS/S3-compatible
storage.

---

## Folder liên quan

Luồng ingest dữ liệu sau khi integration call thành công:

```text
docs/ingestion/
```

Chi tiết bucket/storage:

```text
docs/ingestion/seaweedfs-postgres-upload-flow.md
```

