# Bước 4 — so sánh chunking trên PDF thật

Cập nhật: 2026-10-05. Đây là **development benchmark**, không phải chứng nhận
chất lượng production. Quyết định hiện tại: **giữ default v1, chưa reindex**.

Checkpoint mới: [sửa heading detection và rerun](heading-detection-regression-fix.md).
Các số `real-books-gemini-03` bên dưới là mốc **trước bản sửa detector**,
được giữ để đối chiếu, không ghi đè lịch sử.

## 1. Đã bổ sung gì?

- [Runner PDF thật](../../scripts/benchmark_book_chunking.py): kiểm tra magic
  bytes, cấu trúc, encryption, text layer và checksum trước khi parse.
- [Comparison engine](../../app/evaluation/chunking_comparison.py): chạy đúng
  cleaner/profile/chunker của ingestion cho cả v1/v2, pin size/overlap 512/64.
- [20 câu hỏi và source labels](../../tests/fixtures/chunking/real_books_v1.json)
  trên hai PDF khác kiểu: truyện ngắn không có chương và sách narrative nhiều chương.
- BM25 dùng `KeywordRetriever` thật. Dense dùng embedding Gemini thật và exact
  cosine trong bộ nhớ. Hybrid chạy `RetrievalPipeline`, query rewriting, BM25,
  weighted RRF và reranker thật với adapters chỉ mục in-memory.
- Context đo riêng: top-3 seeds → expander bước 3, window=1 → compressor với
  budget 12.000 ký tự. Chunks lân cận vẫn giữ citation độc lập.
- Cache parser/embedding, JSON/Markdown reports mới, và gate giữ v1 khi còn
  regressions hoặc thiếu đánh giá. Không có auto-promotion.

Không gọi `IngestionPipeline.run`, tạo job, upload, ghi PostgreSQL/S3, upsert
Qdrant, gọi generation/judge hoặc đổi runtime `.env`. Đây không phải test
Qdrant/Postgres production; không đo tốc độ hay chất lượng câu trả lời cuối.

## 2. Nguồn và tính lặp lại

