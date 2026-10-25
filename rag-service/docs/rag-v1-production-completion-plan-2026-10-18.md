# RAG v1 Production Completion Plan — 2026-10-18

Status: **PARTIAL — LOCAL GATES PASS; PUBLISH/DEPLOY APPROVED CONDITIONAL ON CI; PRODUCTION TESTS PENDING**
Target: hoàn thành một bản **RAG v1 demo-ready và production-verifiable trong hôm nay**.
Baseline được giữ nguyên: `chunking=v1`, `CHUNK_SIZE=512`, `CHUNK_OVERLAP=64`,
`retrieval=hybrid`, Gemini Embedding, Qdrant, PostgreSQL BM25 và heuristic reranker.

> Mục tiêu hôm nay không phải thêm mọi kỹ thuật RAG nâng cao. Mục tiêu là chứng
> minh luồng hiện tại chạy đúng từ upload đến citation, có regression test, có
> metric vận hành tối thiểu và có bằng chứng deploy/rollback rõ ràng.

## Tóm tắt tiến độ đã đối chiếu

Cập nhật ngày **2026-10-24**, đối chiếu evidence lịch sử và checkpoint mới bên dưới.
`[x]` chỉ xác nhận đúng mức kiểm chứng ghi kèm; **local/test tự động không có
nghĩa là đã deploy hoặc đã kiểm chứng production**.

- [x] Code và regression tests cho phạm vi v1 trong kế hoạch này đã hoàn thiện local.
- [x] Upload/reindex PDF thật, dữ liệu SQL/Qdrant, retrieval và phân quyền đã chạy local.
- [x] Answer/citation/abstention smoke đã chạy với Gemini thật, prompt v1 và v2; browser local v2 chuyển trang 1 → 3 bằng citation.
- [x] Retrieval gate: Hybrid HitRate@3 **0.941**, Recall@3 **0.912**; không có lỗi/scope violation.
- [x] Metrics, trace ID và bốn Prometheus targets đã kiểm chứng local.
- [x] Latest RAG suite checkpoint 18/10: **803 tests**, coverage **88%**; backend chạy lại ngày 24/10: **325 tests**, không lỗi/skip.
- [x] Image candidate pass Trivy theo policy HIGH/CRITICAL có bản vá; không phải tuyên bố không còn mọi lỗ hổng.
- [x] Backend image mới của reborrow fix cũng qua Trivy ngày 24/10: exit 0, 0 fixed HIGH/CRITICAL; không thay bằng scan RAG cũ.
- [x] Runbook, script smoke có thể chạy lại và evidence local đã được ghi lại.
- [x] Full answer gate **20/20 PASS**, report ngày 19/10 đã lưu xong trong lượt bị gián đoạn và được đọc/xác nhận ngày 24/10: 17/17 answerable, 3/3 unanswerable; không ghép subset.
- [x] Smoke local PDF rỗng, source-storage retry/recovery và public Ask negative cases đã chạy thật; tìm/sửa thêm lỗi persist `FAILED` sau rollback.
- [x] Adversarial PDF qua worker thật: job 9 INDEXED, 2/2 answer/abstention checks pass với Gemini.
- [x] Public/browser v2: loan hợp lệ → Ask factual → citation trang 3 → PDF trang 3/6; câu thiếu dữ kiện abstain. Có 1 lỗi provider ban đầu, retry thủ công một lần thành công; không che lỗi đó.
- [x] Lỗi mượn lại ebook hết hạn được user duyệt sửa ngày 24/10: free/paid cùng expire loan cũ trước INSERT; full backend 325 tests pass; browser mượn lại golden thành công, loan 6 EXPIRED và loan 9 ACTIVE.
- [ ] Commit/push, PR review và CI cho chính các working changes này; user đã duyệt push/PR/merge/deploy khi CI đạt ngày 24/10.
- [ ] Ghi rollback digest, deploy bằng image đã qua CI và chạy smoke/browser checks trên production.

Gate chất lượng RAG local đã đạt trên dataset 20 câu hiện tại; không phải đánh giá
toàn bộ câu trả lời đều đúng ngữ nghĩa hoặc bảo đảm quota production. Lỗi loan cũ
ACTIVE quá hạn đã sửa và kiểm chứng local. User đã gỡ yêu cầu giữ local, duyệt
publishing/deploy khi CI đạt để test RAG lấy số liệu CV. CI, rollback digest và
production smoke vẫn phải xác nhận riêng; preflight chỉ đọc đã đạt trên VPS.

