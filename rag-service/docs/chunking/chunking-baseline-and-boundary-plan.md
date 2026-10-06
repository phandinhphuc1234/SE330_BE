# Chunking baseline và kế hoạch sửa ranh giới trang/chương

Cập nhật: 2026-10-06.

## 1. Phạm vi lượt implementation này

**Bước 1–3 đã được implement:** baseline v1 giữ nguyên, cùng strategy
chapter-aware/source-mapped v2 opt-in. CLI có thể chọn v1/v2, vẫn pin size/
overlap 512/64. Chi tiết bước 2: [Chapter-aware v2](chapter-aware-v2.md).
Chi tiết bước 3: [Context expansion theo chương/phiên bản](chapter-aware-context-expansion.md).
**Bước 4 đã có runner và benchmark PDF thật:**
[kết quả/gates](real-book-chunking-benchmark.md). Quyết định HOLD default v1;
heading detection có lỗi trên sách thật, chưa promote v2/reindex.

[Follow-up heading fix](heading-detection-regression-fix.md) đã sửa nhánh v2
và rerun: chapter labels pass; retrieval regressions/held-out gate vẫn HOLD.
[Ranking diagnostics và query holdout](retrieval-diagnostics-and-vi-query-holdout.md)
giải thích failure modes và thêm câu hỏi Việt/evidence mới; source vẫn English,
runtime ranking và default v1 chưa thay đổi.

Đã thêm nhánh code ingestion và config flag, nhưng mặc định vẫn v1. Chưa sửa
`.env` thật; provider chỉ gọi trong local opt-in benchmark. Chưa chạy runtime
reindex/migration/deploy.

Các mốc cũ về search/embedding/evaluation trong những doc chunking dài là
lịch sử. Trạng thái retrieval hiện tại nằm ở
[implementation ledger](../retrieval-evaluation-implementation.md).

## 2. Vì sao cần baseline trước khi sửa?

Baseline lưu **hành vi hiện tại**, kể cả các giới hạn đã biết, để có thể so sánh
với v2 mà không mất v1. Test characterization pass chỉ có nghĩa "v1 vẫn hoạt
động như mốc đã kiểm tra"; không có nghĩa ranh giới chương/trang đã tốt.

ChunkQualityValidator PASS kiểm tra an toàn/metadata/độ dài/duplicate, chưa
chứng minh tính đúng đắn semantic hoặc mọi nhãn chương.

## 3. Bộ dữ liệu

[baseline_cases.json](../../tests/fixtures/chunking/baseline_cases.json) chứa
11 tình huống tự viết, không lấy nội dung sách có bản quyền từ repo tham khảo.
Input là **parsed pages giả lập**, không phải PDF đã chạy parser thật.

| Case | Điều cần quan sát |
| --- | --- |
| vi_mid_page_chapter | Chương mới ở giữa trang; v1 gán chương mới cả phần nội dung cũ. |
| en_mid_page_chapter | Heading sau tám dòng không rỗng; v1 bỏ sót, carry-forward chương cũ. |
| vi_two_chapters_one_page | Hai chương trong một chunk, metadata chỉ giữ chương đầu. |
| en_two_chapters_one_page | Cùng tình huống trên với tiếng Anh. |
| vi_cross_page_sentence | Hai nửa câu ở hai trang vẫn là hai chunks riêng. |
| en_cross_page_sentence | Cùng tình huống trên với tiếng Anh. |
| vi_no_chapter_heading | Không bịa chương; giữ dấu tiếng Việt, dấu ngoặc kép và citation. |
| en_no_chapter_heading | Không bịa chương; giữ hội thoại và citation. |
| vi_chapter_carry_forward | Heading đầu trang được nhận diện và carry-forward đúng trang nguồn. |
| en_chapter_carry_forward | Cùng tình huống trên với tiếng Anh. |
| en_long_page_overlap | Trang đủ dài để thực sự split; kiểm tra phần text overlap. |

Các IDs document=7, book=101, ebook=55 là synthetic identifiers cho test,
không tra cứu hoặc tạo record trong database.

## 4. Nó chạy qua code nào?

CLI dùng đúng hàm đồng bộ IngestionPipeline._build_chunk_result. Nhánh v1:

