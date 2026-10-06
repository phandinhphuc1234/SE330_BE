# Vietnamese-source: bounded real embedding baseline

Cập nhật 2026-10-06. **Đã hoàn tất real embedding baseline:** lượt 27 bổ sung
đúng 120 missing inputs, 0 errors, cache đủ 216 vectors và chấm đủ 120
BM25/Dense/Hybrid cases. Lượt 25 lỗi vẫn giữ nguyên để truy vết. Section/ranking
regressions giữ HOLD; mặc định production vẫn v1 và baseline ranking.

## 1. Phạm vi đã được duyệt

Chủ đồ án duyệt 20 câu / 23 evidence anchors / 7 headings trong
[phiếu review](vietnamese-source-review-v1.md), rồi xác nhận riêng cho Gemini
với tối đa **220 input embedding**. Runner kiểm tra
[biên bản owner review](../../tests/fixtures/chunking/vietnamese_source_owner_review_v1.json)
theo source/dataset hashes; receipt không tự cấp quyền provider costs.

Nguồn là báo cáo World Bank dịch sang tiếng Việt, không phải novel gốc Việt.
Review diễn ra sau first BM25 scoring: đây là development/regression data,
không phải independent blind expert review hay reusable blind holdout.
[First-evaluation ledger](vietnamese-source-evaluation-v1.md), sealed manifest
và hai reports trước review giữ nguyên, kể cả trạng thái pending lịch sử.

## 2. Flow và chốt an toàn

[benchmark_vietnamese_embeddings.py](../../scripts/benchmark_vietnamese_embeddings.py):

1. Bắt buộc chọn `--gemini` (quota/cost opt-in) hoặc `--cache-only` (không provider).
2. Kiểm tra output chưa tồn tại, budget <=220, owner receipt và source/policy seals.
3. Kiểm tra checksum PDF, parse/clean/chunk thật cho v1/v2, anchor labels và
   real cache. Cache hỏng làm fail; không đổi thành missing rồi âm thầm gọi API.
4. Khóa public embedding identity / baseline ranking. Key lấy từ `.env`
   của người dùng, không ghi/hiện key trong report hoặc logs.
5. Chỉ gửi exact task/text pairs từ preflight; tính số input **trước** I/O,
   kể cả batch thất bại. Từ lượt 27, batch document tối đa 8 và <=10.000
   reservation units; query riêng; không tự retry. Lượt 25 dùng batch 16.
6. Lưu vector thật thành cache immutable, chạy BM25/Dense/Hybrid bằng pipeline
   thật với exact cosine trong memory, không gọi DB/Qdrant.
7. Kiểm tra lại seals/receipt, ghi NEW JSON/Markdown, gồm cả trạng thái failure
   và counters. Không ghi đè report cũ, không tự promote runtime.

