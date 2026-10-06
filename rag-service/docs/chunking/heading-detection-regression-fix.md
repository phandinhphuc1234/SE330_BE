# Sửa heading detection sau benchmark PDF thật

Cập nhật: 2026-10-06. Đây là bước tiếp theo của
[benchmark PDF thật](real-book-chunking-benchmark.md). **Giữ mặc định v1**;
không reindex, đổi runtime `.env`, restart containers hoặc deploy.

Follow-up mới: [ranking diagnostics và Vietnamese query holdout](retrieval-diagnostics-and-vi-query-holdout.md).

## 1. Vấn đề và bản sửa

1. **Terminal period:** detector v2 trước đây từ chối `CHAPTER I.` dù đã
   bỏ wrapper Markdown. Nay nhận một dấu chấm kết thúc, giữ nguyên dấu chấm
   trong metadata title và không sửa text nguồn/offset. Dotted leaders,
   ellipsis, table/list/quote/code và prose mơ hồ vẫn bị từ chối.
2. **Appendix:** thêm section không đánh số `Appendix`/`APPENDIX.`. Heading
   này chấm dứt chapter trước; nội dung sau nó thuộc Appendix, không Chapter XI.
3. **Front matter:** nếu đang carry Preface/Foreword/Introduction và trang
   bắt đầu bằng một khối toàn Markdown headings trước chapter đánh số, khối
   title đó được giữ thành section không gán chương. Không carry PREFACE
   qua title block. Văn xuôi cuối preface trước heading giữa trang vẫn giữ
   nhãn preface; không áp dụng một quy tắc reset bừa cho mọi prefix.
4. **Oracle độc lập:** label `APPENDIX.` có thể nằm trong source line
   `#### **APPENDIX.**`. Bộ chấm nay lấy boundary ở đầu cả dòng thay vì giữa
   wrapper. Chỉ cho phép wrapper Markdown/whitespace; inline prose và code
   indentation bị từ chối. Không gọi detector để tạo ground truth, không sửa
   manifest, trang, quote hoặc title để làm điểm tốt hơn.

Các lỗi 1–3 nằm ở nhánh v2. **Code ChapterDetector v1, 11 baseline fixtures
và reviewed v1 snapshot giữ nguyên** để tiếp tục so sánh công bằng.

Code liên quan:
[heading detector](../../app/ingestion/chunking/chapter_boundaries.py),
[chapter-aware strategy](../../app/ingestion/chunking/chapter_aware_strategy.py),
[source-anchored evaluator](../../app/evaluation/chunking_comparison.py).

## 2. Test chống tái phát

- [37 heading/section regressions](../../tests/unit/test_real_book_heading_regressions.py):
  English/Vietnamese, Roman/word/Arabic numbers, punctuation, Markdown/bold;
  tất cả 12 headings tự review của Douglass; negative TOC/prose/structure;
  Preface → Chapter I → Chapter II → Appendix; code fence qua trang;
  title block/fallback, exact spans/coverage, citation và input immutability.
- [6 oracle regressions mới](../../tests/unit/test_book_chunking_benchmark.py):
  label có/không wrapper phải có cùng source boundary; không chấp nhận
  inline, multi-line hoặc indented-code label làm heading oracle.
- Trước bản sửa: 15/33 heading regressions ban đầu fail, 18 negative tests
  pass. Sau sửa: 172 tests liên quan pass, không regenerate snapshot v1.
- Full RAG suite kiểm tra cuối: **491 passed, 10 warnings**, 46.33s.
  Thêm 43 tests so với mốc bước 4 (448). Warnings Pydantic alias cũ, không fail.
- CLI `benchmark_chunking --check` xác nhận reviewed v1 snapshot vẫn khớp.

## 3. Benchmark lại đúng dữ liệu cũ

Giữ nguyên hai PDF/135 trang, 20 development questions, source checksums,
parser/cleaner, 512/64 tokens, top-3 seeds, expansion window=1 và 12.000 ký
tự context. Không đổi retrieval weights hoặc tune labels theo ranking.

`real-books-heading-fix-offline-04` là lượt trung gian: còn 2 mismatch,
gồm title block bị carry PREFACE và boundary oracle bên trong wrapper
Appendix. Giữ nguyên report này để traceability, không ghi đè.

Lượt offline cuối `real-books-heading-fix-offline-05`:

| PDF | Strategy | Chunks | Chapter mismatches / audited | Mixed-chapter chunks | Source coverage |
| --- | --- | ---: | ---: | ---: | ---: |
| Magi | v1 | 9 | 0 / 9 | 0 | 100% |
| Magi | v2 sau sửa | 9 | 0 / 9 | 0 | 100% |
| Douglass | v1 không đổi | 128 | 111 / 111 | 11 | 100% |
| Douglass | v2 sau sửa | 128 | **0 / 112** | **0** | 100% |

