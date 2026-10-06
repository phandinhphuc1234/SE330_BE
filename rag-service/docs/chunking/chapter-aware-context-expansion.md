# Context expansion theo chương và phiên bản — bước 3

Cập nhật: 2026-10-05. Đã implement trong code; chưa deploy/reindex.
Chunking mặc định vẫn `v1`; [v2](chapter-aware-v2.md) vẫn opt-in.

## 1. Giải thích ngắn

Retrieval tìm được một chunk liên quan, gọi là **seed**. Nhưng lời giải thích
hoặc phần tiếp theo của câu chuyện có thể nằm ở chunk ngay trước/sau nó.
Expansion lấy thêm những chunks đó để LLM có đủ ngữ cảnh.

Không lấy chunk chỉ vì index đứng cạnh seed. Chúng phải cùng nguồn/phiên bản,
cùng chương/section đáng tin và không bị ngăn bởi một boundary hay chunk lỗi.
Neighbor giữ text, vector ID, trang và citation **của chính nó**.

Ví dụ seed index 39 thuộc chương 1: chunk 38 cùng chương có thể được thêm;
chunk 40 đã sang chương 2 thì không. Với window=3, không nhảy qua chunk 40
để lấy một chunk xa hơn chỉ vì nó có metadata trông giống chương 1.

## 2. Luồng thực tế

~~~text
Scoped retrieval → rerank / threshold / topK → seeds
  → kiểm tra document + ebook + embedding version + aliases
  → gộp cửa sổ index, một lần đọc PostgreSQL
  → chỉ đọc document INDEXED, chunk active, cả hai đúng embedding version
  → đối chiếu seed với DB anchor: cùng ID, text/hash, trang, scope và chương
  → đi từng bước về trước/sau (window 0..3)
      → khác phiên bản/chương, mất chunk hoặc metadata không rõ: dừng hướng đó
      → hợp lệ: thêm evidence riêng
  → giữ seeds trước; neighbors cùng chương/section ưu tiên hơn fallback cùng trang
  → ưu tiên neighbor gần hơn, rồi seed rank; deduplicate theo vector ID
  → compression theo global budget → prompt có source IDs riêng
  → LLM chọn source ID → backend dựng citation đúng chunk nguồn
~~~

Không gọi thêm LLM hay embedding trong expansion. Nếu dependency đọc neighbors
lỗi, `HYBRID_FAIL_OPEN` vẫn điều khiển dùng seeds hoặc báo lỗi như trước.
Metadata thiếu/khác scope khiến **không thêm neighbor**, không lấy dữ liệu rộng
hơn để bù. Seeds độc lập đã được retrieval chọn không bị lọc chỉ vì nằm ở
các chương khác nhau; câu hỏi so sánh nhiều chương vẫn có thể dùng chúng.

## 3. Chọn chương như thế nào?

| Loại chunk | Quy tắc expansion |
| --- | --- |
| v2 có chương | Cùng section_id; metadata chương phải nhất quán |
| v1 có chương | Cùng chapter_index và chapter_title; nếu cả hai có trang heading thì phải khớp |
| Không có chương đáng tin | Chỉ cùng một trang; v2 còn phải cùng section_id |
| Khác chương/section hoặc metadata không đủ | Không thêm neighbor |

v1 vẫn dùng nhãn chương cũ. Expansion **không sửa được chunk v1 đã trộn hai
chương sẵn bên trong**. Chapter boundaries v2 và benchmark ở bước 4 là việc
riêng; không suy ra mọi sách thật đã có nhãn chương đúng từ unit tests này.

## 4. Giữ ebook và source revision

`context_scope.py` chuẩn hóa snake/camel aliases, từ chối alias mâu thuẫn,
ID âm/0, bool/float giả làm ID, dữ liệu inactive và page ranges không hợp lệ.

Seed và neighbor phải khớp document internal/external ID, ebook, book nếu có,
embedding version và các revision/policy fields đã cung cấp: chunking strategy/
version, size/overlap/chunker, cleaning version, source checksum, document/
source version và artifact version keys. Một field có ở bên này nhưng thiếu
ở bên kia không được xem là "version nào cũng được".

