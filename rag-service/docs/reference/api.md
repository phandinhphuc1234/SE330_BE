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

## Search indexed evidence

```http
POST /internal/retrieval/search
Content-Type: application/json
X-RAG-API-Key: <service-secret>
```

At least one trusted scope is required: `bookId`, `ebookId` or `documentId`.
For the Secure AI Ebook Reader, Spring Boot should authorize the member first
and send the exact `ebookId` being read.

```json
{
  "query": "Dependency inversion là gì?",
  "ebookId": 55,
  "topK": 5,
  "scoreThreshold": 0.7
}
```

Constraints:

- `query`: 1-4096 characters.
- `topK`: optional, 1-50.
- `scoreThreshold`: optional, 0.0-1.0.
- Unknown request fields are rejected.

Response example:

```json
{
  "queryTextHash": "<sha256>",
  "queryTextPolicy": "<embedding query policy>",
  "embeddingVersion": "<configured version>",
  "topK": 5,
  "resultCount": 1,
  "appliedFilters": {
    "ebook_id": 55
  },
  "results": [
    {
      "pointId": "<qdrant point id>",
      "vectorId": "<deterministic vector id>",
      "score": 0.83,
      "text": "Retrieved evidence text...",
      "citation": {
        "documentId": "doc_ebook_55",
        "bookId": 101,
        "ebookId": 55,
        "pageStart": 42,
        "pageEnd": 43,
        "chunkIndex": 12
      },
      "metadata": {}
    }
  ]
}
```

This endpoint returns evidence chunks, not a generated answer. It does not
authenticate end users and must never be called directly from the browser.
The target user-facing flow and answer contract are documented in
[Secure AI Ebook Reader](../../../docs/secure-ai-ebook-reader-roadmap.md).

## Generate an evidence-grounded ebook answer

```http
POST /internal/answers
Content-Type: application/json
X-RAG-API-Key: <service-secret>
```

Spring must authorize the member and reading session first, then send the
trusted `ebookId`. The configured `ANSWER_SCORE_THRESHOLD` is a safety floor:
callers may raise it but cannot lower it.

```json
{
  "question": "Dependency inversion là gì?",
  "ebookId": 55,
  "topK": 5,
  "scoreThreshold": 0.7
}
```

Grounded response:

```json
{
  "answer": "Dependency inversion tách module cấp cao khỏi chi tiết cấp thấp.",
  "grounded": true,
  "abstained": false,
  "reason": null,
  "citations": [
    {
      "documentId": "doc_ebook_55",
      "bookId": 101,
      "ebookId": 55,
      "pageStart": 42,
      "pageEnd": 43,
      "chunkId": "<deterministic-vector-id>",
      "excerpt": "<bounded evidence excerpt>",
      "score": 0.83
    }
  ],
  "model": "gpt-4o-mini",
  "promptVersion": "library-ebook-answer-v1"
}
```

If retrieval is below the threshold, the endpoint does not call the LLM and
returns `abstained=true`, `grounded=false` and an empty citation list. A
non-abstained answer is accepted only when every cited source ID belongs to the
current retrieval result; model-generated citation metadata is never trusted.

Runtime generation settings:

- `LLM_PROVIDER=openai` with `OPENAI_API_KEY`, or `LLM_PROVIDER=gemini` with
  `GEMINI_API_KEY`.
- `LLM_MODEL` must name a model for the selected provider.
- `LLM_TIMEOUT_SECONDS`, `ANSWER_RETRIEVAL_TOP_K`,
  `ANSWER_SCORE_THRESHOLD`, and `ANSWER_MAX_CONTEXT_CHARS` bound cost and risk.

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
