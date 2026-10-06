# Ranking diagnostics và Vietnamese query holdout

Cập nhật: 2026-10-06. Follow-up của
[heading regression fix](heading-detection-regression-fix.md).
**Không đổi runtime ranking, chunk size, default v1 hoặc dữ liệu đã index.**

## 1. Đã implement gì?

- [Diagnostic engine](../../app/evaluation/retrieval_diagnostics.py) giải
  thích điểm BM25 thành TF, DF, IDF, document length và contribution từng
  term. Tổng contribution phải khớp scorer production, nếu drift thì dừng.
  Evidence labels chỉ gắn vào kết quả **sau** ranking, không tham gia scoring.
- [CLI read-only](../../scripts/diagnose_chunking_retrieval.py) chạy cùng
  checksum-pinned PDF/parser/cleaner/chunker; chẩn đoán frozen v1/current v2.
  Dense chỉ đọc real Gemini cache đúng model/dimension/task/input hash;
  thiếu cache thì fail closed, không gọi provider hoặc tạo vector giả.
- Các ablation `original`, query-focused và bỏ scoped book-title tokens chỉ
  chạy offline trong diagnostic report. Không wire vào KeywordRetriever,
  không điều chỉnh RRF/reranker hoặc score threshold.
- [Manifest mới](../../tests/fixtures/chunking/real_books_vi_query_holdout_v1.json):
  **20 câu hỏi tiếng Việt, 22 evidence anchors**, trên hai PDF English cũ.
  Đây là **query-level holdout / cross-lingual retrieval**, không phải sách
  tiếng Việt, corpus mới hay chứng nhận chất lượng toàn library.
- Dataset metadata ghi rõ split/query language/source language/review status.
  Guard pin SHA-256 baseline, từ chối trùng ID/text hoặc source khác; giữ nguyên
  title/chapter labels/metadata/source language, không relabel corpus; sau parse
  từ chối anchor overlap theo source character positions, kể cả câu được
  dịch/reword với quote chỉ lấy một phần đáp án development cũ.
- Runner ghi manifest-content hash và cùng config trước provider calls.
  Không sửa 20 English development questions hoặc HTTP golden set cũ.

## 2. BM25 `birthplace`: vì sao v2 tụt từ hạng 1 xuống hạng 5?

Query development: `Where was Frederick Douglass born?`.

V1 giữ heading tên sách và phần kể nơi sinh trong cùng chunk trang 19.
V2 đã tách title block không gán chương ra riêng, nên chunk chứa đáp án trang
19–20 **không có** token `frederick`/`douglass` trong body. BM25 hiện chỉ
score body, không score metadata title. Một đoạn khác trang 64–65 có các tên
này và lặp `born` hai lần được ưu tiên, dù không chứa đáp án được gán nhãn.

| v2 candidate | Raw BM25 | `born` contribution | `frederick` + `douglass` contribution | `was` contribution |
| --- | ---: | ---: | ---: | ---: |
| Rank 1, trang 64–65, không phải đáp án | 6.4027 | 4.9547 (TF=2) | 1.2006 | 0.2474 |
| Rank 5, trang 19–20, có đáp án | 3.6951 | 3.4493 (TF=1) | 0 | 0.2458 |

Corpus v2 có 128 chunks; DF `born`=3, `frederick`=65, `douglass`=71.
Length normalization cũng thay đổi khi ghép trang; không chỉ số từ khóa.

Offline query-focused variant `frederick douglass born` đưa đáp án v2 lên
**rank 3**. Bỏ book-title terms tiếp (`born`) vẫn rank 3, không tăng thêm.
Đây là một hypothesis check trên development case, **không đủ để promote
query policy**. Dense vẫn tìm đúng đáp án ở rank 1.

## 3. Dense `mother-name`: không mất evidence, hai kết quả gần điểm nhau