Douglass có 12 reviewed boundaries: 11 chapters + Appendix. Audit bắt đầu
trang vật lý 19; front matter chưa gán nhãn không được tính là quality oracle.
v2 cũ có 108/108 mismatch; chunk count/denominator thay đổi khi sửa section
boundaries. Không lấy raw count thấp hơn làm bằng chứng cải thiện. Source
coverage chỉ đo non-whitespace text sau cleaning, không phải PDF/OCR accuracy.

BM25 Recall@3 Magi vẫn 0.85 cho cả hai. Douglass v1=0.70, v2=0.60;
MRR@5 v2 từ 0.50 ở lượt cũ lên 0.52, nhưng chưa bằng v1=0.60.
Sửa nhãn chương không tự đảm bảo retrieval ranking tốt hơn.

## 4. Gemini dense/hybrid sau bản sửa

Lượt cuối `real-books-heading-fix-gemini-06` chạy đủ **120 cases**, errorCount=0,
0 lượt vượt context budget. Dùng cùng model `gemini-embedding-2`, dimension
3072, version `gemini-embedding-2-3072-v1`, text policy
`gemini_search_title_text_v1`. 282 unique document/query inputs trong budget
400; **8 provider requests** mới, 514 cache hits (lượt đọc, không phải 514
unique vectors). Pacing=15s, retries=0. Cache không tái dùng embedding của
chunk đã đổi content/chapter/page context vì input hash khác.

Magi không đổi: BM25 Recall@3=0.85, dense=0.80, hybrid=0.90 ở cả hai versions.
Các số Douglass:

| Mode | Strategy | Recall@3 | MRR@5 | Context retained |
| --- | --- | ---: | ---: | ---: |
| BM25 | v1 frozen | 0.70 | 0.600 | 0.70 |
| BM25 | v2 trước sửa (run 03) | 0.60 | 0.500 | 0.60 |
| BM25 | v2 sau sửa (run 06) | 0.60 | 0.520 | 0.60 |
| Dense | v1 frozen | 1.00 | 0.950 | 1.00 |
| Dense | v2 trước sửa (run 03) | 0.80 | 0.683 | 0.90 |
| Dense | v2 sau sửa (run 06) | **1.00** | **0.900** | **1.00** |
| Hybrid | v1 frozen | 0.80 | 0.775 | 0.85 |
| Hybrid | v2 trước sửa (run 03) | 0.80 | 0.687 | 0.90 |
| Hybrid | v2 sau sửa (run 06) | 0.80 | **0.775** | 0.90 |

Recall đo source anchors, không phải tỷ lệ LLM trả lời đúng. Dense v2 đạt
Recall bằng v1 và tăng 20 percentage points so với v2 cũ; MRR vẫn thấp hơn
v1 (0.900 < 0.950). Hybrid MRR phục hồi bằng v1, context retention cao hơn
v1 5 percentage points; BM25 vẫn thấp hơn v1 10 percentage points về Recall.

Đã đối chiếu source checksum, page count, cleaned-page hashes và toàn bộ
v1 BM25 cases với run 03: đều không đổi. Manifest source labels và snapshot
v1 không chỉnh sửa; oracle wrapper fix ảnh hưởng chapter audit, không thay
retrieval evidence/quote metrics. Reports cũ được giữ nguyên.

Final gates: source coverage **PASS**, chapter labels **PASS**, dense/hybrid
completion **PASS**, no-measured-regression **FAIL** (BM25 và dense MRR),
held-out Vietnamese **NOT RUN**. **HOLD v1**, không auto-promotion.

Hai cases cụ thể cần phân tích tiếp từ report: BM25 `birthplace` có bằng
chứng rank 1 ở v1 nhưng rank 5 ở v2 (ngoài top-3); dense `mother-name` từ
rank 1 xuống rank 2, Recall@3 vẫn đầy đủ nhưng MRR giảm. Chưa đổi keyword
weights/chunk size chỉ để sửa hai câu development này.

Reports JSON/MD đầy đủ ở ignored `data/chunking-comparison/`; không commit
PDF/cache/vectors/private source reports lên Git.

## 5. Giới hạn và bước tiếp theo

- Hai sách/câu hỏi English này là development set, không phải held-out set.
- Heuristic title-block chỉ nhận cấu trúc rõ; không đoán mọi title page.
  Running headers, mục lục không có dotted leaders và format heading khác
  vẫn cần review trên sách mới. `chapter_index` là thứ tự heading detector,
  không phải số chương in trên sách (`chapter_number`).
- Dense search dùng exact cosine trong memory, không đo Qdrant/SQL production.
- Không gọi LLM generation/judge: chưa chấm answer faithfulness/abstention.
- Giữ default v1; không tự promote khi chapter-label gate pass. Cần phân tích
  các câu retrieval bị giảm, rồi thêm Vietnamese và held-out questions trước
  quyết định promote/reindex có rollback.

Flow: đọc cùng PDF/labels → parse/cache hợp lệ → cleaner chung → v1 frozen /
v2 đã sửa → source/chapter audit độc lập → BM25/dense/hybrid → expansion và
compression → report mới + HOLD gates. Mục đích: sửa lỗi cấu trúc và đo tác
động thật, không thay đổi dữ liệu hoặc chất lượng production ngầm.