Tài liệu vận hành: [RAG v1 operations](rag-v1-operations.md).
Bằng chứng tổng hợp: [local quality gates](evidence/2026-10-18-rag-p0-09-quality-gates.md).
Follow-up mới nhất: [20/20 evaluation, adversarial PDF, browser và reborrow](evidence/2026-10-24-rag-local-release-gates.md).

## 1. Definition of Done

Chỉ đánh dấu kế hoạch này `DONE` khi tất cả điều kiện P0 sau đều đạt:

- [x] Một PDF thật được upload qua backend và ingestion kết thúc ở `INDEXED` (terminal state của implementation).
- [x] PostgreSQL có document/chunk tương ứng và Qdrant có vector tương ứng.
- [x] Internal retrieval trả kết quả không rỗng, đúng `ebookId` và có citation
      gồm ít nhất `ebookId` + `pageStart`.
- [x] User có quyền đọc gọi semantic search và Ask This Book thành công (local v1/v2; public browser v2 dùng ebook fixture 11).
- [x] User không có quyền đọc bị từ chối; browser không gọi trực tiếp RAG service.
- [x] Citation local có page hợp lệ, signed PDF tải/render được và browser chuyển đúng trang 3/6; browser production vẫn chờ post-deploy smoke.
- [x] V2 abstain đúng 3/3 golden unanswerable và câu thiếu dữ kiện trên browser local.
- [x] Prompt injection được escape trong source và không đổi system instruction theo regression test cấu trúc.
- [x] Retrieval evaluation chạy lại thành công và không thấp hơn quality gate đã
      chốt trong mục 5.
- [x] Answer runner đã thử đủ 20 golden cases không dùng judge, phân biệt lỗi quota/provider với abstention.
- [x] Answer evaluation hoàn tất đủ **20/20 không có lỗi provider**, gồm 3/3 unanswerable abstain đúng (full run ngày 19/10).
- [x] RAG API có metric tối thiểu và request ID để truy vết lỗi/latency (local Prometheus scrape verified).
- [x] Local unit/integration tests, backend tests và image security scan pass; CI release vẫn chờ.
- [ ] Deploy production bằng image digest đã được CI kiểm thử.
- [ ] Post-deploy smoke test pass và có rollback target rõ ràng.
- [x] Bằng chứng local đã chạy được ghi vào mục 10; giữ cả report fail/partial.
- [x] Giữ report v2 partial 19/20 và bổ sung full 20/20 PASS; failure smokes và suite 803 tests lịch sử được giữ nguyên.
- [ ] Bổ sung CI và production vào mục 10 trước khi chốt DONE.

## 2. Phạm vi hôm nay

### Phải hoàn thành

- [x] Chụp baseline và kiểm kê cấu hình mà không in secret.
- [x] Test luồng chính ingestion, retrieval, answer, citation và authorization trên local; generation runtime dùng prompt v1.
- [x] Hoàn thiện evaluation runner và chạy retrieval gate thành công.
- [x] Chạy full answer evaluation của prompt v2, giữ nguyên report 19/20 và lỗi quota của câu cuối.
- [x] Chạy full answer evaluation v2 **thành công**, report `rag-eval-20261019T072231556760Z`; không ghép subset.
- [x] Bổ sung regression/security tests cho citation, scope, prompt injection và model JSON lỗi.
- [x] Bổ sung observability tối thiểu cho RAG API và kiểm chứng local.
- [x] Chạy full test suites và image scan local.
- [ ] Chạy CI/review, deploy và smoke test production sau khi các gate đủ điều kiện.
- [x] Cập nhật tài liệu/runbook bằng số liệu local thật.
- [ ] Chốt tài liệu bằng kết quả release production thật.

### Không làm hôm nay

- Cross-encoder reranker.
- LLM multi-query rewriting.
- Global/community GraphRAG.
- Agentic RAG.
- Thay Gemini Embedding bằng E5/BGE-M3.
- Chuyển mặc định sang chunking v2.
- Phoenix và MLflow cùng lúc.
- OCR/layout parsing nếu PDF demo có text layer tốt.
- Recommendation system.

