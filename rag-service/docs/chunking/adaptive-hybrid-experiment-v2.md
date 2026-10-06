# Adaptive hybrid — experiment v2

Preregistered: 2026-10-06, trước lần chạy scoring đầu tiên của experiment này.
Follow-up của [lexical diagnostic](lexical-agreement-diagnostics.md).
Queries/corpus đã được quan sát ở bước trước: đây là **development/regression
experiment, không blind benchmark**. Chưa đổi production, chưa reindex.

## 1. Hypothesis và công thức chốt trước khi chạy

Giảm lexical contribution khi exact-token agreement yếu có giữ được gains Magi
và giảm losses Douglass không? Đây là hypothesis, không phải confidence model.

Tính `q` = IDF-weighted distinct-query-token coverage của **focused BM25 top1**
trên toàn bounded ebook corpus. Focused = variant cuối của QueryRewriter hiện
có; lấy đúng BM25 tokenizer/k1=1.5/b=0.75 và stable rank. DF=0 vẫn thuộc mẫu số.
Không có positive BM25 result thì q=0. Không strip tên/title, detect language,
route theo book/case IDs hoặc dùng evidence/expected answer để tính q.

| Policy | Strength s | Dense/keyword RRF | Reranker RRF/cosine/lexical |
| --- | --- | --- | --- |
| baseline_v1 | Không adaptive | 0.70 / 0.30 | 0.40 / 0.50 / 0.10 |
| idf_fusion_linear_v2 | q | 1−0.30s / 0.30s | Giữ baseline |
| idf_fusion_sqrt_v2 | sqrt(q) | 1−0.30s / 0.30s | Giữ baseline |
| idf_combined_linear_v2 | q | 1−0.30s / 0.30s | 0.40s / 1−0.50s / 0.10s |
| idf_combined_sqrt_v2 | sqrt(q) | 1−0.30s / 0.30s | 0.40s / 1−0.50s / 0.10s |

Căn bậc hai giảm lexical weight nhẹ hơn identity khi 0<q<1; đây là coarse
sensitivity hypothesis, không grid-search hay ngưỡng tune theo cases đã biết.
Combined s=0 là cosine-only reranking trên RRF pool, **không luôn đồng nghĩa
Dense độc lập**. s=1 khôi phục baseline. Original lexical coverage của reranker
không đổi; các weights điều chỉnh theo request, không thay score công khai.

Giữ 20 English + 20 Vietnamese queries trên hai English PDFs, v1/v2 chunkers,
source labels/checksums/cache identity. TopK=5/candidateK=15, RRF k=60, hai keyword
rewrites chia tổng weight; context seed/window/budget=3/1/12000 characters.
Dense/BM25 độc lập là controls. Dự kiến 400 hybrid + 160 control = **560 cases**.

## 2. Review rules chốt trước kết quả

- Baseline phải replay khớp **mọi case/metric/context/citation/cleaned page hash**
  của reports 15/16; thiếu/mismatch nguồn/cache thì dừng, không gọi provider ngầm.
- Giữ v1 no-regression gates: không giảm aggregate Recall@3, MRR@5 hoặc context
  retention ở bất kỳ dataset × book × chunker segment; không giảm Recall@3 ở
  bất kỳ case nào. MRR/context tradeoffs per-case vẫn công khai.
- V2 thêm **strict macro improvement**: candidate bằng baseline hoàn toàn không
  đủ để đề cử. Phải cải thiện ít nhất một macro metric, tolerance 1e-12.
- Eligible candidates xếp theo macro Recall@3, MRR@5, context retention, registry
  order. Không đổi weights/formula/gates sau khi nhìn kết quả. Nếu tất cả fail:
  HOLD baseline; không auto-promote theo một vài câu hỏi hoặc macro gains.
- Nominee chỉ được freeze để validation mới; vẫn cần fresh questions/native
  Vietnamese source có quyền sử dụng + human review trước runtime adoption.

## 3. Implementation boundary

[Policy registry](../../app/evaluation/adaptive_hybrid_experiment.py) chỉ nhận
query/candidate text, không nhận book/title/language/evidence labels. Frozen
policies và numeric decisions; manifest hash bao gồm formulas/review rules.
[Evaluator](../../app/evaluation/chunking_comparison.py) tạo actual production
RetrievalPipeline với settings copy và reranker explicit cho từng offline query.
Không thêm hook, selector, `.env` flag hoặc experimental import vào runtime API.
Default evaluator không adaptive vẫn giữ report format và baseline semantics.

No public metadata chứa q hoặc experimental decisions; chúng chỉ có trong
private offline reports. Các public scores/threshold vẫn là cosine; keyword-only
không được biến thành semantic confidence. Không DB/Qdrant/ingestion writes.

## 4. Validation và kết quả

