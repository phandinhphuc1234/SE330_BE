# Học từng phần RAG qua implementation của Library

Cập nhật: 2026-10-06.

Mục đích: giải thích từng phần đã implement, ghi rõ code nằm ở đâu, đã kiểm tra
gì và giới hạn nào còn tồn tại. Mỗi lượt chỉ học một phần. Tài liệu này không
thay thế [trạng thái implementation](retrieval-evaluation-implementation.md).

## Tiến độ giải thích

| Bài | Chủ đề | Trạng thái code | Trạng thái học |
| --- | --- | --- | --- |
| 1 | BM25 / keyword retrieval | Đã implement | Đã soạn giải thích và chạy ví dụ; chờ người học xác nhận hiểu |
| 2 | Hybrid retrieval và RRF | Đã implement | Chưa giải thích riêng |
| 3 | Query rewriting và reranker | Bản deterministic / heuristic đã implement | Chưa giải thích riêng |
| 4 | Context expansion / compression | Đã implement | Chưa giải thích riêng |
| 5 | Evaluation runner / golden dataset | Đã implement; live answer evaluation chưa chạy đủ do quota | Chưa giải thích riêng |
| 6 | Graph retrieval theo ebook | Extension local đã implement; không phải full GraphRAG | Chưa giải thích riêng |

Checkpoint implementation cho chủ đề 4:
[Context expansion theo chương và source revision](chunking/chapter-aware-context-expansion.md).
Đã ghi flow/tests/giới hạn; không tự đánh dấu người học đã hiểu bài.
Checkpoint cho chủ đề 5: [benchmark chunking PDF thật](chunking/real-book-chunking-benchmark.md)
dùng shared source anchors cho v1/v2, không thay golden dataset HTTP cũ.
[Heading regression fix](chunking/heading-detection-regression-fix.md) đã xử
lý v2 chapter-label gaps trên corpus này. HOLD vì retrieval regression và
thiếu held-out review; chưa chứng minh answer quality.
Follow-up: [ranking diagnostics/query holdout](chunking/retrieval-diagnostics-and-vi-query-holdout.md)
giải thích TF/DF/IDF và cosine margin, thêm queries Việt trên English PDFs.
Đây là validation implementation, không tự đánh dấu người học đã hiểu bài.
[Hybrid ranking trace](chunking/hybrid-ranking-trace.md) ghi rõ evidence tụt
qua RRF/reranker/cutoff, numerical contributions và invariant tests. Scoring
chưa thay đổi; đây cũng không tự đánh dấu bài 2–3 đã học hoặc đã hiểu.
[Fixed policy comparison](chunking/hybrid-policy-comparison.md) có số liệu
gains/regressions giữa các books/query sets. Chưa đổi default ranking; không
đánh dấu người học đã hiểu chỉ vì experiment và doc đã hoàn thành.
[Lexical agreement](chunking/lexical-agreement-diagnostics.md) giải thích khi
match từ khóa hữu ích/noisy bằng term contributions, full-corpus ranks và 80
cases. Đây là diagnostic cho bài 1–3, không tự đánh dấu đã học/đã hiểu và không
thêm ranking policy production.
[Adaptive experiment v2](chunking/adaptive-hybrid-experiment-v2.md) đã kiểm tra
query-level IDF strength và bốn alternatives trên 560 cases. Gains đi kèm Magi
regressions nên giữ HOLD; không đánh dấu bài 2–3 đã hiểu hoặc đổi runtime policy.
[Vietnamese-source first evaluation](chunking/vietnamese-source-evaluation-v1.md)
thêm source tiếng Việt thật + 20 câu/23 anchors, 40 BM25 cases và exact replay.
Thấy được khác nhau giữa seed Recall, MRR và context retention trên câu cần
2 trang. Bạn đã duyệt toàn bộ [phiếu câu hỏi](chunking/vietnamese-source-review-v1.md)
ngày 2026-10-06; approval lưu riêng theo hash sau first scoring, không sửa
reports cũ và không tự đánh dấu đã hiểu bài.
[Gemini baseline có budget](chunking/vietnamese-source-embedding-baseline-v1.md)
giữ 96 vectors từ lượt lỗi, rồi bổ sung đủ 120 missing inputs theo quyền riêng
với token-aware pacing. Cache đủ 216 vectors, chấm 120 BM25/Dense/Hybrid cases,
0 errors; Hybrid Recall@3 v1/v2 là 92,5%/90%. Thấy rõ context retention có thể
đạt 100% dù seed Recall@3 chưa đạt 100%; chưa chấm answer quality. Vẫn HOLD v1,
không tự đánh dấu đã hiểu, tune known cases hoặc đổi production.
[Chẩn đoán baseline](chunking/vietnamese-baseline-diagnostics-v1.md) bổ sung
40 traced case-version pairs + 120 exact replay cases, 0 provider calls.
Ví dụ `vi-poverty-rate`: evidence cosine không đổi 0,753817 nhưng Dense rank
3→4 vì các candidates khác đổi; similarity score khác vị trí tương đối trong
corpus. Ví dụ high-income: RRF rank3→reranker rank4, nhưng context vẫn giữ anchor.
23/23 anchors có full single-chunk source ở mỗi chunker; chưa cần tăng overlap
mù. Chưa chấm answer quality, chưa đổi production hoặc đánh dấu đã hiểu bài.

