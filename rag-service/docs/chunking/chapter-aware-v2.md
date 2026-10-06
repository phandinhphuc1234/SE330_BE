# Chapter-aware chunking v2 — bước 2

Cập nhật: 2026-10-05. **Opt-in**, chưa thay mặc định `v1` và chưa reindex.
Checkpoint tổng: [kế hoạch bốn bước](chunking-baseline-and-boundary-plan.md).

## 1. v2 giải quyết vấn đề gì?

v1 nhận diện một chương cho cả trang rồi chia từng trang riêng. Vì vậy, phần
cuối chương cũ có thể bị gán sang chương mới, hai chương ngắn có thể chung
chunk, và một câu qua hai trang không xuất hiện đầy đủ trong cùng chunk.

v2 xác định ranh giới chương **trước khi** chia theo token. Nó đọc mọi dòng
trên trang, không chỉ tám dòng đầu; phần trước heading vẫn ở chương cũ.
SentenceSplitter chạy riêng cho từng section, nên overlap và quan hệ
previous/next của LlamaIndex không đi qua ranh giới section đó.

~~~text
Parsed pages → cleaner giữ từng trang → document profiler
  ├─ v1 mặc định → nhãn chương theo trang → splitter từng trang
  └─ v2 opt-in  → scan heading + offset → sections theo chương
                  → ghép trang liên tiếp cùng nguồn/chương
                  → splitter riêng từng section, 512/64
                  → map từng chunk về cleaned pages
                  → hash/vector ID + token count + quality gate
                  → chunks + metadata + citation pageStart/pageEnd
~~~

Đây là thay đổi ingestion/chunking, không thêm LLM call, embedding provider,
GraphRAG hoặc API mới.

## 2. Khi nào ghép và khi nào không?

- Ghép nội dung trong một chương đã nhận diện, chỉ qua các trang **liên tiếp**
  có cùng document/ebook/book identity và các trường version/checksum nếu có.
- Nếu trang trước kết thúc bằng câu chưa hoàn tất và trang sau bắt đầu bằng
  chữ thường, cả hai là văn xuôi: nối bằng một dấu cách.
- Trường hợp khác trong cùng chương: giữ ranh giới đoạn bằng hai newline,
  không tự đoán sửa từ bị ngắt hoặc bỏ dấu gạch nối qua trang.
- Không có chương đáng tin: fallback từng trang, không bịa tên chương.
- Heading mới, đổi nguồn/phiên bản, thiếu identity, nhảy/lặp số trang hoặc
  trang rỗng: không carry chương/nối nội dung qua ranh giới đó.
- Code fence có trạng thái qua các trang liên tiếp; dòng `Chapter 2` nằm
  trong code không tạo chương và không bị nối như văn xuôi.

Ví dụ các heading hỗ trợ: `Chương 2`, `Chapter IV`, `Chapter One: The Arrival`,
`**Chương 3 — Thư viện**`, `## Chapter 2 The Arrival`, `Epilogue`,
`##### **CHAPTER I.**`, `APPENDIX.`. Một dấu chấm kết thúc là punctuation
của heading; không nhận dotted leaders của TOC.

Khối toàn Markdown title headings trước chapter đánh số có thể kết thúc
carry Preface/Foreword/Introduction; nó được giữ không gán chương. Văn xuôi
trước heading giữa trang vẫn thuộc chương cũ. Chi tiết và giới hạn trong
[heading regression fix](heading-detection-regression-fix.md).

Không nhận các câu như `Chapter 2 explains the problem.`, dòng table/list/
blockquote/code, hội thoại có dấu ngoặc kép hoặc mục lục có dotted leaders.
Tiêu đề có thêm chữ nhưng thiếu separator/Markdown sẽ fallback bảo thủ.
Đây là heuristic, không phải mô hình hiểu cấu trúc sách hay parser mục lục
hoàn chỉnh; heading không chuẩn và running header vẫn cần review trên sách thật.

## 3. Mapping và citation

Metadata mới:

| Field | Ý nghĩa |
| --- | --- |
| chunking_strategy_version | `v2`; tên strategy vẫn `library_pdf_narrative` |
| chapter_detection_version/source | `v2` / `rule_based_line_boundary_v2` |
| section_id | ID ổn định trong một lần dựng sections có cùng input/thứ tự |
| section_char_start/end | Vị trí chunk trong text section đã ghép |
| source_mapping_version | `cleaned_page_chars_v1` |
| source_spans | Các đoạn nguồn thật sự nằm trong chunk |
| pageStart/pageEnd | Trang đầu/cuối **thực sự chạm**, không phải toàn bộ chương |

Ví dụ một chunk nối qua hai trang:

~~~json
{
  "pageStart": 1,
  "pageEnd": 2,
  "source_mapping_version": "cleaned_page_chars_v1",
  "source_spans": [
    {"pageNumber": 1, "sourceCharStart": 10, "sourceCharEnd": 40,
     "chunkCharStart": 0, "chunkCharEnd": 30},
    {"pageNumber": 2, "sourceCharStart": 0, "sourceCharEnd": 20,
     "chunkCharStart": 31, "chunkCharEnd": 51}
  ]
}
~~~

