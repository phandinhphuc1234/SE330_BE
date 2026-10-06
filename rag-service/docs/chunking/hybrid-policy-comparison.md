# Hybrid policy comparison — preregistered offline experiment

Cập nhật: 2026-10-06. Follow-up của [hybrid ranking trace](hybrid-ranking-trace.md).

## 1. Chốt experiment trước khi chạy

Đây là development/regression comparison, **không phải blind test**. Giữ nguyên
hai English PDFs, 20 English development queries và 20 Vietnamese regression
queries, evidence/source labels, chunkers v1/v2, cache identity và settings.
Không bổ sung điều kiện theo case ID, đáp án, ngôn ngữ hoặc tên sách vào scorer.

Chỉ thử năm cấu hình cố định dưới đây; không grid-search hoặc đổi weights sau
khi xem kết quả. Chúng là hypothesis/ablation, không phải production standard.

| Policy ID | Dense/keyword RRF weights | Reranker RRF/cosine/lexical weights | Mục đích |
| --- | --- | --- | --- |
| `baseline_v1` | 0.70 / 0.30 | 0.40 / 0.50 / 0.10 | Runtime baseline hiện tại |
| `fusion_dense_85_v1` | 0.85 / 0.15 | 0.40 / 0.50 / 0.10 | Cô lập ảnh hưởng fusion weights |
| `rerank_semantic_80_v1` | 0.70 / 0.30 | 0.15 / 0.80 / 0.05 | Cô lập ảnh hưởng reranker weights |
| `combined_semantic_80_v1` | 0.85 / 0.15 | 0.15 / 0.80 / 0.05 | Kết hợp hai thay đổi trên |
| `cosine_only_ablation_v1` | 0.70 / 0.30 | 0 / 1 / 0 | Đối chứng: cosine trên pool sau RRF |

Dense và BM25 độc lập là controls; cosine-only sau RRF **không đồng nghĩa**
Dense độc lập vì candidate truncation có thể đã loại một chunk trước reranker.
RRF k=60; hai keyword rewrites vẫn chia tổng keyword weight, không nhân đôi.

Giữ request topK=5/candidateK=15, metrics @1/3/5, context seeds=3,
context expansion window=1 và budget=12000 characters. Các public scores/
threshold vẫn là cosine; keyword-only không được coi là semantic confidence.

## 2. Tiêu chí review được chốt trước kết quả

- Replay baseline phải khớp **mọi retrieval case/metric/context/citation** của
  reports trước; source/manifest/embedding identity phải khớp, provider calls=0.
- Báo cáo riêng từng dataset × book × chunker (8 segments), không chỉ macro.
- Candidate được đề cử cho validation mới nếu không giảm Recall@3, MRR@5 hoặc
  context-retained-anchor rate ở bất kỳ segment nào; đồng thời không giảm
  Recall@3 ở bất kỳ case nào so với baseline. MRR/context per-case tradeoffs
  vẫn phải liệt kê. Không tự đổi tiêu chí nếu không candidate nào đạt.
- Xếp eligible candidates theo macro Recall@3, MRR@5, context retention; nếu
  bằng nhau ưu tiên cấu hình xuất hiện trước trong bảng (thay đổi đơn giản hơn).
- Có candidate tốt hơn trên bộ này chỉ là **nomination**, không auto-promote.
  Freeze policy identity rồi cần fresh questions/native Vietnamese source và
  human review trước khi dùng runtime hoặc reindex.

## 3. Đã implement và chạy gì?

- [Policy registry/review](../../app/evaluation/hybrid_policy_comparison.py):
  frozen dataclasses, manifest hash, coarse hypotheses và tiêu chí ở trên.
  Review từng segment/case, ghi cả losses lẫn gains; không dùng labels để score.
- [Runner](../../scripts/compare_hybrid_policies.py): guard manifest/reference/
  embedding identity/context/source checksums trước experiment, yêu cầu đủ
  cached document/query vectors, replay baseline rồi dùng cùng production
  RetrievalPipeline và context builder cho từng policy. JSON/MD chỉ tạo mới.