Không đánh dấu "đã hiểu" chỉ vì tài liệu đã được viết. Năm bài tiếp theo chưa
được giải thích trong lượt học BM25 này.

## Sơ đồ tổng thể trước khi học các bài tiếp

[Full flow RAG trong Library](rag-full-flow.md) nối các phần từ upload PDF đến
retrieval, generation/citation và evaluation; phân biệt hybrid mặc định với
graph build thủ công. Đây là phần định hướng tổng thể, không tự đánh dấu các
bài 2–6 đã học hoặc người học đã hiểu. Lượt này chỉ đối chiếu source và cập
nhật tài liệu, không thay implementation/runtime hoặc chạy lại benchmark.

## Bài 1 — BM25: tìm đoạn sách bằng từ khóa

### 1. Vấn đề nó giải quyết trong Library

PDF đã được pipeline ingestion chia thành các đoạn nhỏ, gọi là **chunks**.
Nội dung chunks được lưu trong bảng `document_chunks` của PostgreSQL; vector
embedding của chúng được lưu ở Qdrant.

Dense retrieval so sánh vector để tìm nội dung liên quan đến ý nghĩa câu hỏi.
BM25 là nhánh bổ sung tìm theo những từ thực sự xuất hiện trong nội dung chunks.
Nó hữu ích khi cần khớp những từ cụ thể như tên riêng hoặc thuật ngữ. Nó không
đọc hiểu, dịch câu hỏi hoặc tự sinh câu trả lời như LLM.

Trong project, BM25 chấm điểm **chunk**, không chấm điểm toàn bộ cuốn sách như
một đơn vị duy nhất. Corpus dùng để tính thống kê là tập chunks được source
load trong đúng scope của request.

### 2. Ví dụ đã chạy bằng code thật

Ba chunks minh họa trong bộ nhớ, không ghi vào database:

| Chunk | Nội dung |
| --- | --- |
| 1 | `Jim sold his watch.` |
| 2 | `Mrs Sofronie bought Della's hair.` |
| 3 | `Della met Jim. Jim met Della.` |

Query: `Sofronie hair`.

`tokenize()` biến query thành `['sofronie', 'hair']`. Chunk 2 có hai từ này;
chunks 1 và 3 không có từ nào trong query.

Đã gọi trực tiếp `bm25_score_candidates()` với ba chunks trên. Kết quả:

```json
{
  "query": "Sofronie hair",
  "tokens": ["sofronie", "hair"],
  "results": [{"chunkId": 2, "bm25Score": 1.961659}],
  "unmatchedQueryResults": []
}
```

`unmatchedQueryResults` là kết quả của query thứ hai `database`, vốn không
khớp từ nào trong ba chunks. Score được làm tròn sáu chữ số để ghi vào doc.
Đây là ví dụ kiểm chứng thuật toán, không phải một phép đo trên ebook production.

Riêng nhánh BM25 trả tối đa `top_k` chunks có điểm dương; không cố thêm đoạn
không khớp để đủ số lượng. Pipeline tổng vẫn có nhánh dense riêng.

### 3. Vì sao không chỉ đếm số từ xuất hiện?