Số trong ví dụ chỉ minh họa. Khoảng trống ở vị trí 30 là dấu cách nối trang,
không giả vờ thuộc nguồn nào. Mỗi span phải thỏa:

~~~python
cleaned_page[sourceCharStart:sourceCharEnd] == chunk[chunkCharStart:chunkCharEnd]
~~~

Offsets dùng **Python Unicode character index, từ 0, end không bao gồm**.
Chúng trỏ vào text **sau cleaning**, không phải PDF byte offset, tọa độ bounding
box, text trước cleaning hay số trang in trên sách. `pageNumber` là số trang
parser/adapter cung cấp; mapping không khôi phục dữ liệu cleaner đã loại bỏ.

Các source IDs vẫn có ở metadata chunk. Citation API hiện giữ contract cũ:
book/ebook/document, chapter, page range, chunk index và vector ID; chưa thêm
character spans vào DTO frontend. PDF reader có thể dùng pageStart để mở
trang đầu, còn source_spans phục vụ kiểm thử/audit nội bộ.

Với text lặp, mapping kiểm tra tiến độ phủ nguồn và giới hạn overlap bằng
tokenizer mặc định của SentenceSplitter, không lấy lần xuất hiện đầu tiên
một cách mù quáng. Nếu không map chính xác được hoặc bỏ sót nội dung nguồn,
strategy raise error thay vì tạo citation suy đoán. token_count của ứng dụng
vẫn là ApproxTokenCounter, không phải Gemini tokenizer.

Artifacts cleaned pages của v2 vẫn giữ hình dạng từng trang và không áp nhãn
chương duy nhất lên một trang có nhiều chương. Nhãn chương/source mapping
nằm ở từng chunk và chunks artifact.

## 4. Code và cách kiểm tra không đụng production

Code: [detector](../../app/ingestion/chunking/chapter_boundaries.py),
[strategy](../../app/ingestion/chunking/chapter_aware_strategy.py),
[selector](../../app/ingestion/chunking/selector.py),
[acceptance tests](../../tests/unit/test_chapter_aware_chunking.py).

Trong folder `rag-service`:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --strategy v2
~~~

Xem nguồn và mapping đầy đủ:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --strategy v2 --json
~~~

Kiểm tra v1 không bị đổi:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --check
~~~

CLI dùng cùng 11 fixtures ở bước 1 qua nhánh pipeline thực, không gọi PDF
parser/provider/storage/DB. v1 snapshot giữ nguyên. v2 có acceptance tests;
chưa có reviewed v2 snapshot, nên `--strategy v2 --check` bị từ chối rõ ràng.
`v1KnownLimitations` trong report v2 là chú thích baseline, không phải điểm
đánh giá chất lượng của v2. Reports có thể chứa text sách: không commit
private report lên GitHub.

Config `CHUNKING_STRATEGY_VERSION=v1` đã có trong các env examples và được
truyền tới RAG qua unified/standalone/production Compose. `Settings` chỉ nhận
`v1` hoặc `v2`. Chưa sửa `.env` thật, chưa restart containers, chưa chạy job
ingestion/reindex. Sau benchmark được duyệt mới chọn `v2` cho worker.
Đổi flag chỉ tác động **ingestion mới**, không thay stored chunks/vectors.

## 5. Phần chưa làm

Verification: **326 RAG tests passed**, trong đó 66 acceptance tests mới cho
v2; 6 warnings Pydantic đã có. CLI v2 chạy 11 cases/19 pages/18 chunks đều
qua quality gate; v1 snapshot vẫn khớp. Chi tiết ở
[verification ledger](chunking-baseline-and-boundary-plan.md#8-verification-ledger).

**Bước 3 đã implement:** [context expansion theo chương/phiên bản](chapter-aware-context-expansion.md).
`section_id` và source checksum được giữ trong vector payload; expansion
kiểm tra current DB anchor và không qua boundary/gap. Retrieval vẫn có thể
chọn seeds độc lập từ nhiều chương; chúng không bị xóa chỉ vì khác chương.
Chất lượng nhãn chương trên mọi PDF thật vẫn cần kiểm tra ở bước 4.

**Bước 4 đã có [benchmark PDF thật](real-book-chunking-benchmark.md).**
Mốc benchmark đầu phát hiện terminal-period/Appendix/front-matter gaps.
[Bản sửa tiếp theo](heading-detection-regression-fix.md) đã xử lý nhánh v2
và oracle wrapper; BM25 v2 vẫn có regression trên Douglass. Giữ default v1,
không promote/reindex từ synthetic tests hoặc dev-set nhỏ.

Flow strategy: kiểm tra trang/scope → tìm boundaries → dựng sections và
source spans → sentence split → xác định vị trí không bỏ sót nguồn → map
page ranges → enrich metadata. Mục đích: giữ ranh giới chương và provenance
đúng khi câu/đoạn đi qua trang, trong khi vẫn bảo toàn v1 để đối chiếu.