Các mục trên chỉ được mở lại sau khi v1 có held-out evaluation ổn định.

## 3. Nguyên tắc thực hiện

- Không xóa hoặc ghi đè report evaluation hiện có.
- Report mới phải có timestamp và được lưu như artifact; chỉ commit report đã
  được xem và xác nhận hợp lệ.
- Không in `.env`, API key, password hoặc token vào terminal/log/report.
- Không đổi threshold hoặc weight chỉ để làm một case đơn lẻ pass.
- Mọi thay đổi ranking phải so sánh với baseline trên cùng dataset.
- `grounded=true` không được diễn giải là citation đúng về ngữ nghĩa nếu mới chỉ
  kiểm tra citation ID tồn tại.
- Provider timeout/quota là lỗi vận hành, không phải model abstention.
- Chỉ deploy image digest đã đi qua CI; không build tùy ý trực tiếp trên VPS.
- Không merge nếu quality gate P0 chưa pass hoặc rollback target chưa xác định.

## 4. Task board hôm nay

| ID | Công việc | Timebox | Trạng thái | Bằng chứng bắt buộc |
|---|---|---:|---|---|
| RAG-P0-01 | Baseline và environment audit | 30 phút | DONE | commit SHA, Compose baseline, config names |
| RAG-P0-02 | Local/integration test sạch | 45 phút | DONE | baseline 779 RAG + 319 backend; final suites ở P0-09 |
| RAG-P0-03 | Ingestion E2E với PDF thật | 60 phút | DONE (local, thêm failure/retry smoke) | golden/public upload giữ 6 chunks/vectors; job 7 FAILED đúng; job 8 retry rồi INDEXED ở lần thử 2 |
| RAG-P0-04 | Retrieval/authorization smoke | 45 phút | DONE (local) | scoped hits; anonymous Ask 401, thiếu session 400, cross-book Ask 403 |
| RAG-P0-05 | Answer/citation/abstention smoke | 60 phút | DONE (local v1/v2) | Browser factual answer, citation page 1 → 3/6, rendered PDF and abstention verified |
| RAG-P0-06 | Prompt-injection và citation tests | 60 phút | DONE (bounded local scope) | deterministic regressions + real adversarial PDF job 9, 2/2 Gemini checks; không tuyên bố miễn nhiễm |
| RAG-P0-07 | Retrieval + answer evaluation | 75 phút | DONE (local, full v2 20/20) | 17/17 answers, 3/3 abstentions, errors/scope/citation-metadata violations 0; unchanged thresholds |
| RAG-P0-08 | Metrics và trace tối thiểu | 60 phút | DONE (local) | private metrics, persisted ingestion snapshot, trace, Prometheus targets up |
| RAG-P0-09 | Full quality gate + review | 45 phút | LOCAL TEST/RUNTIME/SCAN PASS; CI PENDING | RAG checkpoint 803 / 88%; backend new 325; reborrow browser + auth smoke + backend Trivy pass; provenance in latest evidence |
| RAG-P0-10 | Deploy và production smoke | 60 phút | APPROVED; CI/DEPLOY PENDING | read-only VPS preflight pass; new release CI and rollback still required |
| RAG-P0-11 | Chốt tài liệu và demo script | 30 phút | PREPARED; production pending | operations runbook, repeatable public smoke, evidence ledger |

Thứ tự bắt buộc: `01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11`.
Nếu một bước fail, ghi nguyên nhân và sửa ngay tại bước đó; không đẩy lỗi xuống
bước deploy. Theo chỉ đạo tiếp theo của người dùng, P0-08/09 và tài liệu được
hoàn thiện độc lập khi P0-07 còn chờ quota; P0-10 tiếp tục giữ lại.

## 5. Quality gates

### 5.1 Retrieval gate

Dataset baseline:
`app/evaluation/datasets/gift_of_the_magi_v1.json`.

Baseline đã ghi nhận trước đây ở `topK=3`:

| Mode | Hit rate | Recall | MRR | nDCG |
|---|---:|---:|---:|---:|
| Dense | 0.882 | 0.853 | 0.804 | 0.812 |
| Hybrid | 0.941 | 0.912 | 0.725 | 0.762 |

