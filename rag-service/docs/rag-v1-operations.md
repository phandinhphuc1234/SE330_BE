# RAG v1 — vận hành, kiểm chứng và demo

Ngày cập nhật: 2026-10-24. Phạm vi đã kiểm chứng: **local**.
Full prompt-v2 evaluation đã PASS 20/20 ngày 19/10, được đọc/xác nhận sau timeout
ngày 24/10. Adversarial PDF và browser Ask/citation/abstention cũng đã qua kiểm thử
local. Reborrow fix đã qua backend 325 tests và browser runtime; CI/release vẫn giữ lại.
User đã duyệt push/PR/merge/deploy khi CI đạt trong follow-up 24/10 để test RAG
lấy số liệu CV. Các mục giữ local bên dưới ghi lại quyết định cũ, không còn là
publish hold hiện tại; không được bỏ qua gate CI/rollback/production smoke.
Suite RAG gần nhất 803 tests / 88% là checkpoint 18/10, không chạy lại ngày 24/10.
Chi tiết tại [latest local gates](evidence/2026-10-24-rag-local-release-gates.md).

## 1. Khởi động và kiểm tra

Từ repository root:

```powershell
.\scripts\dev-up.ps1
docker compose -f compose.yaml -f compose.rag.yaml ps
```

Port Spring của máy này lấy từ `LIBRARY_APP_PORT` trong `.env` (lượt test là
`18090`); RAG loopback mặc định `8000`. Các biến bí mật vẫn nằm trong `.env` và
không được đưa vào command argument, report hoặc Git.

Một fixture riêng đã được tạo qua public API cho kiểm tra upload:

| Source | Book | Ebook | RAG document | Job | Chunks |
|---|---:|---:|---:|---:|---:|
| Golden dataset: The Gift of the Magi | 3342 | 10 | 1 | 3 (reindex) | 6 |
| Local upload fixture, cùng PDF sáu trang | 3343 | 11 | 2 | 4 | 6 |

Golden labels vẫn trỏ ebook 10. Không đổi labels sang ebook 11 và không xóa
fixture tự động. Hai bản có scope/vector riêng.

## 2. Metrics

Bật optional monitoring profile:

```powershell
docker compose -f compose.yaml -f compose.rag.yaml --profile monitoring up -d prometheus
```

Prometheus local: `http://127.0.0.1:9090`; cấu hình hợp nhất:
`prometheus/prometheus.rag.yml`. Nó scrape Spring (`/actuator/prometheus`),
RAG (`/metrics`) và Qdrant (`/metrics`) qua Docker DNS.

`/metrics` của RAG chỉ được truy cập qua private service network hoặc loopback
local. Production Compose không publish port của RAG/worker. Không tạo proxy
public cho endpoint này. Nếu bật Prometheus trên VPS, đặt nó cùng private
network với `rag-api`, giữ UI ở loopback và tuân theo policy Actuator hiện tại.
Lượt này chưa cài monitoring production.

| Metric | Ý nghĩa |
|---|---|
| `library_rag_http_requests_total` | Request theo method, route template, status |
| `library_rag_http_request_duration_seconds` | Histogram thời gian HTTP |
| `library_rag_stage_duration_seconds` | Histogram embedding, dense, BM25, rewrite, RRF, rerank, context và LLM |
| `library_rag_retrieval_outcomes_total` | Retrieval rỗng/không rỗng theo mode |
| `library_rag_answer_outcomes_total` | Grounded, abstained hoặc error với mã ổn định |
| `library_rag_provider_errors_total` | Lỗi provider, gồm timeout/quota |
| `library_rag_ingestion_jobs` | Số job hiện tại theo status, đọc từ PostgreSQL |
| `library_rag_ingestion_job_duration_seconds_mean` | Trung bình created → completed, **gồm queue và retries** |
| `library_rag_ingestion_metrics_up` | 1 khi đọc được snapshot ingestion, 0 khi DB snapshot lỗi |

Counter/histogram HTTP và stages thuộc process API, reset khi process restart.
Thiết kế v1 chạy một Uvicorn worker mỗi container. Ingestion dùng gauge từ DB
vì Celery worker không chia sẻ bộ nhớ với API; gauge có thể giảm khi xóa job.
Snapshot query bị chặn ở 2 giây. Khi DB lỗi, API vẫn xuất metric HTTP/provider
và đặt ingestion snapshot `up=0`; không giả số job bằng 0.

Không dùng request ID, book ID, query hoặc chunk text làm nhãn Prometheus.
Ví dụ truy vấn:

```promql
rate(library_rag_http_requests_total{route="/internal/answers"}[5m])
histogram_quantile(0.95, sum by (le, stage) (rate(library_rag_stage_duration_seconds_bucket[5m])))
increase(library_rag_provider_errors_total{error_code="LLM_RATE_LIMITED"}[15m])
library_rag_ingestion_jobs{status="FAILED"}
```