| PDF | Trang vật lý | Câu hỏi | Đặc điểm |
| --- | ---: | ---: | --- |
| [The Gift of the Magi](https://www.ibiblio.org/ebooks/Henry/Gift_Magi.pdf) | 9 | 10 | Không có chương, có câu qua trang và bằng chứng nhiều trang. |
| [Narrative of the Life of Frederick Douglass](https://www.ibiblio.org/ebooks/Douglass/Narrative/Douglass_Narrative.pdf) | 126 | 10 | 11 chương + Appendix, headings Markdown, Roman numbers và dấu chấm. |

Nguồn [O. Henry](https://www.ibiblio.org/ebooks/Henry/) và
[Frederick Douglass](https://www.ibiblio.org/ebooks/Douglass/index.html) ghi
copyright status của tác phẩm gốc; PDF đầy đủ chỉ tải local, không đưa vào Git.
Số trang labels là **trang vật lý PDF bắt đầu từ 1**, không phải số in trên giấy.
Với Douglass, chapter audit bắt đầu từ trang 19; front matter chưa gán nhãn
không được tính như oracle. Appendix trang 119 có nhãn riêng.

PDF Magi này khác bản 6 trang của golden dataset HTTP cũ. Không tái dùng chunk
IDs/pages của bản cũ và không sửa dataset `gift_of_the_magi_v1.json` đang dùng.
SHA-256 được pin trong manifest mới; khác bytes phải review/gán nhãn lại, runner
không tự cập nhật checksum chỉ để benchmark qua.

Skill PDF được dùng để xem bố cục thực tế, xác nhận heading/trang nguồn và
đối chiếu parser. Một bản Alice tải thử có encryption nên bị loại theo policy
hiện tại; không bypass validator. Render mẫu có cảnh báo thiếu font Symbol/
ArialUnicode nhưng nội dung/heading mẫu đọc được. Các parser artifacts có thể
còn lỗi glyph ở bìa: coverage bên dưới chỉ đo **text sau cleaning**, không
chứng minh parser khôi phục nguyên vẹn mọi chữ/glyph của PDF gốc.

Cache parser khóa theo checksum, versions PyMuPDF/PyMuPDF4LLM và hash source
adapter. Cache embedding khóa theo task document/query + model + dimension +
version + text policy + input text. Query vectors được dùng chung giữa v1/v2;
không embedding query hai lần chỉ vì đổi chunker. Cache chỉ ghi hash/vector,
không ghi API key hoặc Settings. Parsed cache vẫn chứa text nguồn và chỉ ở local.

## 3. Chấm công bằng khi chunk IDs khác nhau

Labels là `page + quote`, không phải expected chunk IDs. Mỗi quote phải có
đúng một vị trí trên cleaned page; chỉ bỏ qua khác biệt whitespace khi locate,
không đổi chữ, hoa/thường hay punctuation. Label mất/không duy nhất thì dừng.

- v1 map exact substring về trang cleaned; substring lặp mơ hồ thì dừng.
- v2 audit từng `source_spans`: text bằng chính xác source slice, mọi ký tự
  không phải whitespace của chunk đều mapped; page citation khớp span thật.
- Cả hai phải dùng cùng cleaned-page hashes. Chunk trùng/overlap không được
  tính source coverage hai lần.

Các metric trong report:

| Metric | Ý nghĩa |
| --- | --- |
| Evidence Recall@1/3/5 | Tỷ lệ anchors được phục hồi đầy đủ bằng union source positions trong top-k. |
| Source-character coverage@k | Tỷ lệ ký tự bằng chứng được phủ, hữu ích khi chỉ lấy được nửa câu. |
| Hit@k | Ít nhất một anchor phục hồi đủ. |
| Complete@k | Tất cả anchors của câu hỏi phục hồi đủ, không chỉ một trong hai facts. |
| MRR@k | Rank đầu tiên của chunk phủ ít nhất 50% một anchor; threshold cố định cho cả hai phiên bản. |
| Context-retained anchor rate | Quote đầy đủ còn trong `context_text` của một citation đúng trang sau expansion/compression. |
| Source coverage | Phủ toàn bộ ký tự không phải whitespace của cleaned pages, không phải PDF bytes. |
| Chapter-label mismatch | So nhãn chunk với headings tự review, không lấy output detector làm ground truth. |

Context-retention là phép đo **strict single-citation**, khác source recall có
thể ghép coverage từ nhiều chunks. Không đánh đồng hai con số. Chapter audit
ghi cả audited chunk count/rate: raw mismatch count giảm chỉ vì ít chunks hơn
không chứng minh nhãn chương tốt hơn.

## 4. Cách chạy

Từ `rag-service`, đặt PDF đúng filename ở `data/chunking-comparison/`.
Runner không tự tải URL để tránh download ngoài ý muốn; sources có trong manifest.
Folder này đã được ignore vì có thể chứa private text hoặc provider artifacts.

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_book_chunking --output data/chunking-comparison/my-offline-run.json
~~~

Opt-in Gemini embedding thật (có quota/chi phí, dùng key hiện có):

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_book_chunking --gemini --max-embedding-texts 400 --interval-seconds 2 --output data/chunking-comparison/my-gemini-run.json
~~~

Budget đếm tổng unique document/query inputs trước khi gọi provider, không phải
token hay tiền. Batch document=16, max retries=0; pacing giữa requests. Pacing
không giải quyết hết quota ngày. Cache hit có thể tránh provider calls khi chạy
lại. Đổi output filename mỗi lần: JSON hoặc MD đã tồn tại đều bị từ chối trước I/O.

Provider failure giữ kết quả BM25 đã chạy và đánh dấu `errorCount>0`,
`embedding.status=failed`, exit code 1. Modes thiếu/chạy dở không phải baseline
dense/hybrid đạt. Raw provider error không đưa vào report vì có thể chứa secret.

Kiểm tra baseline cũ và tests:

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_chunking --check
.\.venv\Scripts\python.exe -m pytest -q
~~~

## 5. Kết quả kiểm tra hiện tại

Offline run `real-books-offline-01`: parse PDF thật, 135 trang, 20 câu hỏi;
không error. Hai phiên bản cùng cleaned hashes, source coverage 100%.

| Book | Strategy | Chunks | BM25 Evidence Recall@3 | MRR@5 | Context retained |
| --- | --- | ---: | ---: | ---: | ---: |
| Magi | v1 | 9 | 0.85 | 0.65 | 0.85 |
| Magi | v2 | 9 | 0.85 | 0.65 | 0.85 |
| Douglass | v1 | 128 | 0.70 | 0.60 | 0.70 |
| Douglass | v2 | 124 | 0.60 | 0.50 | 0.60 |

Đây là 20 development questions gán nhãn trong lượt này; không tune labels
theo ranking để lấy số đẹp. Truy vấn English, chưa có test-set Vietnamese
held-out. Không suy rộng Recall trên hai sách thành chất lượng toàn library.

**Bug/giới hạn thực tế phát hiện:** v1 không strip Markdown heading nên bỏ
sót `##### **CHAPTER I.**`; v2 có strip markup nhưng đang từ chối suffix `.`.
Cả hai mang nhãn PREFACE sang phần thân sách. v2 có thể nối qua chương thật
bị bỏ sót; chapter-safe expansion chỉ an toàn khi nhãn/boundaries đúng.
Appendix chưa thuộc detector v2. Các mismatch được giữ trong report, không
thay oracle hoặc PDF để che vấn đề. Chưa sửa detector trong bước benchmark này.

Final run `real-books-gemini-03`: **errorCount=0**, cả BM25/dense/hybrid đã chạy
đủ 20 câu cho v1/v2. Dùng Gemini `gemini-embedding-2`, dimension 3072,
version `gemini-embedding-2-3072-v1`, text policy `gemini_search_title_text_v1`.
277 unique document/query inputs nằm trong budget 400; lần chạy cuối phát sinh
22 provider requests, các inputs còn lại được tái dùng từ cache hợp lệ. Không
gọi generation/judge. Provider/pacing giữa requests=15 giây, retries=0.

| Book | Strategy | Mode | Evidence Recall@3 | MRR@5 | Context retained |
| --- | --- | --- | ---: | ---: | ---: |
| Magi | v1 | dense | 0.80 | 0.783 | 0.80 |
| Magi | v2 | dense | 0.80 | 0.783 | 0.80 |
| Magi | v1 | hybrid | 0.90 | 0.900 | 0.90 |
| Magi | v2 | hybrid | 0.90 | 0.900 | 0.90 |
| Douglass | v1 | dense | 1.00 | 0.950 | 1.00 |
| Douglass | v2 | dense | 0.80 | 0.683 | 0.90 |
| Douglass | v1 | hybrid | 0.80 | 0.775 | 0.85 |
| Douglass | v2 | hybrid | 0.80 | 0.687 | 0.90 |

v2 không tăng Recall trên mẫu không có chương; trên Douglass BM25/dense có
regression và hybrid cùng Recall nhưng MRR giảm. Context retention hybrid
tăng 0.85 → 0.90 không đủ bù chapter labels sai hoặc ranking regression.
Với Douglass, chapter audit: v1 111/111, v2 108/108 audited chunks có mismatch,
không coi count 108 thấp hơn 111 là cải thiện. Mapping/coverage pass chỉ chứng
minh source chars/trang còn đúng, không chứng minh nhãn chương/semantic tốt.

Run `real-books-gemini-02` có **errorCount=1**: Magi hoàn tất nhưng provider lỗi
giữa document embedding Douglass. Giữ report cho traceability, không dùng làm
baseline semantic đầy đủ. Lần 03 chạy chậm hơn và reuse cache thành công;
không khẳng định nguyên nhân chính xác của lỗi cũ vì raw SDK error không lưu.
Reports đầy đủ nằm local ở `data/chunking-comparison/real-books-*.json/.md`;
bảng đã review trong tài liệu này không chứa private PDF text/vectors/keys.

Full RAG suite sau thay đổi code cuối: **448 passed, 10 warnings**, 46.51s;
thêm 32 tests benchmark/source labels/cache/budget/provider diagnostics.
Warnings Pydantic alias cùng các tests cũ, không phải fail. CLI v1 `--check`
vẫn khớp reviewed snapshot 11 synthetic cases; không regenerate baseline.
Tests kiểm tra exact source mapping, page labels, union/strict-context metric,
scoped real-vector-only modes, forbidden writes, input/checksum/overwrite guards,
cache tasks/dimension separation và provider errors không leak secret.

## 6. Quyết định và bước tiếp theo

**HOLD default v1.** Final gates: source coverage PASS, dense/hybrid completed
PASS, chapter labels FAIL, no-measured-regression FAIL, held-out Vietnamese
NOT RUN. Runner ghi gates source coverage, chapter-label accuracy,
dense/hybrid completion, retrieval/context regression và held-out Vietnamese.
Không có automatic promotion dù mọi gate trong dev-set qua.

Việc tiếp theo: sửa boundary detection cho heading thực tế (terminal period,
Appendix, chống carry sai từ front matter) bằng regression fixtures rồi chạy
lại **cùng labels/PDF/checksum**. Sau đó thêm sách/câu hỏi Vietnamese + held-out;
review kết quả trước khi người dùng chọn v2 và lên kế hoạch reindex có rollback.
Không cần parent-child/semantic chunking hoặc GraphRAG để sửa các fundamentals này.

Flow: manifest/bytes validation → parse thật/cache hợp lệ → hai nhánh clean/
chunk giống config → audit source/chapter → retrieval/context metrics → report
mới + gate HOLD. Mục đích là đưa ra quyết định từ evidence, không đổi production.