| Chunker | Rank 1 cosine / trang | Rank 2 cosine / trang | Margin | Rank của evidence |
| --- | --- | --- | ---: | ---: |
| v1 | 0.755006 / 19 | 0.744222 / 20 | 0.010784 | 1 |
| v2 | 0.764143 / 20–21 | 0.757642 / 19–20 | 0.006501 | 2 |

Chunk đúng của v2 không bị giảm cosine so với v1; competitor có nội dung
gia đình trên trang 20–21 cao hơn một chút. Recall@3 vẫn đầy đủ, MRR giảm
vì đổi thứ tự. Không diễn giải cosine thành xác suất đúng. Chưa có ablation
isolating chunk text vs chapter metadata để khẳng định một yếu tố duy nhất
gây chênh lệch này.

Diagnostic còn cho thấy BM25 `mother-name` yếu ở cả hai versions: token
`mother's`/`douglass's` không match dạng `mother`/tên thường, `name` không
match `named`. V1 evidence rank 86, v2 rank 76; focused variant không còn
match evidence. Possessive/apostrophe normalization và morphology cần
đánh giá riêng, không sửa scorer chỉ để đạt điểm hai câu này.

Report local: `data/chunking-comparison/ranking-diagnostics-07.json`.
Provider calls=0, runtime writes=false; chỉ xuất số đã review trong doc.

## 4. Holdout tiếng Việt: phạm vi và chống leakage

Các câu mới hỏi về facts chưa dùng trong development: thu nhập thay đổi,
giá bán tóc, chất liệu quà, thời gian uốn tóc, tuổi tự ước tính, tên thuyền,
hàng rào phủ hắc ín, học đọc không có giáo viên, di chúc và bò chưa thuần.
Có multi-fact/cross-page/paraphrase/negation; **không có unanswerable cases**,
nên không dùng bộ này để kết luận abstention/hallucination quality.

Review status=`assistant_source_checked`: đã đối chiếu quotes/trang/sự
không trùng anchors bằng code, chưa được người dùng review semantic labels.
Các docs nguồn PDF không đổi. Split này tách **questions/evidence**, không
tách books/documents; các books đã được dùng phát triển trước đó.

Manifest được tạo trước lượt đánh giá đầu và không tune theo ranking mới.
Sau khi đã xem kết quả, nếu dùng nó để sửa pipeline thì nó trở thành
regression/evaluation set; cần tạo một held-out set mới cho quyết định cuối.
Không tuyên bố bộ này vĩnh viễn là blind test.

Report `dataset.manifestContentSha256` là hash JSON model canonical đã validate;
`baselineDatasetSha256` là SHA-256 bytes của manifest development gốc.
Source PDF checksum và model/vector policy vẫn là các cache keys độc lập.
Hash của manifest đầu tiên cũng được khóa trong unit test; fixtures JSON
được pin `eol=lf` trong root `.gitattributes` để byte checksum không drift do
Git `core.autocrlf` trên Windows. Không đổi nội dung baseline/heldout để lấy
điểm đẹp hơn sau khi đã xem kết quả.

Lượt offline `vi-query-holdout-offline-08` đã chạy đủ 40 cases (20 × 2 versions),
errorCount=0. BM25 Recall@3 Magi=0.50 cả hai; Douglass=0.30 cả hai.
Đây là giới hạn lexical cross-language, không phải parser làm mất source.

## 5. Kết quả Gemini và replay

Run `vi-query-holdout-gemini-09`: đủ **120 cases**, errorCount=0, không vượt
context budget. Cùng Gemini model/dimension/version/policy của run 06;
282 unique document/query inputs trong budget 400; 20 provider requests để
embed query mới, document vectors dùng cache. Không gọi LLM generation/judge.

| PDF | Chunker | BM25 Recall@3 | Dense Recall@3 | Hybrid Recall@3 |
| --- | --- | ---: | ---: | ---: |
| Magi | v1 | 50% | 95% | 95% |
| Magi | v2 | 50% | 95% | 95% |
| Douglass | v1 | 30% | 100% | 90% |
| Douglass | v2 | 30% | 100% | **80%** |