~~~text
Synthetic ParsedDocument pages
  → document adapter
  → PdfCleaningTransformation / PdfCleaner
  → DocumentProfiler
  → ChapterDetector
  → LlamaSentenceChunkingStrategy v1 / SentenceSplitter
  → internal Chunk + token counts + quality validator
  → citation projection của internal retrieval
  → report/snapshot local
~~~

Cleaning, profiling, chapter detection, splitting, metadata, hashing và
citation projection chạy thật. Chỉ vô hiệu hóa các dependency không thuộc
bước này: PDF parser registry và artifact/storage writer. Không gọi
IngestionPipeline.run, database session, embedding hoặc Qdrant upsert.

v2 thay page-wide ChapterDetector bằng boundary detection/source mapping
trong strategy; cleaned page artifacts giữ nguyên hình dạng trang.

Config được pin ở CLI, không lấy CHUNK_SIZE/cleaning flags từ .env. Các patch
chỉ sống trong context của tool kiểm thử, không sửa cached application settings.
Report chỉ giữ whitelist metadata, không serialize Settings/secrets.

Snapshot [expected_v1.json](../../tests/fixtures/chunking/expected_v1.json)
ghi text trước/sau cleaning, text chunks, hashes/vector IDs, metadata chương/
trang, citation và quality report. Không ghi random LlamaIndex node IDs hoặc
timestamp để hai lần chạy có thể so sánh.

knownLimitations là chú thích của fixtures và có các characterization tests
riêng xác nhận; đây không phải điểm số tự động về chất lượng.

## 5. Cách chạy

Từ folder rag-service, dùng venv đã cài dependencies:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --check
~~~

Muốn xem toàn bộ JSON:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --json
~~~

Muốn tạo report ở một đường dẫn mới:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --output .\data\chunking-baseline\v1-local.json
~~~

Thử v2 trên cùng fixtures (không thay config đang chạy):

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --strategy v2 --json
~~~

v1 dùng reviewed snapshot; v2 dùng acceptance tests và chưa có reviewed
snapshot, nên không dùng `--strategy v2 --check`.

CLI dùng UTF-8 để giữ text tiếng Việt trên Windows, và không overwrite output
đã tồn tại. Fixtures mặc định là synthetic; khi dùng private fixtures, report
có thể chứa text nguồn, không đưa report đó lên GitHub.

Chạy tests:

~~~powershell
.\.venv\Scripts\python.exe -m pytest tests/unit/test_chunking_baseline.py -q
~~~

Flow của CLI: load/validate fixtures → chạy nhánh chunking v1/v2 đã chọn → dựng report →
in summary/JSON, tạo file mới hoặc so snapshot → trả exit code 0/1.
Mục đích là phát hiện drift và giữ mốc so sánh, không xử lý upload production.

## 6. Nguyên tắc review snapshot

- Không tự regenerate snapshot chỉ để làm test xanh.
- Nếu đổi dependency/cleaner/v1, đọc diff text và provenance trước khi duyệt.
- Giữ fixture inputs và v1 snapshot khi thêm v2; v2 dùng report/expectations riêng.
- 512/64 là token budget của SentenceSplitter, không phải ký tự.
- metadata.token_count vẫn là ApproxTokenCounter, không phải tokenizer Gemini.
- Bộ synthetic này chưa chứng minh retrieval/answer chất lượng trên sách thật.
- Nếu tokenizer resources chưa được cài/cache, cần chuẩn bị dependencies trước;
  baseline không yêu cầu key/provider hoặc database đang chạy.

## 7. Kế hoạch tiếp theo

| Bước | Việc | Trạng thái |
| --- | --- | --- |
| 1 | Baseline v1, fixtures, snapshot, tests và CLI | Hoàn thành, 31 tests mới đã qua |
| 2 | Strategy v2: chapter/section boundaries, câu qua trang và source mapping | Đã implement opt-in; chưa đổi default/reindex |
| 3 | Context expansion ưu tiên cùng chương, giữ độc lập citation và ebook/version scope | Đã implement; chưa deploy/reindex |
| 4 | So sánh v1/v2 trên sách thật và retrieval benchmark trước khi đổi mặc định | Đã có benchmark + heading fix; HOLD vì retrieval regressions/held-out |

Tiêu chí v2 được kiểm tra bởi acceptance tests riêng (không suy từ tests v1):

