# Internal RAG API

RAG is an internal service. Spring Boot owns end-user authentication and authorization; RAG does not expose user registration, login, workspace, chat-session, feedback, or direct-upload APIs.

## Authentication

All `/internal/*` endpoints require:

```http
X-RAG-API-Key: <service-secret>
```

RAG reads the expected value from `RAG_INTERNAL_API_KEY` and compares it using a constant-time comparison. Use a different long random secret per environment and inject it through a secret manager in production.

Docker network isolation is not authentication. Production should also avoid publishing the API host port and should prefer TLS/mTLS or short-lived service JWTs when the deployment platform supports them.

## Create ingestion

```http
POST /internal/ingestions
Content-Type: application/json
X-RAG-API-Key: <service-secret>
```

```json
{
  "sourceType": "LIBRARY_EBOOK",
  "bookId": 101,
  "ebookId": 55,
  "bucket": "library-private",
  "objectKey": "ebooks/101/55/original.pdf",
  "checksumSha256": "<64 hexadecimal characters>"
}
```

Optional fields:

```json
{
  "originalFilename": "clean-code.pdf",
  "contentType": "application/pdf",
  "fileSizeBytes": 5242880
}
```

Response:

```http
HTTP/1.1 202 Accepted
```

```json
{
  "documentId": "doc_ebook_55",
  "ingestionJobId": 123,
  "status": "QUEUED"
}
```

The endpoint only accepts the configured `LIBRARY_EBOOK_BUCKET` and the exact key `ebooks/{bookId}/{ebookId}/original.pdf`. Repeating the same ebook/checksum returns the existing active/completed job without enqueueing duplicate work.

## Read ingestion status

```http
GET /internal/ingestions/{ingestionJobId}
X-RAG-API-Key: <service-secret>
```

```json
{
  "documentId": "doc_ebook_55",
  "ingestionJobId": 123,
  "status": "PROCESSING",
  "stage": "parsing_pdf",
  "errorCode": null,
  "errorMessage": null
}
```

Current terminal success is `INDEXED`. The worker still commits `CHUNKED` as an
intermediate state after chunks/artifacts are persisted, but `INDEXED` is only
set after embedding and Qdrant upsert succeed.

## Health

```http
GET /api/v1/health
```

Health remains unauthenticated so container orchestration can probe it. It must only be reachable from trusted networks in production.

## Interactive documentation

Swagger/ReDoc/OpenAPI are disabled by default. Enable locally only:

```dotenv
ENABLE_API_DOCS=true
```