Spring local hiện đặt `MANAGEMENT_HEALTH_MAIL_ENABLED=false` mặc định, cùng
policy production đang có: SMTP là dependency tùy chọn, DB/Redis health giữ
nguyên. Đặt biến này `true` khi chủ động kiểm tra SMTP. Health UP không chứng
minh gửi email thành công; lượt RAG này không gửi email test.

## 3. Theo dõi request

Luồng correlation:

```text
Client X-Trace-Id (hoặc Spring tạo UUID)
  → Spring MDC traceId và response X-Trace-Id
  → RagClientConfig gắn X-Request-ID khi gọi RAG
  → RAG context request_id, response X-Request-ID và log stage/outcome
```

Lượt runtime đã thấy cùng ID `rag-p0-08-20261018-runtime` ở Spring response và
log RAG. Log outcome chứa ebook ID, mode, citation count, duration và mã lỗi;
không thêm câu hỏi, toàn bộ evidence hoặc credentials vào log outcome mới.

## 4. Smoke public reader có thể chạy lại

Script `scripts/smoke_public_reader.py` nhận credentials từ **process environment**:
`RAG_SMOKE_EMAIL`, `RAG_SMOKE_PASSWORD`. Dùng account MEMBER có loan còn hạn;
không dùng demo account/mật khẩu public trên production.

Từ `rag-service/`, sau khi đặt hai biến trong môi trường cục bộ:

```powershell
.\.venv\Scripts\python.exe scripts/smoke_public_reader.py --book-id 3342 --skip-answers
```

Thêm `--denied-book-id 3226` để xác nhận session của book 3342 không dùng Ask
book khác. Không cần book 3226 có loan: đây là kiểm tra session/book binding.

Script kiểm tra anonymous search/Ask 401, Ask thiếu session 400 và cross-book
Ask 403 nếu bật flag; thêm login/session, scope search, trace ID,
PDF private và page citation trong giới hạn PDF. Nó đóng đúng session vừa tạo
và chỉ in summary không có bearer/session token hoặc signed URL.
HTTP response khi đóng session được kiểm tra; không coi cleanup lỗi là PASS.

Bỏ `--skip-answers` khi quota đã sẵn sàng để kiểm tra thêm grounded answer và
câu hỏi không có đáp án. Thay câu hỏi mặc định khi dùng ebook khác.
`--skip-answers` không phải bằng chứng generation đã pass.

## 5. Evaluation và gate

Đặt `RAG_INTERNAL_API_KEY` trong process environment bằng nguồn cấu hình local
đã được cấp quyền; không truyền key qua CLI argument. Từ `rag-service/`:

```powershell
.\.venv\Scripts\python.exe -m app.evaluation.run_eval `
  --modes dense hybrid --gate-modes hybrid --top-k 3 --skip-answers `
  --min-hit-rate 0.94 --min-recall 0.91

.\.venv\Scripts\python.exe -m app.evaluation.run_eval `
  --modes hybrid --top-k 3 --llm-interval-seconds 30 `
  --min-hit-rate 0.94 --min-recall 0.91 --require-correct-abstention
```

`--gate-modes hybrid` cho phép so sánh dense nhưng chỉ áp ngưỡng quality cho
hybrid. Error/scope/citation metadata violation của bất kỳ mode nào vẫn fail.
`--require-correct-abstention` yêu cầu **đủ cả 20 câu** và đúng nhãn
answerable/abstained, nên một response 200 nói “not stated” nhưng
`abstained=false` vẫn fail.

Report mới ghi commit, dirty-worktree flag, dataset/version, top K, observed
embedding/chunking version, model/prompt version và gate result. Giữ report lỗi
cùng report pass; không sửa số liệu của lượt cũ. Khi prompt thay đổi phải chạy
full dataset lại, không ghép các subset rồi tuyên bố một full run đã pass.

Prompt hiện tại là `library-ebook-answer-v2`, yêu cầu thiếu dữ kiện phải abstain.
Full run `rag-eval-20261019T072231556760Z` hoàn tất đủ 20 câu: 17/17 answerable
được trả lời, 3/3 unanswerable abstain, error/scope/citation metadata violations 0.
Gate **PASS** với HitRate@3 0.941, Recall@3 0.912; không đổi thresholds/labels.
Report 19/20 ngày 18/10 vẫn giữ nguyên như bằng chứng lỗi quota lịch sử; không
ghép subset. Citation precision 0.902 và lexical coverage 0.765 không phải chứng
minh mọi phát biểu đều faithful về ngữ nghĩa. Quota live vẫn có thể hết lại.

## 6. Demo 5–7 phút