- Heading giữa trang tạo đúng boundary, không gán phần trước heading sang chương mới.
- Hai chương trên một trang không bị trộn vào cùng chunk.
- Câu/đoạn liên tục trong cùng chương có thể nối qua trang với provenance đúng.
- Không nối bừa hai trang/hai chương; heading không chắc chắn phải fallback bảo thủ.
- Không có heading vẫn chunk được, không bịa tên chương.
- Giữ text nguồn, book/ebook scope, trang và citation đúng khi re-chunk.

## 8. Verification ledger

### Bước 1 — mốc giữ nguyên

- Trước thay đổi: 15 tests chunking/chapter/node adapter/ingestion chunk path passed.
- Full RAG suite sau thay đổi: 260 passed, 6 Pydantic alias/schema warnings đã có
  từ trước; trong đó 31 tests baseline mới đã qua.
- CLI --check: snapshot khớp 11 cases, 19 parsed pages và 20 chunks. Trang dài
  tạo 2 chunks và có text overlap thật. Mọi case qua quality gate nhưng các
  giới hạn semantic/chapter của v1 vẫn tồn tại như đã ghi ở phần 3.
- Tests kiểm tra repeatability và cô lập config khỏi runtime environment;
  từ chối đi vào parser/storage/DB/provider ingestion I/O; reject snapshot drift;
  JSON stdout parse được; CLI không overwrite output đã tồn tại.
- Đã kiểm tra whitespace và các liên kết local trong tài liệu này.
- Chưa test PDF parser end-to-end, real-book retrieval hoặc production.

### Bước 2 — chapter-aware v2

- Full RAG suite sau thay đổi cuối của bước 2: **326 passed, 6 warnings** trong 53.45s;
  warnings Pydantic alias/schema đã có từ trước. Trong đó 66 acceptance tests
  mới của v2; 31 tests baseline v1 vẫn qua và snapshot không đổi.
- CLI v2: 11 cases, 19 parsed pages, **18 chunks**, mọi case qua quality gate.
  Hai chương chung trang thành hai chunks riêng; câu liên tục qua trang nằm
  trong một chunk có citation pageStart=1/pageEnd=2, source spans đúng text.
- Đã test full non-whitespace source coverage, exact character-span equality,
  hash/source IDs/page citations, repeatability và không mutation input.
- Có regression tests cho text lặp với overlap thật, zero-overlap, quan hệ
  previous/next trong cùng chương; mapping từ chối source bị bỏ sót/biến đổi.
- Có tests chặn carry/join khi đổi document/ebook/book/version/checksum/source
  type, thiếu identity, nhảy/lặp số trang hoặc trang rỗng; bảo vệ code fences.
- CLI v2 chạy selector/pipeline thật với config pin; tests cấm ingestion I/O
  và kiểm tra JSON/no false snapshot claim. v2 chưa có reviewed snapshot.
- Env examples và Compose truyền flag với default v1; chưa sửa runtime env,
  restart services, upload/parse PDF thật, gọi provider hoặc reindex.
- Ở mốc bước 2, chưa đo chất lượng retrieval/answer và bước 3–4 còn việc;
  xem checkpoint bước 3 dưới đây để biết trạng thái cập nhật.

### Bước 3 — chapter/revision-aware context expansion

- Full RAG suite: **416 passed, 10 Pydantic alias warnings**, 33.17s. Thêm
  89 unit tests + 1 integration scenario; warnings cùng loại cũ được scenario
  HTTP mới kích hoạt thêm, chưa sửa trong bước này.
- Kiểm tra cùng section/chapter, fallback cùng trang, IDs/aliases/revisions,
  source checksum, anchor text/hash/ID, current INDEXED/active SQL predicates,
  stop-at-gap/boundary, merged windows, distance priority và dedup.
- Integration synthetic từ real cleaner/chunker/compact payload đến answer
  HTTP chứng minh neighbor giữ citation riêng và chương kế tiếp không được
  thêm vào prompt. DB/vector/provider/LLM dùng adapters deterministic, không live.
- v1 snapshot và v2 acceptance tests không đổi hành vi; source checksum chỉ
  thêm vào runtime chunk path khi đã có checksum PDF, không giả checksum fixtures.
- Chưa deploy/reindex, chưa đổi default v1, runtime env hoặc golden dataset.
  Ở mốc bước 3 chưa khẳng định retrieval/answer tốt hơn trên sách thật;
  checkpoint bước 4 nằm dưới đây.