Application retries=0; SDK `HttpRetryOptions(attempts=1)`; timeout 30.000 ms.
SDK retry options đã kiểm tra từ implementation đang cài, không chỉ tắt lớp
retry ngoài. [Google embedding docs](https://ai.google.dev/gemini-api/docs/embeddings)
xác nhận task prefix cho Embedding2 và separate Content objects tạo vector
riêng; không gom nhiều chunks thành một vector. Vector cache kiểm tra dimension,
finite/nonzero values, task/model/version/text-policy identity.

Identity: `gemini-embedding-2`, 3072 dimensions,
`gemini-embedding-2-3072-v1`, `gemini_search_title_text_v1`.
Ranking: dense/keyword 0,7/0,3; RRF k=60; candidate multiplier=3;
reranker RRF/cosine/lexical 0,4/0,5/0,1; query rewrite max=2 (lexical only).
Context 3 seeds, neighbor window=1, budget 12.000 characters; chunks 512/64 tokens.
Không thử thêm fixed/adaptive experimental weights/formulas trong lượt này.

### Token-aware throttle từ lượt 27

Sau khi chủ đồ án cung cấp quota page và duyệt riêng tối đa 120 missing inputs,
đã thêm [embedding_throttle.py](../../scripts/embedding_throttle.py):

- Limiter local theo rolling window 61 giây, tối đa 20.000 reservation units và
  60 request attempts trong window; chờ theo từng đoạn <=30 giây.
- Ước lượng trên **exact provider-facing text**: số UTF-8 bytes +32/input,
  gồm title/task/context prefix. Đây là ước lượng bảo thủ local, **không phải
  exact Gemini tokens, token billing hay công thức chars/4 cho tiếng Anh**.
  Không gọi countTokens API hoặc tải tokenizer ngoài để phục vụ lượt này.
- Mỗi document batch <=8 inputs và <=10.000 estimated units. Chia ở cache layer
  để batch thành công được lưu ngay cả khi batch nhỏ tiếp theo thất bại.
- Token và input reservations được giữ khi request lỗi. Input không đăng ký,
  duplicate submission hoặc vượt cap bị từ chối trước cả throttle/API.
- Cùng Google project có thể còn clients khác; limiter chỉ đo workload local,
  không đảm bảo hết mọi loại quota errors hay chứng minh scalable production.

Quota page người dùng gửi là **peak 28 days**: Embedding2 TPM 32,28K/30K;
RPM -/100, RPD -/1K. Dấu `-` là không có số đo sử dụng, không phải 0. TPM peak
vượt 7,6% là dấu hiệu phù hợp với failure 25, chưa chứng minh chính xác limit
của request đó hoặc trạng thái quota live. Key/project/tier/billing không đổi.
[Google rate-limit docs](https://ai.google.dev/gemini-api/docs/rate-limits)
giải thích giới hạn theo project và nhiều chiều RPM/TPM/RPD;
[token docs](https://ai.google.dev/gemini-api/docs/tokens) phân biệt đếm token
thật với ước lượng. Không dùng 512 chunker tokens làm số Gemini tokens.

## 3. Kết quả thật của lượt 25

Private/ignored report:
`data/chunking-comparison/vietnamese-source-gemini-25.json` và `.md`.

| Chỉ số | Đo được |
| --- | ---: |
| Unique inputs cần cho cả v1/v2 | 216 |
| Document / original query inputs cần | 196/20 |
| Tổng input đã gửi, kể cả batch lỗi | 112 |
| Provider request attempts | 7 |
| Thành công, vector thật đã cache | 96 |
| Input thuộc batch bị lỗi | 16 |
| Cache còn thiếu | 120 = 100 documents + 20 queries |
| BM25 cases đã chấm | 40 |
| Dense / Hybrid cases đã chấm | 0/0 |
| LLM generation / runtime writes | 0/0 |

Sáu batches 16 documents thành công; batch thứ 7 bị lỗi phân loại
`rate_or_quota`. Không có query embedding nào được gửi. Report chỉ lưu error
type/category, không raw SDK error/key. **Chưa xác định** limit RPM,TPM,RPD
hay giới hạn khác: không suy ra từ con số 96. Giới hạn phụ thuộc project/model
và xem tại AI Studio, theo
[Google rate-limit docs](https://ai.google.dev/gemini-api/docs/rate-limits).
Không tự thay key, bật billing hoặc retry để vượt chốt đã thống nhất.

Số input không phải số HTTP requests, số token hay chi phí. Token billing/chi
phí thực tế chưa được đo. 96 vectors cache được giữ lại; không fake 120 vectors
còn thiếu hay chấm subset rồi gọi đó là full baseline.

Đối chiếu với report first-23: **toàn bộ BM25/chunk/source/context/section
audits của cả v1/v2 khớp**, không chỉ macro metrics. Fixture, owner receipt và
hai reports first-23/replay-24 giữ SHA256 nguyên vẹn.
Gates owner review=true, denseHybridCompleted=false,
realEmbeddingCacheReady=false, automaticPromotion=false.

## 4. Chạy và tiếp tục

Runner mới tách riêng, không đổi snapshot provenance của runner BM25 cũ:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_vietnamese_embeddings --gemini --max-embedding-inputs 220 --interval-seconds 2 --output data/chunking-comparison/my-new-attempt.json
~~~

Đây là lệnh có thể tiêu quota/phí, **không chạy lại chỉ vì ví dụ xuất hiện trong
doc**. Cần quyền mới và budget cho lượt tiếp. Mỗi lần phải chọn output chưa có.
`--max-embedding-inputs` giới hạn input còn thiếu được gửi trong lượt đó;
union corpus vẫn có cap riêng 220. Nếu cap nhỏ hơn số missing, fail trước API.
`--cache-only` không lấy key/runtime embedding identity để tạo provider client,
và fail nếu cache thiếu; chỉ dùng để replay full baseline khi đủ vectors thật.
Common app imports có thể khởi tạo app config/SQLAlchemy engine, không phải DB
connection/query; không serialize Settings/secrets hoặc gọi network provider:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_vietnamese_embeddings --cache-only --output data/chunking-comparison/my-new-replay.json
~~~

Tại checkpoint 25, budget cũ 220 đã tính 112 input submitted; còn 108,
trong khi cache thiếu 120.
Vì vậy hoàn tất mà không thêm failure cần tổng ít nhất 232 submitted inputs,
tức **thêm 12 so với budget ban đầu**. Không tự vượt quyền ban đầu: sau khi
xem quota page, user đã duyệt riêng lượt 27 tối đa 120 missing inputs, kể cả
failed submissions. Lượt bổ sung dùng token-aware pacing, không gửi lại 96
inputs có cache và không tự retry. Khi đủ cache, chấm đủ 120 cases = 20
questions × 2 chunkers × 3 modes rồi replay zero-provider và chẩn đoán failures.
Chưa thay strategy/reindex; kết quả lượt bổ sung được ghi riêng bên dưới.

## 5. Verification của checkpoint 25

[Tests](../../tests/unit/test_vietnamese_embedding_benchmark.py) kiểm tra
review/hash/provenance, budget trước I/O, failed inputs vẫn tính budget,
no repeat/unregistered inputs, cache task separation, cả hai retry layers,
missing/corrupt cache, immutable output, double review check, pinned generic
factory, counters khi provider fail và redaction. Unit vectors chỉ là fixtures
test, không được đưa vào cache/report benchmark thật.

Checkpoint 25: narrow related suites **102 passed**; full RAG suite **735 passed,
10 warnings**, 40,30s; tăng 33 tests so với checkpoint 702. Pydantic alias
warnings là warnings cũ. 157 local documentation links hợp lệ, không trailing
whitespace ở scoped files. Cache-only CLI thật từ chối cache thiếu, không tạo
report pass hay gọi provider. Chi tiết trong
[verification ledger](chunking-baseline-and-boundary-plan.md).
Không chạy Spring/Next builds, production load test, answer/judge hay reindex.

## 6. Token-aware completion — lượt 27

User duyệt riêng tối đa 120 missing embedding inputs sau khi xem quota page.
Private/ignored report: `data/chunking-comparison/vietnamese-source-gemini-throttled-27.json`
và `.md`; không ghi đè report 25 hoặc first-evaluation reports.

| Chỉ số | Đo được |
| --- | ---: |
| Cache hợp lệ trước / sau | 96 / 216 vectors |
| Input bổ sung submitted / successful | 120 / 120 |
| Document / original query inputs bổ sung | 100 / 20 |
| Provider request attempts / errors | 37 / 0 |
| BM25 / Dense / Hybrid cases | 40 / 40 / 40 |
| Estimated reservation units tổng | 147.199 |
| Peak local units trong rolling 61 giây | 19.865 / 20.000 |
| Peak request attempts trong window | 21 / 60 |
| Throttle waits / tổng scheduled wait | 35 / 375,91 giây |
| LLM generation / DB-Qdrant-runtime writes | 0 / 0 |

Tổng submitted qua hai lượt là **232 = 112 + 120**, gồm 16 inputs thuộc batch
lỗi lịch sử. 37 request attempts khác 120 input embeddings; 147.199 local
reservation units **không phải actual Gemini/billing tokens**. Chưa đo chi phí,
project-wide traffic hay production latency. Cache hits trong pipeline gồm
nhiều lần đọc lại cùng vector, không phải số unique vectors.

### Retrieval quality trên 20 câu × 2 chunkers × 3 modes

| Chunker | Mode | Evidence Recall@3 | MRR@5 | Context retained anchor rate |
| --- | --- | ---: | ---: | ---: |
| v1 | BM25 | 85% | 0,8375 | 85% |
| v1 | Dense | 90% | 0,8542 | 95% |
| v1 | Hybrid | 92,5% | 0,8792 | 100% |
| v2 | BM25 | 87,5% | 0,8875 | 90% |
| v2 | Dense | 85% | 0,8500 | 90% |
| v2 | Hybrid | 90% | 0,8667 | 95% |

Recall đo anchors trong top-3 seeds; MRR đo vị trí evidence đầu tiên trong top-5;
context retention đo anchors còn lại sau neighboring expansion/budget. Vì vậy
Hybrid v1 context=100% không có nghĩa top-3 đầy đủ hoặc LLM trả lời đúng 100%.
Đây là một translated report và 20 positive questions, không general benchmark.

**Chẩn đoán ban đầu từ actual per-case scores, chưa sửa thuật toán:**

- `vi-poverty-rate`: Dense/Hybrid v1 Recall@3=1, context=1; v2 Recall@3=0,
  MRR@5=0,25, context=0. Đây là regression evidence/context rõ ràng cần trace.
- `vi-high-income-goal`: Dense/Hybrid cả hai Recall@3=0, MRR@5=0,25 nhưng
  context=1. Neighbor expansion giữ được anchor ngoài top-3 seeds; chưa có
  answer/judge score để kết luận trả lời được.
- `vi-not-poor-not-secure`: Dense cả hai Recall/context=0; Hybrid cả hai
  Recall/context=1, nhưng MRR v2 giảm 0,5 → 0,3333.
- `vi-digital-two-domains`: Hybrid Recall@3 v1=0,5, v2=1; context cả hai=1.
  Gain ở một câu không triệt tiêu regressions ở câu khác.
- `vi-mtqg-investment`: Dense MRR v1=1 → v2=0,5 dù Recall/context đều=1.

BM25 và **mọi chunk/source/context/section audit fields** của v1/v2 vẫn khớp
report first-23 toàn bộ. Section mismatches vẫn 97/97 với v1 và 30/94 với v2;
v2 mixed-section chunks=3. Gate owner review, real cache và Dense/Hybrid completion
đã đạt; chapter labels, no-regression và independent blind/held-out validation
chưa đạt. **HOLD v1 + baseline ranking**, không nominate/chấm thêm fixed/adaptive
formulas, sửa labels, đổi weights hoặc reindex production trong lượt này.

### Cache-only replay — lượt 28

`data/chunking-comparison/vietnamese-source-cache-replay-28.json` và `.md`:
**120 cases, 0 errors, 0 submitted inputs, 0 provider calls**, cache vẫn đủ 216.
Đối chiếu exact fields với lượt 27: `dataset`, `config`, `books`,
`firstScoringRegistration`, `ownerReview`, `decision`, `limitations`,
`errorCount`, `caseCountByMode`, `caseCount`, `runtimeWrites`, `llmGeneration`,
`automaticPromotion` đều khớp. Timestamps và operational counters/authorization/
throttle khác có chủ đích; không claim toàn bộ JSON identical.

SHA256 của PDF, raw manifest, owner receipt và reports first-23/replay-24/
failed-25 vẫn nguyên vẹn. Cache/reports/source vẫn private/ignored. Không thay
sealed labels, public runtime settings, `.env`, DB, Qdrant hoặc production.

Verification sau final code changes: full RAG suite **750 passed, 10 warnings**
(57,07s), narrow related suites **117 passed**. 15 tests throttle mới dùng
virtual clock/fake fixtures để kiểm tra weighted/request windows, failed request
reservations, budget trước throttle/API và save-per-successful-batch; không đưa
test vectors vào benchmark cache. Pydantic warnings là warnings cũ. Syntax và
scoped whitespace checks đạt; 158 local documentation links hợp lệ, 14 scoped
text files không trailing whitespace. Không chạy Spring/Next build, production load test
hoặc LLM answer/judge trong lượt này.

Follow-up đã hoàn tất: [source/heading/ranking diagnostics](vietnamese-baseline-diagnostics-v1.md)
trace đủ 40 case-version pairs, replay 120 baseline cases exact, không provider.
23/23 anchors có full single-chunk evidence ở mỗi chunker; v2 chỉ nhận 2/7
reviewed headings và có ranking/cutoff regressions. Bước tiếp theo là experiment
v3 riêng cho section detection, đo source/heading audits trước; Dense/Hybrid
embedding inputs mới cần budget/quyền riêng. Sau đó mở rộng source và
negative/abstention cases review trước scoring; không tune known-case scores.

Flow/purpose: owner review + riêng provider authorization → budget/seals/cache
guards → real embeddings/caching → actual baseline retrieval → NEW report và
HOLD nếu thiếu gate. Mục đích là đo trung thực, tiếp tục được từ cache thật mà
không làm mất dữ liệu hay âm thầm thay production.