BM25 cân nhắc tần suất từ và độ dài tài liệu; trong project, "tài liệu" ở đây
là chunk. Các thành phần cơ bản này được trình bày trong
[Introduction to Information Retrieval — Okapi BM25](https://nlp.stanford.edu/IR-book/html/htmledition/okapi-bm25-a-non-binary-model-1.html).

Ba ý cần nhớ:

1. **TF — term frequency:** một từ xuất hiện trong chunk bao nhiêu lần. Lặp
   thêm có thể tăng điểm, nhưng phần tăng giảm dần, không tăng tuyến tính mãi.
2. **IDF — inverse document frequency:** từ xuất hiện ở ít chunks trong corpus
   có trọng số lớn hơn từ xuất hiện khắp nơi. Ví dụ `Sofronie` xuất hiện ở
   một chunk, còn `Jim` xuất hiện ở hai chunks trong ví dụ trên.
3. **Length normalization:** xét độ dài chunk so với độ dài trung bình để
   không ưu tiên một chunk chỉ vì nó dài và chứa nhiều từ.

Khái niệm IDF: [Stanford IR book](https://nlp.stanford.edu/IR-book/html/htmledition/inverse-document-frequency-1.html).

Các giá trị trong source hiện tại là `k1=1.5` và `b=0.75`. Đây là defaults của
implementation, chưa được tuning bằng một bộ dữ liệu độc lập lớn.

### 4. Luồng thực tế trong code

1. Spring kiểm tra quyền đọc rồi gửi phạm vi ebook tin cậy tới RAG. BM25 không
   tự kiểm tra JWT hoặc nghiệp vụ loan.
2. `KeywordRetriever.search(query, top_k, scope=...)` kiểm tra `top_k`, tách
   query thành tokens; query không có token thì trả danh sách rỗng.
3. `PostgresKeywordCandidateSource.load()` lấy chunks thuộc phạm vi được gửi.
4. `bm25_score_candidates()` tách tokens trong từng chunk, tính thống kê corpus
   và điểm BM25, giữ những điểm dương rồi sắp xếp giảm dần.
5. Retriever lấy tối đa `top_k`, giữ text/metadata/vector ID và tạo `SearchResult`
   với `retrieval_source="keyword"`.
6. Danh sách được đưa về retrieval pipeline. Cách kết hợp với dense là bài 2,
   chưa đi sâu trong bài này.

Các scope `bookId`, `ebookId`, `documentId` được kết hợp bằng điều kiện **AND**
khi cùng được cung cấp. Candidate còn phải có:

- `source_type` đúng loại Library ebook;
- document có trạng thái `INDEXED`;
- embedding version của document khớp phiên bản hiện tại;
- chunk active theo scope, với active thiếu/null được source coi là true.

Metadata định danh document/book/ebook được lấy từ bản ghi document để giữ
phạm vi và citation. Việc kiểm tra quyền user vẫn thuộc Spring.

### 5. PostgreSQL và GIN làm gì? Có tính BM25 trong SQL không?

**Hiện tại không tính BM25 trong SQL.** PostgreSQL lưu nội dung chunks và lấy
candidates; Python thực hiện phép tính BM25.

`KEYWORD_CANDIDATE_LIMIT` mặc định là 5000:

- Scope có tối đa 5000 chunks: load toàn bộ corpus trong scope rồi tính BM25.
- Scope lớn hơn: dùng PostgreSQL full-text search để lọc trước các chunks
  chứa ít nhất một từ query, xếp bằng `ts_rank_cd`, lấy tối đa 5000 candidates,
  sau đó Python mới tính BM25 trên tập này.

`ts_rank_cd` không phải điểm BM25. Khi prefilter xảy ra, IDF là thống kê trên
tập candidates đã lấy, không còn là thống kê đầy đủ của toàn scope.

Migration 005 thêm:

```sql
CREATE INDEX ix_document_chunks_fts_simple
ON document_chunks
USING GIN (to_tsvector('simple'::regconfig, content));
```

GIN là inverted index phục vụ full-text search: lưu liên hệ giữa từ và vị trí
khớp. Nó hỗ trợ bước prefilter; không có nghĩa mọi query BM25 ở corpus nhỏ đều
đi qua GIN. Tham khảo [PostgreSQL 16 — Text Search Indexes](https://www.postgresql.org/docs/16/textsearch-indexes.html).

Migration còn thêm index `(document_id, chunk_index)`. Nó không sửa nội dung
PDF/chunks. Một PDF hiện được giới hạn 1000 chunks, nên request một ebook ở
defaults hiện tại dùng toàn corpus ebook.

### 6. Ý nghĩa của score

`metadata.bm25_score` giữ điểm BM25 gốc. Trước khi đưa vào pipeline, nhánh keyword
chuẩn hóa điểm bằng `raw_score / highest_score` trong chính tập kết quả query đó.

Điểm cao nhất thành `1.0` **không có nghĩa câu trả lời đúng 100%**. Đó chỉ là
điểm tương đối của kết quả tìm từ khóa. Không so sánh raw score giữa các query
hoặc các corpus khác nhau như một xác suất.

Pipeline giữ điểm cosine phục vụ ngưỡng evidence; không dùng riêng một điểm
BM25 cao để tự xác nhận câu trả lời đủ bằng chứng.

### 7. Giới hạn hiện tại

- Tokenizer dùng regex Unicode và `casefold()`; chưa có phân từ tiếng Việt
  chuyên biệt, stemming, fuzzy search hoặc phrase/proximity scoring.
- Không tự nhận ra từ đồng nghĩa hoặc dịch giữa `hair` và `tóc`.
- Token có apostrophe có thể được giữ nguyên, ví dụ `Della's`; không mặc định
  stem nó về `Della`.
- Tính lại thống kê corpus trong Python mỗi lần search; chưa có cache/statistics
  index BM25 dài hạn. Candidate budget giới hạn workload, không chứng minh
  khả năng chịu tải production.
- Với corpus vượt budget, prefilter có thể bỏ sót và điểm dùng candidate-local
  IDF. Chưa coi đây là BM25 toàn thư viện ở quy mô lớn.
- Nhánh BM25 không gọi Gemini; luồng hybrid tổng vẫn có Gemini query embedding
  và luồng answer còn có LLM. Không vì thế mà toàn RAG hoạt động offline.

### 8. Những gì đã implement và đã kiểm tra

Source cần đọc:

| File / thành phần | Vai trò |
| --- | --- |
| [keyword_retriever.py](../app/retrieval/keyword_retriever.py) / `PostgresKeywordCandidateSource` | Lấy candidates đúng scope và prefilter khi vượt budget |
| Cùng file / `tokenize`, `bm25_score_candidates` | Tách tokens và tính điểm BM25 |
| Cùng file / `KeywordRetriever` | Lấy top K và giữ metadata cho downstream |
| [005_keyword_search_index.py](../alembic/versions/005_keyword_search_index.py) | GIN và document/chunk index |
| [retrieval_pipeline.py](../app/retrieval/retrieval_pipeline.py) | Gọi nhánh keyword; xử lý fallback |
| [test_advanced_retrieval.py](../tests/unit/test_advanced_retrieval.py) | Tests thuật toán, scope, budget, fusion safety và dense override |

Ngày 2026-10-05 đã chạy lại, từ folder `rag-service`:

```powershell
.\.venv\Scripts\python.exe -m pytest tests/unit/test_advanced_retrieval.py -k 'bm25 or keyword' -q
```

Kết quả: **6 passed, 13 deselected**. Các kiểm tra bao gồm:

- query hiếm/chính xác chọn đúng chunk, query không khớp trả rỗng;
- scope và candidate budget được truyền đúng;
- SQL có điều kiện Library scope, INDEXED và current version;
- SQL prefilter dùng đúng biểu thức GIN và giữ giới hạn;
- keyword-only hit không tự vượt ngưỡng semantic evidence;
- override `dense` không gọi nhánh keyword.

Các source/SQL adapter trong tests này được mock. Ví dụ thuật toán phía trên
chạy trực tiếp bằng code thật, không gọi database/provider. Trong bài học này
không chạy lại benchmark end-to-end hoặc thay đổi runtime.

### 9. Nhật ký lượt học này

- Đã đối chiếu source BM25, migration 005, config và cách pipeline gọi keyword.
- Đã soạn bài 1, chạy ví dụ ba chunks và chạy lại sáu tests liên quan.
- Đã ghi rõ ranh giới PostgreSQL/Python, score và hạn chế ngôn ngữ/quy mô.
- Chỉ cập nhật tài liệu và liên kết tài liệu; không sửa implementation, key,
  database hoặc deploy trong lượt học này.
- Chưa đánh dấu người học đã hiểu; chưa chuyển sang bài 2.

### 10. Câu hỏi tự kiểm tra

Nếu một từ xuất hiện ở gần như mọi chunk, còn một từ chỉ xuất hiện ở vài chunk,
từ nào thường có trọng số IDF lớn hơn? Vì sao?

Ý chính cần nhớ: **BM25 tìm và xếp hạng bằng từ xuất hiện trong sách; không tự
hiểu ý nghĩa hay tự tạo câu trả lời.**