### Bước 4 — real PDF comparison

- Thêm source-anchored dataset riêng, 20 questions/2 PDF English/135 trang.
  Parse/clean/chunk path thật; không dùng lại IDs của golden dataset HTTP cũ.
- BM25 offline chạy thành công: Magi hai phiên bản bằng nhau; Douglass v2
  Evidence Recall@3 0.60 so với v1 0.70. Source coverage sau cleaning 100%.
- Final Gemini dense/hybrid: 20 questions, errorCount=0; Douglass dense
  Recall@3 v1=1.00/v2=0.80, hybrid Recall bằng 0.80 nhưng MRR v2 thấp hơn.
  Full RAG suite: 448 passed, 10 warnings Pydantic cũ; v1 snapshot vẫn khớp.
- Chapter audit phát hiện v1 không strip Markdown, v2 bỏ heading có terminal
  period, mang PREFACE sang body; Appendix chưa được detector hỗ trợ.
- PDF/parser/embedding caches và reports local được ignore. Không có runtime
  writes, LLM generation, promote strategy, reindex hoặc deploy.
- Chi tiết final dense/hybrid run, tests và giới hạn xem
  [benchmark ledger](real-book-chunking-benchmark.md). HOLD default v1.

### Follow-up — heading regression fix

- V2 nhận terminal period và Appendix; không carry Preface qua Markdown
  title block trước main chapter. Oracle lấy toàn heading line kể cả wrapper.
  V1 detector, baseline fixtures/snapshot và real-book source labels không đổi.
- Thêm 43 regressions: 37 heading/section + 6 oracle tests. Full suite
  **491 passed, 10 warnings**; reviewed v1 snapshot vẫn khớp.
- Cùng PDF/labels/checksum: Douglass v2 có **0/112** chapter-label mismatch,
  0 mixed-chapter chunks, 100% cleaned source coverage; 128 chunks.
- Final Gemini run 06: đủ 120 cases, errorCount=0, không vượt context budget.
  Dense v2 Recall@3 từ 0.80 → 1.00; hybrid MRR từ 0.687 → 0.775.
  BM25 Recall vẫn 0.60 < v1 0.70; dense MRR 0.900 < v1 0.950.
- HOLD default v1: review retrieval regressions, thêm Vietnamese/held-out;
  không reindex hoặc deploy. Chi tiết ở
  [heading fix ledger](heading-detection-regression-fix.md).

### Follow-up — ranking diagnostics và Vietnamese query holdout

- Thêm read-only BM25 contribution/cached cosine diagnostic CLI. Provider
  calls=0; không đổi runtime keyword/dense/hybrid policy hoặc tuning weights.
- Dataset mới: 20 queries Việt/22 anchors mới trên hai English PDFs cũ;
  query-level holdout, không phải native Vietnamese/unseen-book validation.
  Pin baseline/source/title/chapter/language, kiểm tra source overlap và
  khóa first-evaluated manifest; fixtures JSON dùng LF xuyên Windows/Linux.
- Final 120-case Gemini/replay: Magi v2 BM25/dense/hybrid Recall@3 =
  0.50/0.95/0.95; Douglass v2 = 0.30/1.00/0.80. English baseline replay
  giữ nguyên toàn bộ metrics/cases của run 06; replay không gọi provider thêm.
- **515 passed, 10 warnings**, 27.57s, thêm 24 tests. V1 snapshot vẫn khớp.
  HOLD v1: cần fusion/reranker investigation, Vietnamese PDF và human review.
- Chi tiết, source anchors và commands ở
  [diagnostic/query holdout ledger](retrieval-diagnostics-and-vi-query-holdout.md).

### Follow-up — hybrid ranking trace

- Collector opt-in theo request, snapshots Dense/BM25/RRF trước và sau candidate
  limit/reranker/threshold/final seeds. Không thêm trace vào HTTP hoặc log.
- Hai v2 Vietnamese failures: evidence Dense rank 1 → RRF/reranker rank 6/7.
  Evidence còn trong candidateK=15; không bị threshold hoặc early eviction.
- 80 full hybrid trace invariants và 240-case cache-only benchmark replays
  giữ nguyên cases/metrics/context/citations. Không provider calls.