- [Reranker](../../app/retrieval/reranker.py) nhận weights qua constructor phục
  vụ explicit dependency injection. Defaults vẫn **0.40/0.50/0.10**, không có
  `.env`/HTTP selector mới; runtime factories không inject candidate weights.
  Trace ghi đúng weights được inject và diagnostic kiểm tra contribution sums.
- [Evaluator](../../app/evaluation/chunking_comparison.py) thêm optional
  reranker injection và stage summaries; mặc định không thêm diagnostic fields
  nên old benchmark response/report cases vẫn tái lập được.
- [40 tests mới](../../tests/unit/test_hybrid_policy_comparison.py): weights
  validation, manifest seal/export isolation, default compatibility,
  score/non-mutation, trace contribution, settings isolation, per-case và
  per-segment review gates, unfair case lists, reference drift, missing cache,
  no provider fallback và không ghi đè output.

Experiment hash được chốt trước khi xem ranking mới:

`c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9`.

Run `hybrid-policy-comparison-17`: **560 cases**, errorCount=0, provider calls=0.
Gồm 400 hybrid cases (5 policies × 40 queries × 2 chunkers), 80 Dense và 80
BM25 controls. 8 segments: 2 query sets × 2 books × 2 chunkers. Baseline của
cả 8 segments khớp từng retrieval case/metric/context/citation của reports
15/16; không chỉ khớp average. Không vượt context budget.

Replay `hybrid-policy-comparison-replay-18`: toàn bộ 560 cases, stage summaries,
contexts, citations, manifest và review decision **bằng run 17**. Không gọi
Gemini hoặc LLM generation/judge; các reports/PDF/cache private được ignore.

Full RAG suite cuối: **583 passed, 10 warnings**, 13.28s; tăng 40 tests so với
543 ở bước trace. Warnings là Pydantic alias warnings cũ. Runtime config,
HTTP contracts, query rewrites, chunking default và source labels không đổi.

## 4. Kết quả quan trọng

Các số dưới đây là **v2**; report private có bảng đủ 8 segments, v1 và controls.
Recall@3 là source-anchor recall, không phải % câu trả lời LLM đúng.

| Policy | Magi English Recall@3 / MRR@5 | Douglass English Recall@3 | Douglass queries Việt Recall@3 / MRR@5 | Đạt gates? |
| --- | --- | ---: | --- | --- |
| baseline | 90% / 0.900 | 80% | 80% / 0.700 | Reference |
| fusion Dense 85 | 90% / 0.850 | 80% | 90% / 0.853 | Không: Magi MRR giảm |
| reranker cosine 80 | 80% / 0.850 | 90% | 100% / 0.850 | Không: Magi mất evidence |
| combined | 80% / 0.800 | 90% | 100% / 1.000 | Không: Magi mất evidence |
| cosine-only ablation | 80% / 0.783 | 100% | 100% / 1.000 | Không: Magi mất evidence |

Magi queries Việt giữ Recall@3=95% và MRR@5=1.000 ở mọi policy/v1/v2.
Không so bộ Việt với bộ English rồi kết luận language là nguyên nhân duy nhất:
questions/facts khác nhau. English Magi và English Douglass cũng phản ứng khác.

### Tại sao chưa chọn policy mới?

- `fusion_dense_85_v1` có macro Recall@3 tăng 87.5% → 90.625%, nhưng English
  Magi (cả v1/v2) MRR@5 giảm 0.900 → 0.850. Case `two-sacrifices` relevant
  evidence đầu tiên chuyển rank 1 → 2; Recall@3 vẫn 0.5, không mất thêm anchor.
- Các policies ưu tiên cosine làm English Magi `sold-watch` mất anchor ở
  top-3/top-5. Với `rerank_semantic_80_v1`, evidence từ baseline rank **2**
  xuống reranker rank **6** (cả v1/v2). Context retention của case từ 1 → 0.
  Đổi weights chỉ để cứu hai Vietnamese Douglass cases sẽ che regression này.
