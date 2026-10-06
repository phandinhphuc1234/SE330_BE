# Bước tiếp theo: PDF tiếng Việt thật và baseline mới

Cập nhật: 2026-10-06. Đã chuẩn bị source/questions, chạy BM25 thật + exact replay.
Chủ đồ án đã duyệt toàn bộ 20 câu/23 anchors và 7 heading **sau lần chấm BM25**;
[biên bản theo đúng version/hash](../../tests/fixtures/chunking/vietnamese_source_owner_review_v1.json)
lưu riêng. [Embedding baseline tiếp theo](vietnamese-source-embedding-baseline-v1.md)
giữ 96 vectors từ lượt lỗi đầu, rồi bổ sung đủ 120 missing inputs theo quyền
riêng với token-aware pacing. Cache đủ 216 vectors, chấm đủ 120 cases và 0 errors;
Hybrid Recall@3 v1/v2 là 92,5%/90%. Vẫn HOLD, chưa đổi ranking/chunker production.
Phần số liệu bên dưới giữ checkpoint first-evaluation; không sửa report cũ hồi tố.

## 1. Nguồn được chọn

Ngân hàng Thế giới. 2022. *Từ chặng đường cuối đến chặng đường kế tiếp - Đánh
giá thực trạng nghèo và bình đẳng của Việt Nam năm 2022 - Tổng quan (Tiếng Việt).*
Washington, DC: Ngân hàng Thế giới.
[PDF chính thức](https://documents1.worldbank.org/curated/en/099910004262241595/pdf/P1762610100be30c90a1a7053e9b39aa347.pdf).
Giấy phép [CC BY 3.0 IGO](https://creativecommons.org/licenses/by/3.0/igo/)
ghi trên trang PDF vật lý 3. Chỉ dùng các đoạn văn của báo cáo làm evidence;
không tách ảnh/biểu đồ bên thứ ba để tái sử dụng.

Đây là bản dịch tiếng Việt chính thức của một báo cáo phi hư cấu, **không phải
tiểu thuyết gốc tiếng Việt**. Các câu hỏi/đáp án tóm tắt là phần chỉnh lý phục vụ
evaluation, không được World Bank chứng thực. Những quan điểm và nhận định
trong phần chỉnh lý thuộc trách nhiệm của tác giả phần chỉnh lý, không phải
của World Bank. Những số liệu/chính sách được hỏi là nội dung lịch sử của báo
cáo 2022, không phải khẳng định về hiện trạng năm 2026.

| Thuộc tính | Giá trị đã kiểm tra |
| --- | --- |
| File local (ignored) | `data/chunking-comparison/vietnam-poverty-overview-2022-vi.pdf` |
| Kích thước | 1.614.712 bytes, 32 trang PDF vật lý |
| PDF SHA256 | `4394395d2399aa986ccfab1c9eafca90b44f21ebf64de5342c9fe6897b6c22d5` |
| Source / query language | `vi` / `vi` |
| Manifest | [vietnamese_source_v1.json](../../tests/fixtures/chunking/vietnamese_source_v1.json) |
| Raw manifest SHA256 | `b69caf1f7ba2dcd5a83f5e264613e9e2776700c4ab387bf3ee71beeac8758ba6` |
| Questions / evidence | 20 positive questions, 23 exact page/quote anchors |
| Review khi chấm BM25 lần đầu | `assistant_source_checked`; user review khi đó còn pending |
| Review hiện tại | Chủ đồ án đã chấp thuận toàn bộ ngày 2026-10-06; biên bản riêng theo hash |

Đã render và đối chiếu trực quan trang giấy phép, các trang có evidence và
heading chính; quote cũng được kiểm tra với cleaned text của **cả v1/v2**.
Số trang label là số trang PDF vật lý; trang in 1 tương ứng PDF 9. Các câu hỏi
gồm số liệu/đơn vị, định nghĩa, paraphrase, abbreviation, hai ý/hai anchors,
footnote và tổng hợp từ hai trang. Không dùng chunk IDs của riêng một chunker
để xây ground truth. Không đưa câu hỏi cần đọc biểu đồ vào bộ thử prose này.

Source/query set không trùng với hai PDF English trước. Nhãn và 20 câu đã khóa
**trước khi chấm retrieval**. Source được assistant đọc để soạn nhãn, tại lần
chấm đầu chưa được user review, và manifest khai báo `split=development`. Đây chỉ là
fresh-source first evaluation; sau khi quan sát kết quả, nó trở thành regression
set, không được dùng để tune rồi gọi là blind holdout. Owner approval sau đó
không trở thành independent blind expert review; không đổi manifest SHA256
hoặc metadata/gates `pending` trong hai reports trước review. Runner first-evaluation
giữ snapshot provenance cũ; lượt Dense/hybrid tiếp theo phải kiểm tra biên bản
approval riêng trước khi ghi nhận gate review hiện tại.

## 2. Code và flow đã thêm

- [Registration / cache inspector](../../app/evaluation/vietnamese_source_validation.py):
  kiểm tra raw manifest hash, source identity và seals cũ; giữ snapshot review
  pending tại first scoring, không sửa hồi tố registration;
  embedding identity là dữ liệu công khai cố định, không lấy secret từ `.env`.
- [Runner](../../scripts/benchmark_vietnamese_source.py): checksum/parse/clean/chunk
  hai versions → anchor/source/section audit → BM25/context → cache-only preflight
  → kiểm tra seals lần nữa → NEW private JSON/Markdown. Script có comments và
  flow/purpose cuối file. Không có cờ gọi provider, DB/index/API write hay LLM.
- [Phiếu review 20 câu](vietnamese-source-review-v1.md): câu hỏi/đáp án tóm tắt,
  số trang vật lý/in, cách review và cách tạo version mới khi sửa nhãn.
- [30 tests mới](../../tests/unit/test_vietnamese_source_validation.py): immutable
  source/query/provenance, seals cũ, cache task/text dedup, missing vs corrupt
  real vectors, no fallback/.env/provider/storage/DB, anchors-before-cache,
  honest pending gates, double seal check, output collision và secret redaction.

Runner generic [benchmark_book_chunking.py](../../scripts/benchmark_book_chunking.py)
đã bỏ limitation hardcode “Two English books”, lấy số source/ngôn ngữ thật từ
manifest. Cache offline cũng từ chối vector chứa boolean/non-numeric values,
ngoài dimension/finite/nonzero checks sẵn có. Không sửa public retrieval code.

Fixed policy v1 hash vẫn là
`c278ba9529bec41bcb829917e2c8c22646bf9600f8920c8f776f4db25b963fe9`;
adaptive v2 hash vẫn là
`0b4a4499d97b6c6fac0e2daa150a5139a114652d20a8e0b5c742665934c0faac`.
Chúng được kiểm tra, **không được chấm trên source mới ở bước này**.

## 3. Số liệu đo thật

Reports riêng tư, ignored:
`data/chunking-comparison/vietnamese-source-first-23.json` và
`vietnamese-source-replay-24.json`, mỗi file có Markdown tương ứng.
Replay khớp **mọi field trừ `generatedAt`**, gồm cases/citations/context,
source hashes, metrics, seals và cache readiness.

40 cases = 20 câu × 2 chunkers. Cả hai dùng cùng parser/cleaned pages,
chunk size/overlap **512/64 tokens**, topK 1/3/5, context lấy 3 seeds,
neighbor window 1, budget 12.000 **characters**. Không lẫn đơn vị token/character.

| Chỉ số | v1 | v2 |
| --- | ---: | ---: |
| Chunks | 114 | 111 |
| Chunks qua nhiều trang | 0 | 19 |
| Non-whitespace source coverage | 100% | 100% |
| BM25 evidence Recall@3 | 85% | 87,5% |
| BM25 MRR@5 | 0,8375 | 0,8875 |
| Context retained anchor rate | 85% | 90% |
| Context lớn nhất | 7.852 characters | 9.133 characters |
| Section mismatches / audited chunks | 97/97 | 30/94 |
| Chunks trộn section theo oracle | 5 | 3 |

MRR dùng relevance ≥50% của ít nhất một anchor; Recall@3 đòi đủ ký tự của
anchor trong union các seeds. Context retention còn nghiêm hơn: phải nằm trong
**một citation/context**. Vì vậy MRR/Recall/context không phải ba tên cho cùng
một chỉ số. Các con số này là retrieval evidence, không phải answer accuracy.

3 câu còn đáng kiểm tra, không sửa nhãn hoặc tăng k sau khi xem điểm:

| Case | v1 Recall@3 / MRR@5 / context | v2 Recall@3 / MRR@5 / context |
| --- | --- | --- |
| `vi-poverty-rate` | 0 / 0 / 0 | 0 / 0 / 0 |
| `vi-high-income-goal` | 0 / 0,25 / 0 | 0 / 0,25 / 0 |
| `vi-digital-two-domains` | 0 / 0 / 0 | 0,5 / 0,5 / 1 |

Câu cuối có 2 anchors ở PDF 26/27. v2 top-3 chỉ tìm đủ 1 anchor, nhưng
neighbor expansion giữ được cả 2 trong context. Đây là ví dụ context giúp bù
thiếu evidence ở seeds, không có nghĩa câu trả lời LLM đã được chấm đạt.

Section oracle có 7 heading chính (front matter trước PDF 9 không chấm).
Strategy narrative nhận được một số heading đánh số `Phần I/II`, nhưng chưa
bao quát các heading báo cáo không đánh số như `Giới thiệu`, `Tham khảo`,
`Chú giải`. Không đổi oracle theo metadata của chunker để che 30 mismatches.
Audit này chưa có hierarchy các tiểu mục; nó cũng không kiểm tra table/figure
parsing. Không suy ra rằng narrative chunker đã phù hợp mọi report/textbook.

## 4. Dense/hybrid còn thiếu gì?

Cache-only preflight theo identity `gemini-embedding-2`, dimension 3072,
version `gemini-embedding-2-3072-v1`, policy `gemini_search_title_text_v1`:

| Input | Distinct inputs | Có cache hợp lệ | Thiếu |
| --- | ---: | ---: | ---: |
| Documents (union v1/v2) | 196 | 0 | 196 |
| Original queries | 20 | 0 | 20 |
| Tổng | 216 | 0 | 216 |

**0 provider calls**, 0 runtime writes, 0 LLM generation. Không fake vectors
hoặc fallback sang provider; missing cache là `blocked_missing_real_cache`,
không giả thành lỗi BM25 và không giả thành Dense/hybrid pass. Corrupt cache
làm runner thất bại thay vì coi như thiếu và tự làm lại. Vì chưa gọi Gemini,
không có số liệu token billing/chi phí thực tế; 216 là số unique embedding
inputs, không phải 216 requests hoặc 216 tokens.

## 5. Chạy lại và verification

Từ `rag-service`, source PDF cần có tại đường dẫn ignored ở trên. Muốn lấy
nguồn trên máy khác, dùng URL chính thức và kiểm tra SHA256; không commit
binary/source text/cache/report vào repo hoặc ghi đè source đang có.

~~~powershell
.\.venv\Scripts\python.exe -m scripts.benchmark_vietnamese_source --output data/chunking-comparison/my-vietnamese-source.json
~~~

Hai output `.json`/`.md` phải chưa tồn tại. Không có `--gemini`. Giữ nguyên
dataset/source/policy seals; sửa nhãn phải tạo version mới có change log.

Verification: final full RAG suite **701 passed, 10 warnings**, 14.29s; tăng 30 tests
so với checkpoint 671. Narrow related suites **68 passed**. Pydantic alias
warnings là warnings cũ. Reviewed v1 snapshot 11 cases được kiểm tra riêng
bằng CLI và vẫn khớp. Kiểm tra 138 local documentation links: không link gãy;
scoped whitespace check không lỗi. Source/query hash và hai policy seals cũ
vẫn khớp, source/report/cache/render previews được Git ignore.
Spring/Next build và production load test không nằm trong lượt này.

## 6. Thứ tự tiếp tục

1. **Đã hoàn tất:** bạn duyệt [20 câu và 7 section headings](vietnamese-source-review-v1.md)
   qua xác nhận `mình duyệt hết nha`. Biên bản ghi theo ID/hash và thời điểm
   sau first BM25 evaluation; không chỉnh hồi tố report cũ. Test mới kiểm tra
   đầy đủ ID/anchor count/heading pages và giới hạn authority của approval.
2. **Đã hoàn tất baseline:** [real embedding run](vietnamese-source-embedding-baseline-v1.md)
   cache 96 inputs từ lượt lỗi; user duyệt riêng tối đa 120 missing inputs.
   Token-aware completion gửi đúng 120, 0 errors, đủ 216 vectors và chấm đủ
   120 BM25/Dense/Hybrid cases. Không tự retry, thử weights hoặc gọi LLM answer/judge.
3. **Đã chẩn đoán:** [source/heading/ranking traces](vietnamese-baseline-diagnostics-v1.md)
   đủ 40 case-version pairs, 120 exact baseline replay cases, 0 provider calls.
   23/23 anchors có full single-chunk source ở mỗi chunker; v2 chỉ nhận 2/7
   headings. Tiếp theo là section-detection v3 experiment riêng, source audits
   trước và replay corpus cũ+mới; inputs embedding mới cần quyền/budget riêng.
   Không promote chỉ vì macro Recall trên một báo cáo tăng 2,5 điểm phần trăm.
4. Sau đó bổ sung nguồn tiếng Việt khác và negative/abstention cases, review
   trước khi chấm. Không coi bộ hiện tại là đủ để kết luận production quality.

Flow/purpose: chọn nguồn có quyền sử dụng → đọc/render nguồn → khóa câu hỏi và
page/quote labels → chạy BM25 hai chunkers → audit source/context/sections →
preflight real cache → ghi report + user review checklist → HOLD các gate còn
thiếu. Mục đích là mở rộng bằng chứng tiếng Việt một cách trung thực, không
tune trên các câu đã biết hoặc vô tình thay đổi production.