- **543 passed, 10 warnings**, thêm 28 tests. Scoring/weights/default v1/index
  không thay đổi. Tiếp theo là policy comparison rồi blind/human review mới.
- Kết quả/ranks/contributions/commands ở [trace ledger](hybrid-ranking-trace.md).

### Follow-up — fixed hybrid policy comparison

- Preregister 5 policies + Dense/BM25 controls; giữ source/questions/vectors,
  candidate/context budget, review từng segment và per-case Recall regressions.
- 560-case run + replay khớp hoàn toàn; provider calls=0. Cosine-heavy sửa
  Douglass queries Việt nhưng English Magi Recall 0.90 → 0.80; fusion-only
  giữ Recall nhưng Magi MRR 0.90 → 0.85. Không auto-promote theo macro gains.
- **583 passed, 10 warnings**, thêm 40 tests. Runtime defaults, `.env`, index,
  HTTP contracts, source labels không đổi. Chưa nominee để dùng production.
- Chi tiết/criteria/kết quả/commands ở [comparison ledger](hybrid-policy-comparison.md).

### Follow-up — lexical-agreement diagnosis

- Thêm label-free full-corpus BM25 profile, giữ cả score=0/rank=null rows;
  kiểm tra term sums và actual Dense/BM25 branch ranks/scores. Title/IDF/token
  features chỉ mô tả, không filter/reweight/routing hoặc scoring policy mới.
- 80 diagnostics + exact replay, baseline hybrid 80 case fields/context/citations
  khớp reference. So Dense: hybrid có 2 case-version gains, 7 losses, 71 ties;
  gains là `sold-watch` ở v1/v2. Douglass có cả title-only và non-title name
  overlap failures; counterexamples không cho dùng một coverage/title rule chắc chắn.
- **623 passed, 10 warnings**, thêm 40 tests; v1 snapshot 11 cases vẫn khớp.
  Provider calls=0; không đổi `.env`, runtime weights/defaults, labels hoặc index.
- Chi tiết/term contributions/ranks/commands ở
  [lexical diagnostic ledger](lexical-agreement-diagnostics.md). Bước tiếp theo:
  preregister experiment v2, sau đó fresh source/questions và human review.

### Follow-up — adaptive hybrid experiment v2

- Preregister và seal 4 IDF-strength alternatives + baseline/controls. Query-level
  formulas không nhận language/title/book/case IDs hoặc labels; evaluator-only
  hook dùng actual pipeline với settings copy/reranker explicit, runtime không đổi.
- 560-case run + exact replay; không provider calls/context overflow. Baseline
  replay mọi fields của references 15/16; actual weights khớp 320 adaptive decisions.
- Macro Recall@3 có thể tăng 87.5%→96.25%, nhưng mọi candidate giảm Magi MRR;
  combined-linear còn giảm `sold-watch` Recall@3 1→0 (rank2→4). Không candidate
  qua mọi gates; giữ HOLD. V1 registry hash/review decision không thay đổi.
- **671 passed, 10 warnings**, thêm 48 tests; reviewed v1 snapshot 11 cases khớp.
  Chi tiết/criteria/numeric decisions ở
  [adaptive experiment v2](adaptive-hybrid-experiment-v2.md). Tiếp theo là fresh
  Vietnamese source/queries và human review, không tune tiếp known cases.

### Follow-up — Vietnamese-source first evaluation

- Nguồn mới: World Bank Vietnamese overview 32 trang, license/source SHA256
  kiểm tra; bản dịch chính thức, không claim novel gốc tiếng Việt.
- Khóa 20 câu/23 exact anchors trước scoring. 40 BM25 cases + exact replay
  mọi field trừ generatedAt; v1/v2 Recall@3 85%/87,5%, MRR@5 0,8375/0,8875,
  context retention 85%/90%. V2 còn 30/94 section mismatches; không promote.
- Missing real cache 196 document + 20 query inputs. Provider calls=0,
  runtime writes=false; human review pending, Dense/hybrid chưa được chấm.
- **701 passed, 10 warnings**, tăng 30 tests; v1 reviewed snapshot vẫn giữ.
  [Source-evaluation ledger](vietnamese-source-evaluation-v1.md) và
  [phiếu user review](vietnamese-source-review-v1.md) ghi cách tiếp tục:
  review source/labels, approve bounded embeddings, baseline Dense/hybrid,
  rồi chẩn đoán. Không tune weights thêm trên known cases.