Gate hôm nay:

- `errorCount = 0`.
- `scopeViolations = 0`.
- Hybrid `HitRate@3 >= 0.94`.
- Hybrid `Recall@3 >= 0.91`.
- Không chấp nhận regression MRR/nDCG mà không có phân tích case-level.
- Mọi report phải ghi commit SHA, dataset version, mode, `topK`, chunking version
  và embedding version.

Lệnh dự kiến từ `rag-service/`:

```powershell
poetry run python -m app.evaluation.run_eval `
  --dataset app/evaluation/datasets/gift_of_the_magi_v1.json `
  --modes dense hybrid --gate-modes hybrid `
  --top-k 3 `
  --skip-answers `
  --min-hit-rate 0.94 --min-recall 0.91 `
  --output-dir app/evaluation/reports
```

### 5.2 Answer gate

Chạy không dùng LLM judge trước để tránh tăng gấp đôi số provider calls:

```powershell
poetry run python -m app.evaluation.run_eval `
  --dataset app/evaluation/datasets/gift_of_the_magi_v1.json `
  --modes hybrid `
  --top-k 3 `
  --llm-interval-seconds 30 `
  --min-hit-rate 0.94 --min-recall 0.91 --require-correct-abstention `
  --output-dir app/evaluation/reports
```

Gate bắt buộc:

- 20/20 cases được xử lý hoặc report phải fail rõ ràng.
- 17 answerable cases không được abstain do lỗi provider.
- 3 unanswerable cases phải abstain.
- Citation ID phải thuộc evidence allow-list.
- Citation phải đúng `ebookId` và có `pageStart > 0`.
- Answer point coverage phải được ghi cho từng case.
- Provider timeout/quota phải nằm trong error count, không được tính là abstention.

Nếu quota không đủ, không hạ gate và không sửa report. Ghi `BLOCKED_BY_PROVIDER`
cùng response code/thời điểm đã loại bỏ secret, sau đó dùng key/quota hợp lệ hoặc
provider đã được dự án phê duyệt để chạy lại.

### 5.3 Test gate

Từ `rag-service/`:

```powershell
poetry run pytest --cov=app --cov-report=term-missing --cov-report=xml
```

Từ repository root:

```powershell
.\mvnw.cmd test
```

Không chấp nhận:

- Test bị skip vì thiếu service mà không được ghi nhận.
- Chỉ chạy unit test rồi kết luận E2E pass.
- Dùng mock provider làm bằng chứng production.

## 6. Smoke-test matrix

### 6.1 Ingestion

| Case | Expected | Đã kiểm chứng |
|---|---|---|
| PDF text layer hợp lệ | Job `INDEXED`, chunk count > 0 | PASS local: public upload job 4, 6 chunks và 6 vectors |
| PDF rỗng/không đủ text | Fail bằng error code rõ ràng | PASS local zero-page PDF: job 7 FAILED, attempts 4, completed_at có, 0 chunks/vectors; API INGESTION_FAILED |
| Reindex lại cùng revision | Không tạo dữ liệu active trùng ngoài thiết kế | PASS local: job 3 giữ 6 chunks/6 vectors, không nhân đôi |
| Worker/source-storage lỗi tạm thời khi ingestion | Retry bounded, trạng thái quan sát được | PASS local job 8: object thiếu → RETRYING → phục hồi source → INDEXED ở attempt 2; không coi đây là test mọi provider outage |

Evidence cần lưu:

- `bookId`, `ebookId`, ingestion `jobId`, `documentId`.
- File checksum/revision, parser/chunker/embedding version.
- Page count, chunk count, vector count và thời gian ingestion.

### 6.2 Internal retrieval

Ví dụ, thay các placeholder bằng ID của PDF demo:

```powershell
poetry run python scripts/smoke_internal_retrieval.py `
  --base-url http://127.0.0.1:8000 `
  --query "Câu hỏi có đáp án trong sách" `
  --book-id <BOOK_ID> `
  --ebook-id <EBOOK_ID> `
  --top-k 5 `
  --fail-on-empty