- `combined_semantic_80_v1` đưa đúng evidence của cả `vi-age-estimate` và
  `vi-regular-teacher` v2 lên rank 1 và giữ context; nhưng không đạt Magi gates.
- Không ghi nhận giảm full-anchor pool recall do cắt RRF union → candidateK
  trong 400 hybrid cases này. Điều này không chứng minh mọi corpus đều không
  bị eviction; các seed/context cutoffs và ranking vẫn ảnh hưởng kết quả.

Decision: **không candidate nào đạt toàn bộ tiêu chí đã preregister**.
`nomineeForFreshValidation=null`; giữ runtime `baseline_v1`, default chunker v1.
Không tự nới gates, không chọn policy chỉ vì có macro score cao nhất, và không
nói rằng các policy thử nghiệm chắc chắn kém trên dữ liệu ngoài corpus này.

## 5. Cách chạy lại

Từ `rag-service`; không cần thêm key vì runner chỉ đọc cache thật có sẵn:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.compare_hybrid_policies --output data/chunking-comparison/my-policy-comparison.json
~~~

Defaults tham chiếu `hybrid-trace-english-replay-16.json` và
`hybrid-trace-vi-replay-15.json` trong ignored data folder. Nếu setup ở máy khác,
phải có PDFs/checksums/vectors và reference reports tương ứng; thiếu thì dừng,
không thay bằng vectors giả hoặc gọi provider ngầm. Có thể truyền
`--english-reference`, `--vi-reference`, `--pdf-dir` với artifacts đã kiểm chứng.

JSON/MD chứa source IDs/citations/metrics nên không publish tự động. Đọc
`decision.reviews[].regressions` và `caseChanges`, rồi từng segment/case;
không chỉ nhìn `macro`. Không report raw Settings, key, question hoặc source text.

## 6. Bước tiếp theo

Follow-up [adaptive experiment v2](adaptive-hybrid-experiment-v2.md) đã chốt và
chạy bốn query-level alternatives riêng; không sửa manifest v1. 560 cases có
macro gains nhưng vẫn fail Magi gates, giữ HOLD. Chuyển sang source/query mới
và human review; các items dưới đây giữ lịch sử định hướng của bước v1.

1. [Lexical-agreement diagnosis](lexical-agreement-diagnostics.md) đã chạy toàn
   bộ 80 cases, kiểm tra term contributions và giữ cả BM25 score=0 evidence.
   `sold-watch` được lexical match hữu ích; Douglass có title/name-only và
   name/context overlap failures. Title removal/coverage riêng lẻ chưa đủ.
2. Nếu đề xuất policy mới, chốt hypothesis/criteria thành **experiment v2**
   trước khi chạy. Không mở rộng grid-search trong manifest v1 hoặc thêm
   condition theo IDs/books/expected answers. Chưa implement v2 trong bước này.
3. Khi có candidate đủ cơ sở, freeze nó và validate fresh questions/native
   Vietnamese source có quyền sử dụng + human review. Chỉ sau review mới cân
   nhắc runtime adoption/reindex/rollback. Không tự chuyển default v2.

Giới hạn: reranker đang là deterministic score-combination heuristic, **không
phải cross-encoder/LLM reranker**. Cosine-only sau RRF không phải bằng chứng
Dense luôn tốt nhất. Chưa đo answer faithfulness, abstention hoặc live DB/vector
latency. Đây là comparison có tradeoffs được ghi lại, không phải production fix
đã triển khai hoặc blind-benchmark chứng minh policy cuối cùng.

Flow/purpose: freeze policies và review rules → validate reference/source/cache
→ replay baseline → cùng source/budgets, policy chọn candidates/context → review mọi group
và case → quyết định HOLD hoặc nominee cho validation mới. Mục đích là kiểm tra
thay đổi có khái quát hơn hai câu lỗi đã biết, không đổi production để lấy số đẹp.