1. Mở ebook 10, nêu source PDF có 6 trang/6 chunks.
2. Hỏi chi phí phòng mỗi tuần: nguồn trang 1 ghi `$8 a week`.
3. Mở citation đến đúng trang. Source đã kiểm chứng là PDF 86,822 bytes.
4. Hỏi về món quà/hi sinh của Della và Jim; trình bày nguồn từ trang 3 và 6.
5. Hỏi ngày sinh chính xác của O. Henry: thiếu trong ebook nên phải abstain.
6. Giải thích JWT + loan + reading session được Spring kiểm tra trước RAG.
7. Mở report hybrid HitRate@3 0.941, Recall@3 0.912; nêu giới hạn dataset nhỏ.
8. Tìm request ID trong log và mở metrics/Prometheus.

Các câu trả lời live cần quota; chuẩn bị mở PDF và report đã chạy để giải thích
pipeline khi provider unavailable. Không trình bày report partial như quality PASS.

## 7. Release đang chờ

**Checkpoint mới nhất 25/10:** owner đã duyệt lại publishing và PR #37/#38 đã
merge sau CI; release `7ce3bc4` đã deploy thành công. Retrieval production PASS,
nhưng full answer baseline có ba `LLM_RATE_LIMITED` và một false abstention;
PDF browser bị chặn bởi storage public origin loopback. Xem
[production acceptance/evidence](evidence/2026-10-25-production-rag-validation.md).
Các ghi chú giữ local/quota bên dưới là lịch sử trước lần duyệt mới, không phải
trạng thái deploy hiện tại. Chưa được gọi acceptance toàn bộ là PASS.

- Full tests/runtime reborrow fix đã pass local; phải kiểm chứng lại trên commit CI/release.
- Có xác nhận mới trước commit/push/PR/merge/deploy; hiện vẫn giữ local.
- CI source/tests/image scan pass trên commit release.
- Ghi digest production hiện tại để rollback trước khi merge/deploy.
- Deploy qua CI với immutable digest, rồi smoke public bằng account có quyền.
- Xác nhận citation navigation trong browser production.

Chủ dự án đã yêu cầu giữ deploy chờ quota; dù local answer gate nay đã đạt,
không tự suy ra quyền merge/release từ các kết quả local trong tài liệu này.
Quyết định follow-up ngày 18/10: **giữ thay đổi local, chưa commit/push**;
không tạo PR hoặc tự chạy release workflow.

## 8. Ingestion failure và retry đã kiểm chứng local

`session.rollback()` làm ORM instance hết hiệu lực kể cả khi dùng
`expire_on_commit=False`. Nhánh lưu lỗi ingestion phải reload job bằng scalar
`ingestion_job_id`, không đọc `job.id` từ instance đã rollback; nếu không có thể
gặp `MissingGreenlet` và để DB báo PROCESSING trong khi Celery đã FAILURE.

Bản sửa đã chạy qua worker thật:

- Zero-page PDF, document 3/job 7: FAILED sau 4 attempts, completed_at có,
  0 chunks/vectors, polling API trả INGESTION_FAILED.
- Source-storage failure, document 5/job 8: nguồn được cung cấp sau khi quan sát
  RETRYING, job INDEXED ở attempt 2, 6 chunks/6 scoped vectors.
- Job test 5 trước bản sửa đã được đối chiếu với Celery FAILURE và reconcile thủ
  công về FAILED có audit metadata; không tính là automatic regression PASS.

Các RAG-only readiness fixtures dùng ebook IDs 1576208978, 1026229968 và
1836146605; không phải sách mới tạo trong public Library catalog. Source files
và job history được giữ lại, không xóa/ghi đè ebook golden hiện có.

## 9. Đo RAG trên VPS mà không mở cổng nội bộ

Workflow **Verify production RAG** chạy qua SSH đã pin host key, không đọc key
ra local, không sửa data hay quyền đọc. Chạy `mode=inventory` trước: artifact chỉ
gồm IDs, trạng thái job, source/chunk checksums và vector IDs, không có raw text.
Giới hạn 50 documents / 1000 chunks mỗi document, không gọi provider ở mode này.

Chỉ dùng `mode=evaluate`, `document_id=<ID đã kiểm tra>` khi production chứa đúng
PDF reference. Tool yêu cầu SHA-256 PDF và cả sáu chunk text khớp baseline v1 mới
remap IDs giữa hai database; không đổi câu hỏi, expected pages, answer points
hoặc nhãn answerable. Nếu checksum khác, dừng trước provider calls và cần label
corpus riêng, không sửa nhãn chỉ để có số đẹp.

Evaluation chạy retrieval dense/hybrid toàn bộ 20 câu, gate hybrid không đổi;
sau đó full 20-case answer bằng hybrid, pacing 30 giây, không judge và không
unbounded retry. Artifact chứa report pass/fail, scoped dataset binding, snapshot
và p50/p95 nearest-rank quan sát. Thời gian Ask bao gồm retrieval + generation,
không phải model-only latency, concurrent-load benchmark hoặc SLA. Quota lỗi vẫn
là operational error và gate fail, không được ghép subset thành 20/20 PASS.

Workflow không thay thế upload/ingestion/public JWT-loan-session/browser citation
checks; các bước đó phải ghi riêng trong production acceptance report.