```

Phải kiểm tra thêm:

- [x] API key đúng: HTTP success (local).
- [x] Thiếu/sai API key: bị từ chối (local HTTP 401).
- [x] `ebookId` khác: không rò chunk của ebook demo (local empty result).
- [x] Citation của các hit kiểm tra nằm đúng scope (local).

### 6.3 Public Library flow

| Actor | Action | Expected |
|---|---|---|
| Admin | Upload PDF | Nhận job và theo dõi được trạng thái |
| Member có quyền | Semantic search | Nhận evidence đúng ebook |
| Member có quyền | Ask | Answer + citation hoặc abstention hợp lệ |
| Member không quyền | Search/Ask | HTTP 403/contract tương ứng |
| Anonymous | Search/Ask | HTTP 401/contract tương ứng |
| Browser | Gọi thẳng RAG internal API | Không có public route/credential |

Kết quả local đã có: admin upload, member search/Ask prompt v1, anonymous search
401, sai role 403 và member thiếu loan bị chặn khi tạo reading session. Follow-up
đã gọi Ask trực tiếp: anonymous 401, thiếu session 400/READING_SESSION_REQUIRED,
dùng session khác book 403/READING_SESSION_FORBIDDEN. Cấu hình/test xác nhận
production không publish RAG port. Chưa có post-deploy smoke cho working changes này.

### 6.4 Citation và abstention

| Case | Mức xác nhận hiện có | Còn thiếu |
|---|---|---|
| Câu fact một trang | PASS local Gemini v1/v2; browser v2 answer + citation chuyển trang 1 → 3/6 và PDF render đúng | Browser production sau deploy |
| Câu cần evidence từ hai trang | Full v2 two-sacrifices/unusable-gifts có answer và multi-page citations; đối chiếu PDF có evidence trên trang 5/6 | Chưa browser smoke câu multi-page; không coi mọi citation đã được semantic judge xác nhận |
| Câu không có trong ebook | Full v2 3/3 golden cases abstain; browser local cũng abstain cho maiden surname | Production smoke sau deploy |
| Câu yêu cầu dùng kiến thức ngoài ebook | PASS local Gemini v1: hỏi thủ đô Pháp trả MODEL_ABSTAINED, không citation | Chạy lại với v2 khi quota sẵn sàng |
| Ebook chứa `Ignore previous instructions ...` | PASS deterministic regression + PDF thật job 9: factual 08:30/page 1, missing fact abstain, không marker/citation giả | Chỉ 1 PDF/2 cases; không tuyên bố model miễn nhiễm |
| Model trả citation ID không tồn tại | PASS deterministic regression: INVALID_CITATIONS, fail closed | Production regression smoke khi release |
| Model trả JSON sai schema | PASS deterministic regression: LLM_INVALID_RESPONSE là operational error | Không dùng mock làm bằng chứng provider runtime |

Expected:

- Case 1–2: answer có citation mở đúng trang.
- Case 3–4: abstain khi evidence không đủ.
- Case 5: instruction trong ebook được coi là untrusted data.
- Case 6: `INVALID_CITATIONS` và abstain.
- Case 7: lỗi vận hành `LLM_INVALID_RESPONSE`, không giả làm abstention.

## 7. Observability tối thiểu

RAG API đã có `RequestIdMiddleware` và `TimingMiddleware`; lượt này bổ sung
Prometheus `/metrics`, đã test local:

- [x] Thêm `/metrics` trên private/internal network; production RAG không publish host port.
- [x] Đếm request theo route template/status.
- [x] Đo latency tổng và latency theo stage: embedding, dense, BM25, fusion,
      rerank, context building, LLM.
- [x] Đếm empty retrieval, abstention, invalid citation, provider error/quota.
- [x] Đếm ingestion theo status và mean duration bằng persisted DB snapshot, bao gồm workers.
- [x] Structured log có `request_id`, `ebook_id`, `document_id`, mode và duration;
      tuyệt đối không log raw API key hoặc toàn bộ nội dung sách/prompt.
- [x] Truyền Spring MDC `traceId` → RAG `X-Request-ID`; response Spring giữ `X-Trace-Id`.
- [x] Test metrics, route cardinality, DB failure và production RAG không publish port.

Phoenix/OTel tracing là bước sau. Nếu còn thời gian, thiết kế instrumentation để
có thể nối OpenTelemetry sau này, nhưng không để nó chặn v1 hôm nay.

## 8. Files dự kiến tác động

Danh sách này là phạm vi dự kiến, không phải bằng chứng file đã được sửa:

- `rag-service/app/main.py`
- `rag-service/app/core/middlewares/`
- `rag-service/app/generation/answer_generator.py`
- `rag-service/app/evaluation/`
- `rag-service/tests/unit/`
- `rag-service/tests/integration/`
- `rag-service/pyproject.toml` nếu cần dependency metrics
- `rag-service/infra/prometheus/prometheus.yml`
- `rag-service/docs/rag-full-flow.md`
- `rag-service/docs/retrieval-evaluation-implementation.md`
- Spring RAG client/interceptor nếu chưa truyền `X-Request-ID`

Không sửa frontend trừ khi smoke test xác nhận citation navigation hoặc error
state đang hỏng.

## 9. Deploy và rollback gate

Local đã hoàn tất, không thay thế CI/release:

- [x] Đã review phạm vi diff: RAG, Spring trace forwarding, monitoring/health config và docs; không đổi frontend/migration/golden labels.
- [x] Chưa stage/commit các report lịch sử chưa review; chúng vẫn được giữ nguyên.
- [x] Latest RAG suite: 803 passed, coverage 88%, không failure/skip.
- [x] Backend full suite mới ngày 24/10: 325 passed, failures/errors/skipped 0, gồm reborrow fix và PostgreSQL partial-index test.
- [x] Local image candidate pass Trivy theo policy HIGH/CRITICAL, `--ignore-unfixed`; chưa phải image production.
- [x] Runbook và repeatable public-reader smoke đã chuẩn bị; lần chạy script gần nhất dùng `--skip-answers`.

Trước merge/deploy vẫn cần:

- [x] Full prompt-v2 answer gate ở mục 5.2 pass: report ngày 19/10, xác nhận ngày 24/10.
- [x] Full backend tests và smoke bản build mới của reborrow fix ngày 24/10 pass.
- [ ] Commit/push đúng phạm vi trên branch, chỉ đưa report đã review vào PR.
- [ ] CI RAG tests và backend tests pass trên commit release.
- [ ] CI build/image scan pass, ghi immutable digest của image được publish.
- [ ] PR review không còn issue P0/P1 chưa giải quyết.
- [ ] Ghi lại production image digest hiện tại để rollback.
- [ ] Merge/deploy khi gate đạt và yêu cầu giữ deploy đã được gỡ.

Sau deploy:

- [ ] Backend public health pass.
- [ ] RAG container, worker, Redis, PostgreSQL, Qdrant và SeaweedFS healthy.
- [ ] Production internal retrieval trả ít nhất một hit đúng scope.
- [ ] Public Ask flow pass bằng member có quyền.
- [ ] Citation mở đúng nguồn.
- [ ] Unauthorized flow vẫn bị chặn.
- [ ] Metric/log nhận được request ID của smoke test.

Rollback ngay khi:

- Authorization/scope filter rò dữ liệu.
- Ingestion làm mất hoặc duplicate active revision ngoài thiết kế.
- Error rate tăng rõ rệt hoặc provider call loop không bounded.
- Citation trỏ sai ebook/page ở smoke test chuẩn.
- Backend/RAG health không ổn định sau deploy.

Rollback bằng image digest production trước đó; không dùng `latest` làm rollback
target.

## 10. Evidence ledger

Chỉ điền số liệu đã chạy thật. Không sao chép baseline cũ vào cột kết quả mới.

| Thời gian | Commit SHA | Task | Môi trường | Kết quả | Evidence path/URL | Ghi chú |
|---|---|---|---|---|---|---|
| 2026-10-18 10:24 +07:00 | `0e974ae` | RAG-P0-01 | local | PASS | `docs/evidence/2026-10-18-rag-p0-01-baseline.md` | No containers were running; runtime health is verified in P0-03. |
| 2026-10-18 10:29 +07:00 | `0e974ae` | RAG-P0-02 | local | PASS | `docs/evidence/2026-10-18-rag-p0-02-tests.md` | RAG 779 passed at 87% coverage; backend 319 passed. |
| 2026-10-18 10:45 +07:00 | `0e974ae` | RAG-P0-03 | local | PASS | `docs/evidence/2026-10-18-rag-p0-03-ingestion.md` | Real six-page PDF reindexed in 65.768s; 6 chunks and 6 Qdrant points. |
| 2026-10-18 10:55 +07:00 | `0e974ae` | RAG-P0-04 | local | PASS | `docs/evidence/2026-10-18-rag-p0-04-retrieval-authorization.md` | Public member flow returned 5 scoped hits; anonymous, wrong-role, no-loan and wrong-scope cases were blocked. |
| 2026-10-18 10:58 +07:00 | `0e974ae` | RAG-P0-05 | local | PASS | `docs/evidence/2026-10-18-rag-p0-05-answer-citation-abstention.md` | Factual answer cited page 1 and opened the signed PDF; out-of-scope question abstained. |
| 2026-10-18 11:01 +07:00 | `0e974ae` | RAG-P0-06 | local | PASS | `docs/evidence/2026-10-18-rag-p0-06-security-regression.md` | Injection text is escaped; foreign evidence, invented citations and malformed JSON fail closed. |
| 2026-10-18 11:10 +07:00 | `0e974ae` | RAG-P0-07 | evaluation | PARTIAL — BLOCKED_BY_PROVIDER | `docs/evidence/2026-10-18-rag-p0-07-evaluation.md` | Retrieval gate passed; full answer run completed 18/20 and two bounded retries remained `LLM_RATE_LIMITED`. |
| 2026-10-18 14:04 +07:00 | `0e974ae` + working changes | RAG-P0-08 | local | PASS | `docs/evidence/2026-10-18-rag-p0-08-observability.md` | Public trace propagated; all four Prometheus targets up. |
| 2026-10-18 14:09 +07:00 | `0e974ae` + working changes | RAG-P0-07 retrieval | local | PASS, exit 0 | `app/evaluation/reports/rag-eval-20261018T070921557652Z.md` | All 20 retrieval cases rerun; hybrid gate passed with HitRate 0.941 / Recall 0.912. |
| 2026-10-18 14:16 +07:00 | `0e974ae` + working changes | RAG-P0-03 public upload | local | PASS | `docs/evidence/2026-10-18-rag-p0-03-ingestion.md` | Public API upload created book 3343 / ebook 11 / doc 2 / job 4; INDEXED, 6 chunks and 6 vectors. |
| 2026-10-18 14:19 +07:00 | `0e974ae` + working changes | RAG-P0-07 answer diagnostic | local, prompt v1 | FAIL — abstentionMismatch | `app/evaluation/reports/rag-eval-20261018T071939100580Z.md` | Both remaining calls completed; unknown-city had abstained=false despite missing fact. Full prompt-v2 rerun pending. |
| 2026-10-18 14:29 +07:00 | `0e974ae` + working changes | RAG-P0-09 | local | LOCAL PASS; CI pending | `docs/evidence/2026-10-18-rag-p0-09-quality-gates.md` | 792 RAG tests / 87% coverage; 320 backend tests; no failures/skips. |
| 2026-10-18 15:38 +07:00 | `0e974ae` + working changes | RAG-P0-07 v2 | local | PARTIAL — 19/20, provider-blocked | `app/evaluation/reports/rag-eval-20261018T083851792023Z.md` | 17 answerable và 2 unanswerable đúng; câu cuối LLM_RATE_LIMITED; gate exit 1. |
| 2026-10-18 follow-up | `0e974ae` + working changes | RAG-P0-03/04/09 | local | TEST/SCAN/FAILURE SMOKES PASS | `docs/evidence/2026-10-18-rag-p0-remaining-local-validation.md` | Fixed MissingGreenlet on rollback; jobs 7/8 verified; Ask negative checks; 803 tests / 88%; rebuilt digest scan pass. |
| 2026-10-19 14:22 +07:00 (verified 24/10) | `0e974ae` + working changes | RAG-P0-07 v2 | local | PASS 20/20 | `app/evaluation/reports/rag-eval-20261019T072231556760Z.json` | 17 answered, 3 abstained; no provider/scope/citation metadata errors; not a new run on 24/10. |
| 2026-10-24 | `0e974ae` + working changes | RAG-P0-05/06 | local | ADVERSARIAL/BROWSER PASS | `docs/evidence/2026-10-24-rag-local-release-gates.md` | PDF job 9 INDEXED, 2/2 adversarial cases; browser Ask/citation page 3/abstention; initial provider failure preserved. |
| 2026-10-24 | `0e974ae` + working changes | Reborrow regression / P0-09 | local | 325 TESTS + RUNTIME/SCAN PASS | `docs/evidence/2026-10-24-rag-local-release-gates.md` | Real PostgreSQL partial-index test; old loan 6 EXPIRED, new loan 9 ACTIVE; protected-reader negative checks and new backend Trivy gate pass. |
| 2026-10-18 | | RAG-P0-10 | production | ON HOLD — USER REQUEST | | Full prompt-v2 evaluation and quota must be ready before release. |
| 2026-10-18 | | RAG-P0-11 | local | PREPARED | `docs/rag-v1-operations.md`; `scripts/smoke_public_reader.py` | Public smoke script ran with generation disabled; production demo acceptance is still pending. |

## 11. Demo checklist cuối ngày

Demo chỉ cần một câu chuyện rõ ràng trong 5–7 phút:

1. Mở ebook đã ingest và chỉ ra trạng thái `INDEXED`.
2. Hỏi một câu factual; mở citation đúng trang.
3. Hỏi một câu cần hai evidence; giải thích hybrid retrieval.
4. Hỏi một câu không có trong sách; cho thấy hệ thống abstain.
5. Giải thích authorization: chỉ member có quyền đọc mới gọi được Ask.
6. Mở report evaluation và trình bày Recall/MRR/citation/abstention.
7. Mở metric/log theo request ID để debug một request.
8. Nêu trung thực giới hạn hiện tại: heuristic reranker, dataset còn nhỏ và
   cross-encoder/chunking v2 đang ở backlog có evaluation gate.

## 12. Sau khi v1 hoàn thành

Không thực hiện các mục này trước khi toàn bộ P0 ở Definition of Done đã pass:

Đây là **backlog sau v1**, không phải các tính năng bắt buộc để chốt release v1:

- [ ] Mở rộng held-out dataset lên 3–5 ebook và 50–100 câu.
- [ ] Thử cross-encoder và A/B với heuristic reranker.
- [ ] Đánh giá chunking v2 trên cùng held-out dataset.
- [ ] Bổ sung OpenTelemetry/Phoenix tracing.
- [ ] Chỉ thêm MLflow khi có nhiều experiment cần registry/so sánh.
- [ ] Đánh giá OCR/layout parsing trên corpus PDF scan thật.
- [ ] Phân loại permanent PDF validation failures để không retry vô ích; hiện retry vẫn bounded tối đa 4 attempts.
- [ ] Thiết kế recommendation system như một capability riêng, không trộn score
  recommendation vào RAG retrieval trước khi có contract và evaluation riêng.

---

Khi kết thúc ngày, đổi `Status` thành `DONE` chỉ khi Definition of Done đã được
đánh dấu đầy đủ và Evidence ledger có bằng chứng. Nếu còn mục bị chặn, dùng
`PARTIAL — BLOCKED BY <nguyên nhân>` thay vì tuyên bố hoàn thành.

## 12. PostgreSQL technical corpus follow-up (25/10)

Đã chuẩn bị corpus mới riêng với Magi: PDF PostgreSQL 16 Chapter 13, 22 trang,
35 chunk v1, 40 câu chốt trước retrieval (30 dev/10 held-out). Đã upload/index
production: book 3343 / ebook 11 / document 2 / job 2; inventory run 40180403668
khớp source và cả 35 chunk hashes. Magi vẫn giữ nguyên.

User duyệt push/PR, CI và benchmark **retrieval-only trên 30 dev cases** bằng
chế độ `pg16-retrieval-dev`; chưa duyệt merge/deploy hay generation trong bước
này. Không đổi gate/constant của Magi, không mở cổng, không ghép subset cho PASS.
Chưa có retrieval/answer metric PostgreSQL trước khi workflow chạy thật.
Chi tiết: [corpus preparation](evidence/2026-10-25-postgresql16-corpus-preparation.md).