### Follow-up — chủ đồ án duyệt bộ Vietnamese-source v1

- Ngày 2026-10-06, user xác nhận `mình duyệt hết nha`: chấp thuận đủ 20 câu,
  23 evidence anchors và 7 heading trong phiếu review.
- [Biên bản approval](../../tests/fixtures/chunking/vietnamese_source_owner_review_v1.json)
  ghi rõ owner/method/time, toàn bộ question IDs và source/dataset hashes;
  test kiểm tra scope khớp dữ liệu đã khóa. Approval diễn ra sau first scoring,
  không claim independent blind expert review và không sửa labels/reports cũ.
- Bước tiếp theo cần xác nhận riêng cho Gemini embedding calls có budget.
  Approval review không tự authorize provider costs hoặc runtime promotion.

### Follow-up — bounded real embeddings, dừng rate/quota

- Ngày 2026-10-06, chủ đồ án xác nhận riêng lượt Gemini tối đa 220 inputs.
  [Runner](../../scripts/benchmark_vietnamese_embeddings.py) kiểm tra owner
  receipt/hash và seals, khóa identity/config, count failed submissions trước
  I/O, tắt cả application retries và SDK retries, giữ output/cache immutable.
- Lượt `vietnamese-source-gemini-25`: 7 request attempts, 112 input submitted;
  96 real document vectors cache; batch lỗi 16 inputs thuộc nhóm rate/quota.
  Còn thiếu 100 document + 20 query inputs, **0 Dense/Hybrid cases**. Không có
  quyền retry vượt budget; chưa xác định RPM/TPM/RPD từ safe error category.
- BM25 và mọi chunk/source/context/section audits khớp first-23 toàn bộ.
  Dataset, receipt và reports first-23/replay-24 giữ nguyên SHA256. Không đổi
  runtime/defaults, không DB/Qdrant/LLM writes/calls hoặc auto-promotion.
- Cache-only CLI thật fail trước provider vì thiếu vectors; không fake cache
  hay ghi passing report. Lượt bổ sung cần quyền và input budget riêng (hiện
  thiếu 120, budget cũ còn 108). Script hỗ trợ cap missing submissions tách
  khỏi union corpus cap, không gửi lại entries đã có cache hợp lệ.
- Final **735 passed, 10 warnings**, 40,30s; tăng 33 tests so với 702. Narrow
  related suites **102 passed**. 157 local doc links hợp lệ; scoped whitespace
  check sạch. Spring/Next, live latency và answer quality chưa chạy lượt này.
- [Embedding baseline / failure ledger](vietnamese-source-embedding-baseline-v1.md)
  ghi chi tiết số liệu, authority, flow và cách tiếp tục. Production vẫn HOLD v1.

### Follow-up — token-aware completion và zero-provider replay

- Ngày 2026-10-06, user cung cấp quota page và duyệt riêng tối đa 120 missing
  embedding inputs, tính cả failed submissions. Giữ 96 real vectors đã có,
  không thay key/tier/billing/labels/embedding identity/ranking/runtime settings.
- [Local throttle](../../scripts/embedding_throttle.py) dùng UTF-8 bytes +32/input
  trên exact provider-facing text, rolling 61 giây, cap 20.000 estimated
  reservation units và 60 attempts; document batch <=8 inputs/10.000 units.
  Đây không phải actual Gemini/billing tokens hay đo shared project traffic.
  Chia/cache từng successful batch; thất bại vẫn tính reservation/input budget;
  không retry. Peak quota page 28 ngày không chứng minh exact failure cause.
- Lượt `vietnamese-source-gemini-throttled-27`: **120/120 inputs successful**
  (100 documents +20 original queries), 37 provider attempts, 0 errors; cache đủ
  216 vectors. Peak window 19.865 units/21 attempts; 35 waits, scheduled 375,91s.
  Chấm đủ **120 cases = 40 BM25 +40 Dense +40 Hybrid**.
- Hybrid v1/v2 Recall@3 **92,5%/90%**, MRR@5 **0,8792/0,8667**, context anchor
  retention **100%/95%**. Dense Recall@3 **90%/85%**. BM25 và toàn bộ chunk/
  source/context/section audits vẫn khớp first-23; section mismatches v2 vẫn
  30/94. `vi-poverty-rate` v2 mất top-3/context evidence trong Dense/Hybrid;
  không tự chỉnh nhãn/weights để bù regression.
