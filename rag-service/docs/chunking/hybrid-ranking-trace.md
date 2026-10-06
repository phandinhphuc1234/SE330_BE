# Hybrid ranking trace — evidence mất thứ hạng ở đâu?

Cập nhật: 2026-10-06. Bước 1 của follow-up
[ranking diagnostics/query holdout](retrieval-diagnostics-and-vi-query-holdout.md).

**Đã thêm trace và chạy chẩn đoán, chưa sửa ranking policy.** Không đổi weights,
reranker, query rewriting, threshold, HTTP schema, default v1, `.env` hoặc index.
Không commit/push/deploy; không gọi Gemini, generation/judge hoặc ghi runtime DB.

## 1. Đã thêm gì?

- [RankingTrace](../../app/retrieval/ranking_trace.py): collector riêng cho từng
  request, bật khi code gọi `pipeline.search(request, trace=collector)`.
  HTTP endpoints không nhận hoặc trả trace; không có global flag/log mới.
- [Pipeline](../../app/retrieval/retrieval_pipeline.py) ghi snapshots theo thứ tự:
  Dense candidates, BM25 của từng rewrite, RRF toàn union, RRF sau candidate
  limit, reranker, cosine threshold, final top-k **seeds trước context expansion**.
- [RRF](../../app/retrieval/hybrid_retriever.py) ghi contribution ngay tại vòng
  scoring thật: branch, rank, weight và `weight / (k + rank)`. Snapshot trước
  candidate limit giúp phân biệt eviction với reranker demotion. Không thêm
  field trace vào metadata kết quả công khai.
- [Evidence annotation](../../app/evaluation/retrieval_diagnostics.py) chỉ đọc
  collector **sau khi pipeline đã chạy**. Labels không ảnh hưởng scoring.
  Tổng contribution RRF/reranker phải khớp điểm thật, nếu drift thì dừng.
- [CLI](../../scripts/trace_hybrid_retrieval.py) dùng đúng parser/cleaner/chunker,
  scoped KeywordRetriever và RetrievalPipeline; dense là exact cosine trên
  Gemini vectors thật đã cache. Thiếu cache fail closed, không gọi provider.
  Mỗi case chạy có/không trace và phải có response bằng nhau hoàn toàn.
- [28 tests](../../tests/unit/test_ranking_trace.py) kiểm tra non-mutation,
  output invariance, split keyword weights/dedup/graph/empty union, threshold,
  lexical outage/fail-closed, context, privacy, evidence-label isolation,
  formula drift, cache-only và guards trước I/O.

Collector chỉ lưu chunk IDs, physical page numbers và numeric scores; không lưu
raw query, rewrite text, chunk text, quote, arbitrary metadata hoặc Settings.
Report vẫn có source identifiers nên giữ private trong ignored data folder.
`score` của BM25 snapshot là normalized BM25, `bm25RawScore` là raw BM25;
`score` của Dense/RRF/reranker là cosine, không phải xác suất câu trả lời đúng.

## 2. Cấu hình chẩn đoán phải khớp benchmark

Benchmark trước đo Recall@1/3/5 từ một request `topK=5`, không chạy một request
riêng `topK=3`. Do đó trace mặc định cũng `topK=5`, multiplier=3,
**candidateK=15**; sau ranking đo riêng top 3. Thay topK=3 làm candidateK=9,
không được so như cùng một experiment.

Policy hiện tại không đổi: vector weight=0.7, tổng keyword weight=0.3,
RRF k=60, 2 rewrite sets nên mỗi set có weight=0.15. Reranker heuristic:

`0.40 × normalized RRF + 0.50 × cosine + 0.10 × lexical coverage`.

Evidence relevance dùng đúng >=50% ít nhất một source anchor như evaluator MRR.
Evidence Recall yêu cầu toàn anchor; report ghi cả partial source coverage và
union-of-candidates recall. Không đánh đồng relevant rank với answer accuracy.

## 3. Kết quả hai câu bị thất bại

Run local: `data/chunking-comparison/hybrid-ranking-trace-12.json`, 4 traces,
provider calls=0, runtime writes=false. Cùng manifest/source/cache với run 09/10.

| Case | Chunker | Dense rank | BM25 rank (cả 2 rewrites) | RRF rank | Reranker rank | Có trong final top-5? |
| --- | --- | ---: | --- | ---: | ---: | --- |
| `vi-age-estimate` | v1 | 1 | 11 | 2 | 1 | Có |
| `vi-age-estimate` | v2 | 1 | Không trong top-15 | 6 | 6 | Không |
| `vi-regular-teacher` | v1 | 1 | Không trong top-15 | 6 | 6 | Không |
| `vi-regular-teacher` | v2 | 1 | Không trong top-15 | 7 | 7 | Không |

Các đoạn đúng ở v2 vẫn nằm trong RRF candidateK=15. **Không bị candidate limit
loại trước reranker, không bị threshold loại** (threshold=None). Sự tụt hạng
top-3 đã bắt đầu ở RRF; reranker không phục hồi. Cắt final top-5 mới loại hẳn
evidence khỏi response. Failure `vi-regular-teacher` cũng đã tồn tại ở v1.

### Điểm thật ở v2