Runtime ingestion vốn đã tính SHA-256 của PDF. Bây giờ checksum đó đi vào
cleaned page/chunk metadata, chunks artifacts, PostgreSQL, document metadata
và compact Qdrant payload. `section_id` cũng được giữ trong Qdrant payload;
không có nó, seed lấy từ dense search sẽ mất thông tin boundary của v2.
Không đưa source_spans hoặc quality debug reports lớn vào vector payload.

V2 thiếu source revision hoặc section_id thì không expansion. V1 legacy có
thể thiếu checksum; chỉ dùng metadata hiện có và phải xác nhận DB anchor.
**Không khẳng định checksum-lock cho dữ liệu legacy chưa có checksum.**
Không tự backfill version hay tự reindex dữ liệu cũ trong bước này.

PostgreSQL source không lấy embedding version mới của document đè lên version
cũ của chunk. Nó kiểm tra cả hai và bỏ row stale/conflicting. `artifact_version`
chỉ có trên document có thể là nhãn của storage manifest: không bịa field đó
cho chunk. Nếu chunk thật sự có field này thì phải khớp; source checksum và
các revision/policy thực sự đã khai báo vẫn kiểm tra chặt.

DB anchor phải tồn tại duy nhất tại đúng index, có vector ID và full text/hash
khớp seed. Seed stale sau re-ingestion không được mượn neighbors của bản mới.
Hai rows ở cùng index, active=false, blank text hoặc hash sai đều là barrier.

## 5. Citation và budget

Không nối neighbor text vào seed text. Mỗi neighbor là một `SearchResult`,
có deterministic Qdrant point ID của nó, metadata trang/chương riêng và:

~~~json
{
  "retrieval_source": "context_neighbor",
  "context_expanded_from": "seed-vector-id",
  "context_expansion_relation": "same_section",
  "context_expansion_distance": 1,
  "score_type": "seed_cosine"
}
~~~

Neighbor kế thừa score của seed, **không phải cosine được đo riêng**. Các IDs
seed/neighbor vẫn tách biệt đến prompt và citation builder. Compression giữ
budget toàn cục hiện có; có thể cắt text, bỏ evidence vì hết budget hoặc bỏ
text trùng hoàn toàn. Nó không gộp hai nguồn vào một citation hay chứng minh
LLM answer đúng về mặt semantic.

## 6. Kiểm tra và trạng thái

Full RAG suite: **416 passed, 10 warnings** trong 33.17s; thêm 89 unit tests
và 1 integration scenario cho bước 3. Warnings vẫn cùng loại Pydantic alias
đã có; HTTP scenario mới làm warnings lặp thêm, không được tính là đã sửa.
Baseline v1 và 66 tests v2 ở bước 2 vẫn qua.

Các tests kiểm tra boundaries, version/alias conflicts, SQL predicates, stale/
duplicate anchor, gap barriers, dedup/prioritization, page-local fallback,
checksum đi qua chunk path và compact vector payload. Integration scenario
chạy cleaner/chunker v2 → payload allowlist → DB source → retrieval/expansion
→ HTTP answer/prompt/citation thật, với adapters I/O và LLM deterministic.
Nó xác nhận neighbor được cite đúng trang/chương và chapter kế tiếp không
nằm trong prompt. **Không phải** live PostgreSQL/Qdrant/provider test hoặc
phép đo chất lượng trả lời trên PDF thật.

Từ folder `rag-service`:

~~~powershell
.\.venv\Scripts\python.exe -m pytest tests/unit/test_chapter_context_expansion.py tests/integration/test_chapter_context_answer.py -q
~~~

Code: [scope rules](../../app/retrieval/context_scope.py),
[expander/PostgreSQL source](../../app/retrieval/context_expander.py),
[ingestion](../../app/ingestion/pipeline.py),
[Qdrant allowlist](../../app/indexing/qdrant_store.py).
Tests: [unit](../../tests/unit/test_chapter_context_expansion.py),
[integration](../../tests/integration/test_chapter_context_answer.py).

Checkpoint mới: [bước 4 đã có benchmark PDF thật](real-book-chunking-benchmark.md).
[Heading regression fix](heading-detection-regression-fix.md) đã sửa v2 và
rerun: chapter-label gate pass trên corpus này; retrieval/held-out vẫn HOLD.
Guard theo chapter không tự sửa được boundary thật bị detector bỏ sót.
Bước 3 không đổi `.env`, services đang chạy, API/DTO frontend,
database schema hay golden dataset cũ.