- Lượt `vietnamese-source-cache-replay-28`: **120 cases, 0 errors, 0 provider
  calls/submitted inputs**. Dataset/config/books/provenance/review/decision/
  limitations/case counts và mutation flags khớp report 27 exact; operational
  counters/authorization/throttle và timestamps khác có chủ đích.
- PDF, raw manifest, receipt và reports first-23/replay-24/failed-25 giữ nguyên
  SHA256; artifacts vẫn private/ignored. LLM generation/runtime writes=0;
  **HOLD v1 + baseline ranking**, không fixed/adaptive formula scoring,
  promotion, DB/Qdrant reindex, commit/push hoặc deploy.
- Final code verification: full **750 passed, 10 warnings**, 57,07s; narrow
  **117 passed**, 21,27s; 15 throttle tests mới. Syntax/scoped whitespace checks
  đạt; 158 local doc links hợp lệ và 14 scoped text files sạch trailing whitespace.
  Chưa chạy Spring/Next builds, live latency/load hoặc answer/judge quality.
- [Baseline ledger](vietnamese-source-embedding-baseline-v1.md) ghi full metrics,
  per-case failures, counters và replay scope. Tiếp theo chẩn đoán source-mapped
  retrieval/section gaps; strategy changes phải là experiment riêng, replay cả
  corpus cũ+mới. Không tune thêm trên observed cases hoặc gọi provider ngoài quyền.

### Follow-up — source/heading/rank/context diagnosis trên baseline đã hoàn tất

- Ngày 2026-10-06, user yêu cầu làm bước chẩn đoán tiếp theo và giải thích.
  [Runner](../../scripts/diagnose_vietnamese_baseline.py) kiểm tra sealed labels/
  owner receipt/completed cache-only reference và pinned full config; không
  provider fallback, ablation, tuning, runtime settings selection hoặc writes.
- Reports private `vietnamese-baseline-diagnostics-29` và final `-30`: **40
  case-version pairs, 120 exact baseline replay cases, 0 errors/provider calls**.
  Actual Hybrid traced/untraced response invariance **40/40**. `versions`
  payload hai runs khớp exact; report/source/labels cũ không bị ghi đè.
- **23/23 anchors** ở mỗi chunker đều có full single-chunk source, source
  coverage=100%. V1 nhận **0/7** reviewed headings (Markdown wrappers chưa bóc);
  v2 nhận **2/7**, không nhận unnumbered Vietnamese sections. V2 mismatch **30/94**,
  trong đó 17 chunks carry sai `Phần II...`, 13 actual title=None; mixed sections=3.
- Poverty evidence cùng exact source/provider-input SHA256 và cosine **0,753817**,
  Dense rank **3→4** khi corpus đổi; chưa chứng minh causal metadata-only effect.
  High-income: BM25 focused rank2, RRF rank3→reranker rank4, context retention=1.
  Digital-two-domains v1: PDF26 Dense rank3→RRF/reranker rank7→outside final5;
  context expansion vẫn phục hồi. BM25 còn cứu được not-poor evidence rank9/14.
- [Full diagnosis](vietnamese-baseline-diagnostics-v1.md) phân biệt source loss,
  section metadata gaps, rank losses và top-3/context selection; không suy ra
  answer quality từ context retention. Hướng kế tiếp: isolated section-detection
  v3 experiment, không sửa sealed v1/v2; embedding inputs mới cần quyền/budget riêng.
- [27 tests mới](../../tests/unit/test_vietnamese_baseline_diagnostics.py); narrow
  **88 passed**, 29,40s; final full **777 passed, 10 existing warnings**, 53,94s.
  Chưa chạy Spring/Next builds, deploy, production load/latency hoặc LLM judge.
  Giữ HOLD v1, no commit/push/DB-Qdrant reindex. Common app imports initialize
  config/SQLAlchemy engine, không mở DB session/connection trong runner.

Source của tool/test:

[benchmark_chunking.py](../../scripts/benchmark_chunking.py),
[test_chunking_baseline.py](../../tests/unit/test_chunking_baseline.py),
[test_chapter_aware_chunking.py](../../tests/unit/test_chapter_aware_chunking.py).