| Case / candidate | Trang PDF | Cosine | Normalized RRF | Lexical coverage | Reranker score |
| --- | --- | ---: | ---: | ---: | ---: |
| age: đoạn đúng, rank 6 | 19–20 | 0.754841 | 0.711475 | 0 | 0.662011 |
| age: competitor cuối rank 1 | 4 | 0.709495 | 1.000000 | 0.153846 | 0.770132 |
| teacher: đoạn đúng, rank 7 | 50–51 | 0.740656 | 0.776563 | 0.111111 | 0.692064 |
| teacher: competitor cuối rank 1 | 66–67 | 0.701091 | 0.999462 | 0.111111 | 0.761441 |

Hai đoạn đúng chỉ có RRF contribution từ Dense rank 1: `0.7 / 61 = 0.01147541`;
không có contribution từ hai BM25 top-15. Các competitors được cộng điểm
agreement từ keyword rankings, dù không chứa source anchor được gán nhãn.
BM25 khớp tokens, không dịch câu hỏi Việt sang nội dung English.

Với age: đoạn đúng được lợi cosine trong reranker khoảng **+0.022673**, nhưng
bất lợi phần RRF khoảng **-0.115410** và lexical khoảng **-0.015385** so với
competitor rank 1. Vì vậy cosine cao hơn không đủ để vượt competitor.
Teacher có lexical coverage bằng nhau; lợi cosine khoảng **+0.019783** nhỏ
hơn bất lợi RRF khoảng **-0.089160**. Đây là contribution quan sát được của
policy hiện tại, chưa phải bằng chứng rằng một bộ weights mới sẽ khái quát tốt.

`firstTop3LossStage` là điểm giảm Recall@3 đầu tiên khi final recall thấp hơn
Dense; luôn xem cả rank path và metrics của các stages sau, vì một stage sau
có thể phục hồi một lần tụt hạng trước đó. Không coi nó là causal root-cause
oracle, cũng không quy regression này cho parser/chunker mất source.

## 4. Kiểm chứng và giới hạn

- `hybrid-vi-all-trace-13`: **40 traces** (20 queries × 2 chunkers).
- `hybrid-english-all-trace-14`: **40 traces** trên bộ English cũ.
- Cả 80 response có/không trace bằng nhau, không provider calls. Final hybrid
  Recall@3 từng case khớp reports cũ, không chỉ khớp aggregate.
- Cache-only full benchmark replay, provider factory bị vô hiệu hóa:
  `hybrid-trace-vi-replay-15` có **120 cases** khớp run 10;
  `hybrid-trace-english-replay-16` có **120 cases** khớp run 11.
  So toàn bộ retrieval cases/metrics/context/citations; requestCount=0.
- Full RAG suite: **543 passed, 10 warnings**, 31.72s; thêm 28 tests so với
  mốc 515. Warnings là Pydantic alias warnings cũ, không phải trace failures.
- Không thêm hoặc sửa labels/questions/quotes/checksums của datasets. Bộ Việt
  đã mở kết quả là regression set nếu dùng để chỉnh policy, không còn blind.
- Đây là cached exact-cosine/offline lexical experiment, không đo Qdrant/
  PostgreSQL production latency, answer faithfulness hay abstention quality.

## 5. Cách chạy và đọc report

Từ `rag-service`, chỉ cần cache thật có sẵn; không điền key mới hoặc gọi provider:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.trace_hybrid_retrieval --output data/chunking-comparison/my-hybrid-trace.json
~~~

Toàn bộ queries Việt:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.trace_hybrid_retrieval --book-id all --case-ids all --output data/chunking-comparison/my-vi-traces.json
~~~

Toàn bộ English development queries:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.trace_hybrid_retrieval --dataset tests/fixtures/chunking/real_books_v1.json --book-id all --case-ids all --output data/chunking-comparison/my-en-traces.json
~~~

Mỗi output phải là file mới. Đọc `stageSummary` trước, sau đó
`evidenceRankPath`, `rrfContributions` và rows trong `reranked_candidates`.
`null` rank nghĩa là đoạn không nằm trong tập results quan sát được ở stage đó,
không có nghĩa toàn corpus không có evidence. Chưa trace toàn BM25 corpus.

## 6. Follow-up

[Policy comparison](hybrid-policy-comparison.md) đã chạy 560 cases + replay:
cosine-heavy cứu Douglass Việt nhưng làm mất English Magi evidence; fusion-only
cũng có MRR tradeoff. Chưa candidate nào đạt gates, giữ baseline/v1. Mốc mới:
583 tests; tham số runtime mặc định không đổi. Số 543 phía trên là checkpoint
riêng của bước trace, không phải full-suite count mới nhất.

1. Phân tích tiếp lexical agreement hữu ích vs weak token overlap, chốt
   experiment v2 nếu cần; không tune thêm vào manifest comparison v1.
2. Freeze policy đã chọn, dùng **bộ câu hỏi mới** và PDF tiếng Việt có quyền sử
   dụng; human-review anchors. Không tune tiếp trên bộ blind vừa mở.
3. Chỉ sau validation/promotion review mới đổi runtime default/reindex, có
   rollback. Hiện vẫn **HOLD v1**, không tự động promote.

Flow/purpose: trace các ranks/contributions tại pipeline thật → kiểm tra không
đổi response → gắn source labels sau scoring → tìm evidence tụt ở đâu → replay
baseline → ghi checkpoint. Mục đích là phân biệt retrieval/ranking/cutoff failures
trước một thay đổi policy, không tạo thêm tính năng hoặc đổi production.