Section này được cập nhật **sau** preregistration, không đổi formulas/gates.

Manifest seal trước first run:
`0b4a4499d97b6c6fac0e2daa150a5139a114652d20a8e0b5c742665934c0faac`.
Registry/review exports là copies; runner từ chối seal drift. Manifest v1/hash
`c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9` giữ nguyên.

Run `adaptive-hybrid-v2-21`: **560 cases**, errorCount=0, provider calls=0,
runtime writes=false. 400 hybrid (5 policies × 40 questions × 2 chunkers) +
80 Dense và 80 BM25 controls. Baseline của cả 8 segments khớp mọi retrieval
cases/metrics/context/citations và cleaned page hashes của reference 15/16.

Replay `adaptive-hybrid-v2-replay-22`: report bằng run 21 khi bỏ `generatedAt`,
gồm toàn 560 cases, decisions/actual weights, stages, context/citations,
manifest/embedding/source/reference identities và review decision. Không vượt
context budget; 320 adaptive decisions khớp actual trace weights. Không provider
calls. Review decision/hash của experiment v1 run 17 cũng giữ nguyên sau khi
tái dùng helper review cho v2; không ghi đè hoặc sửa reports cũ.

Các số dưới đây là **chunker v2**. Mỗi query set/book có 10 questions;
Recall là source-anchor recall, không phải tỷ lệ câu trả lời LLM đúng.

| Policy | Magi English Recall@3 / MRR@5 | Douglass English Recall@3 / MRR@5 | Douglass Việt Recall@3 / MRR@5 | Đạt gates? |
| --- | --- | --- | --- | --- |
| baseline | 90% / 0.900 | 80% / 0.775 | 80% / 0.700 | Reference |
| fusion linear | 90% / 0.850 | 80% / 0.800 | 100% / 1.000 | Không: Magi MRR giảm |
| fusion sqrt | 90% / 0.850 | 80% / 0.795 | 100% / 0.950 | Không: Magi MRR giảm |
| combined linear | 80% / 0.825 | 100% / 0.900 | 100% / 1.000 | Không: Magi mất anchor/context và MRR giảm |
| combined sqrt | 90% / 0.833 | 100% / 0.833 | 100% / 1.000 | Không: Magi MRR giảm |

Magi queries Việt giữ Recall@3=95%, MRR@5=1.000, context=95% ở mọi policies
và cả v1/v2. Các English Magi regressions cũng xảy ra ở **cả v1/v2**, không
thể tránh bằng cách chỉ giữ chunker v1. JSON/MD có đủ tám segments và controls.

### Macro gains không đủ để promote

| Policy | Macro Recall@3 | Macro MRR@5 | Macro context retention |
| --- | ---: | ---: | ---: |
| baseline | 87.50% | 0.862500 | 89.375% |
| fusion linear | 92.50% | 0.915625 | 96.250% |
| fusion sqrt | 91.875% | 0.908125 | 95.000% |
| combined linear | 93.75% | 0.937500 | 93.750% |
| combined sqrt | 96.25% | 0.916667 | 96.250% |

Cả bốn alternatives có strict macro gains nhưng đều fail no-regression gates;
không phải bị loại chỉ vì gate strict improvement mới. Combined-sqrt có macro
Recall cao nhất nhưng chưa đạt Magi MRR gate. Không nới gates sau kết quả.

### Numeric request decisions và tradeoffs

- `sold-watch`: q=**0.690405**. Combined-linear keyword weight=**0.207121**,
  reranker RRF/cosine/lexical=**0.276162/0.654798/0.069040**. First relevant
  rank **2→4**, Recall@3 **1→0**, MRR@5 **0.5→0.25**, context retention **1→0**.
  Anchor vẫn trong top5, không giống lỗi mất top5 của experiment v1.
- Combined-sqrt giữ `sold-watch` Recall@3=1 nhưng first relevant rank **2→3**,
  MRR@5 **0.5→0.333**. q transformed=0.830906, keyword weight=0.249272.
- `two-sacrifices`: q=**0.413333**. First relevant rank **1→2** ở cả bốn
  alternatives; MRR **1→0.5**, Recall@3 vẫn **0.5**, context vẫn **0.5**.
  Không mất thêm anchor so baseline, nhưng cũng chưa giữ đủ hai anchors.
- `vi-age-estimate`: q=**0.022323**; fusion-linear keyword weight=**0.006697**.
  Evidence từ ngoài top5 lên rank1; fusion-sqrt lên rank2. Cả hai combined
  alternatives lên rank1, giữ đủ anchor/context.
- `vi-regular-teacher`: q=**0.041169**; fusion-linear keyword weight=**0.012351**.
  Cả bốn alternatives giữ evidence ở rank1, Recall/context từ 0→1.