Magi có câu cần hai anchors: tìm một trong hai tính 0.5, nên 95% Recall
không đồng nghĩa 19/20 câu trả lời đúng. Không so 95% bộ Việt mới với 80%
bộ English cũ rồi tuyên bố retrieval cải thiện; câu hỏi/facts khác nhau.

Douglass v2 dense MRR@5=1.000/context retention=1.00; hybrid
MRR@5=0.700/context retention=0.80. Hai cases `vi-age-estimate` và
`vi-regular-teacher` có đủ evidence ở dense top-3 nhưng hybrid top-3 bỏ sót.
Đây là dữ liệu cần phân tích fusion/reranker trước khi thay policy; không
mặc định hybrid luôn tốt hơn dense hay tăng RRF weights theo hai câu này.

Replay sau guard/metadata changes:

- `vi-query-holdout-replay-10`: khớp **tất cả retrieval cases/metrics** của
  run 09, provider requests=0.
- `english-baseline-replay-11`: khớp **tất cả retrieval cases/metrics** của
  run 06, provider requests=0. Không có regression do thêm evaluator metadata.
- Mỗi replay đủ 120 cases, errorCount=0; source hashes/coverage giữ nguyên.
- Full RAG suite cuối: **515 passed, 10 warnings**, 27.57s; thêm 24 tests
  diagnostics/holdout/checksum/cache/manifest seal so với mốc 491 trước bước
  này. Verified v1 CLI snapshot vẫn khớp; 107 local doc links không có link hỏng.

Final decision **HOLD v1**, không reindex/deploy. `heldOutVietnameseReviewed`
vẫn false vì chưa có native Vietnamese source/unseen-book/human review;
không biến query-level cross-lingual check thành production quality claim.

## 6. Việc tiếp theo và giới hạn

- Đã thêm [hybrid ranking trace](hybrid-ranking-trace.md): v2 age/teacher từ
  Dense rank 1 xuống RRF/reranker rank 6/7; chưa đổi runtime scoring. Xem
  checkpoint này cho 543-test suite, invariant checks và cache-only replay.
- Tiếp theo chọn policy fusion/reranker có khả năng khái quát trên development
  + regression, rồi validate bằng bộ mới; không tune/test chung heldout đã mở.
- Thử normalization possessive/Unicode/question scaffolding trên development
  + regression suite trước khi quyết định đổi BM25 runtime.
- Cần thêm PDF tiếng Việt được phép sử dụng, human-review evidence và một
  source/document holdout mới cho quyết định promote/reindex có rollback.
- Answer faithfulness, abstention và Qdrant/Postgres production latency chưa
  được đo trong bước này. Default/`.env`/migration/HTTP API không thay đổi.

## 7. Cách chạy

Từ folder `rag-service`, không thay `.env` runtime:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.diagnose_chunking_retrieval --cached-dense --output data/chunking-comparison/my-diagnostics.json
~~~

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_book_chunking --dataset tests/fixtures/chunking/real_books_vi_query_holdout_v1.json --output data/chunking-comparison/my-vi-offline.json
~~~

Gemini là opt-in, có quota/chi phí. Reuse document vectors, query mới cần
embedding thật; pacing không khắc phục quota ngày đã hết:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_book_chunking --dataset tests/fixtures/chunking/real_books_vi_query_holdout_v1.json --gemini --max-embedding-texts 400 --interval-seconds 15 --output data/chunking-comparison/my-vi-gemini.json
~~~

Reports chỉ tạo file mới, không ghi đè. Private PDFs/text/cache/vector reports
ở ignored folder; không serialize Settings/API key hoặc raw provider errors.

Flow: freeze development source/checksum → diagnose ranking bằng scorer/cache
thật → tạo câu hỏi/source anchors độc lập → guard leakage → hai chunkers,
BM25/dense/hybrid → context/citation metrics → report mới, review và HOLD.
Mục đích: hiểu failure modes và đo cross-lingual retrieval trước khi chọn
một thay đổi runtime, không tune/test chung một bộ để lấy số đẹp.