Kết luận: scalar agreement theo request hữu ích với noisy cross-lingual overlap
trên corpus này, nhưng không thay thế semantic relevance/ranking từng candidate.
Một request cần hai anchors vẫn có thể thiếu evidence. Không kết luận đây là
policy tốt cho mọi sách hoặc dùng language/book/case IDs để né regressions.

Decision: **HOLD baseline**, `nomineeForFreshValidation=null`. Không candidate
qua mọi criteria đã chốt; chưa freeze candidate production, không đổi weights,
HTTP contracts, `.env`, source labels, default chunker v1 hoặc index.

## 5. Code, tests và cách chạy lại

- [Runner](../../scripts/compare_adaptive_hybrid.py): sealed-manifest/config/
  provenance/reference/source/cache guards, exact baseline replay, actual pipeline
  với per-query weights, review từng segment/case và NEW private JSON/MD.
- [48 tests mới](../../tests/unit/test_adaptive_hybrid_experiment.py): sealed
  manifest + v1 compatibility; endpoint/partial formulas, DF=0, repeated tokens,
  actual rewriting, no label/ID/metadata routing, settings isolation, cosine
  semantics, actual trace weights, label mutation không đổi choices, no-regression/
  strict-gain rules, bounded corpus, no cache fallback/clobber/secret export.
- Final full RAG suite: **671 passed, 10 warnings**, 13.93s. Warnings là các
  Pydantic alias warnings cũ. Reviewed v1 snapshot **11 cases** vẫn khớp.

Từ `rag-service`:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.compare_adaptive_hybrid --output data/chunking-comparison/my-adaptive-comparison.json
~~~

Defaults đọc source PDFs/cache cùng reference reports 15/16. Có thể truyền
`--pdf-dir`, `--english-reference`, `--vi-reference`. Thiếu cache, seal/checksum
hoặc baseline mismatch thì dừng; không gọi provider hoặc tự fake vectors.
Các quyết định numeric nằm ở `rankingPolicyDecision`; actual effective weights
nằm ở `rankingPolicyTraceParameters`. Không publish report/source IDs tự động.

## 6. Hướng tiếp theo và giới hạn

Tạm dừng weight/formula tuning trên bộ đã biết này. Đừng thử thêm thresholds để
làm hai failures biến mất rồi gọi đó là blind validation. Cần mở rộng source-native
Vietnamese PDF có quyền sử dụng + fresh queries và human review; benchmark baseline,
inspect candidate relevance/multi-evidence cutoffs trước khi chọn hướng reranker mới.

Follow-up đã thực hiện: [Vietnamese-source first evaluation](vietnamese-source-evaluation-v1.md)
thêm một **báo cáo được dịch sang tiếng Việt**, 20 câu/23 anchors khóa trước
40 BM25 cases + exact replay. Chủ đồ án đã duyệt toàn bộ checklist ngày
2026-10-06 sau first scoring; biên bản riêng theo hash, không sửa historical
reports hoặc claim blind review. [Bounded embedding baseline](vietnamese-source-embedding-baseline-v1.md)
giữ 96 real vectors từ lượt lỗi, rồi bổ sung đủ 120 missing inputs theo quyền
riêng với token-aware pacing: 216 cached vectors, 120 BM25/Dense/Hybrid cases,
0 errors. Hybrid Recall@3 v1/v2 là 92,5%/90%, vẫn HOLD vì regressions và section
gaps. Không phải novel gốc tiếng Việt. Formulas/seals này giữ
nguyên, chưa được chấm trên source mới; không tuning dựa trên điểm đã quan sát.
[Baseline diagnosis](vietnamese-baseline-diagnostics-v1.md) tiếp theo đã trace
40 Vietnamese case-version pairs, 120 exact replay cases và không gọi provider:
source anchors còn nguyên, v2 nhận 2/7 headings, cùng evidence cosine có thể
tụt rank vì corpus đổi. Đây không phải adaptive experiment mới hoặc quyền tune
formula/weights; ưu tiên isolated section-detection version trước.

Đây không phải rollout sửa production. Không đo live Postgres/Qdrant latency,
LLM faithfulness/abstention, native Vietnamese source quality hoặc chi phí runtime.
Profile toàn scoped corpus thêm công việc offline; chưa chứng minh scalable trên
corpus lớn. Heuristic reranker vẫn không phải cross-encoder/LLM reranker.

Flow/purpose: khóa formulas/gates → guard source/cache/reference → replay baseline
→ dùng query/corpus text quyết định weights → actual retrieval/context → kiểm tra
mọi group và case → HOLD hoặc nominee cho fresh validation. Mục đích là kiểm tra
hypothesis adaptive một cách tái lập, không điều chỉnh đáp án/weights để đạt số đẹp.
