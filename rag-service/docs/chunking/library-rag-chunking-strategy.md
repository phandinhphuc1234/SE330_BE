# Library RAG Chunking Strategy

Checkpoint mới 2026-10-05:
[baseline v1 và kế hoạch sửa ranh giới trang/chương](chunking-baseline-and-boundary-plan.md).
Bước 2 đã có [chapter-aware/source-mapped v2](chapter-aware-v2.md) opt-in;
mặc định vẫn v1. Bước 3 có [context expansion cùng chương/phiên bản](chapter-aware-context-expansion.md);
Bước 4 có [benchmark PDF thật](real-book-chunking-benchmark.md);
[heading fix và rerun](heading-detection-regression-fix.md) đã xử lý gaps
v2 trên corpus này. Giữ v1 vì retrieval regression và thiếu held-out review.
Các dòng trạng thái cũ trong doc này là roadmap lịch sử; embedding/search/
evaluation hiện đã có, xem [implementation ledger](../retrieval-evaluation-implementation.md).

Tài liệu này nối lại giữa lý thuyết trong
`docs/chunking/04-chunking-strategy.md` và implementation hiện tại của hệ thống
Library RAG.

Mục tiêu không phải là chọn một chunk size bằng cảm giác, mà là có một chiến
lược chunking có tên, có version, có metadata, có test, và có đường nâng cấp rõ
ràng bằng LlamaIndex.

---

## 1. Kết luận nhanh

Hiện tại project **đã có chunking**, nhưng mới ở mức baseline:

```text
ParsedDocument từng page
  -> LlamaIndex Document từng page
  -> PdfCleaningTransformation
  -> LlamaIndex SentenceSplitter
  -> LlamaIndex Nodes
  -> internal Chunk
  -> PostgreSQL document_chunks
```

Nó đang dùng:

```text
chunker = llamaindex_sentence_splitter
chunk_size = 512
chunk_overlap = 64
```

Đây là điểm bắt đầu ổn, nhưng chưa phải một chunking strategy production hoàn
chỉnh như tài liệu `04-chunking-strategy.md` mô tả.

Các phần còn thiếu quan trọng:

- chưa có `chunking_strategy_version` rõ ràng;
- chưa có strategy selector theo loại tài liệu;
- chưa có heading-aware chunking;
- chưa có parent-child chunking;
- chưa có semantic chunking;
- đã có chunk quality validation C4 trước khi embed;
- đã có token count ước lượng trong metadata bằng `ApproxTokenCounter`;
- đã có artifact foundation trong `rag-artifacts`: parsed/cleaned/chunks/report/manifest;
- chưa có evaluation để so sánh strategy.

---

## 2. Hiện tại code đang làm gì?

### 2.1. Main path hiện tại

Main path PDF ingestion hiện tại nằm ở:

```text
app/ingestion/pipeline.py
app/ingestion/llama_index/document_adapter.py
app/ingestion/llama_index/pdf_cleaning_transformation.py
app/ingestion/llama_index/node_adapter.py
```

Trong `node_adapter.py`, project đang dùng LlamaIndex:

```python
from llama_index.core.node_parser import SentenceSplitter
```

`SentenceSplitter` tạo `Node`, sau đó project map `Node` về internal `Chunk`.

### 2.2. Các chunker cũ

Project vẫn còn:

```text
app/ingestion/chunkers/base.py
app/ingestion/chunkers/recursive_chunker.py
app/ingestion/chunkers/section_chunker.py
app/ingestion/chunkers/semantic_chunker.py
```

Nhưng các file này hiện không phải path PDF chính nữa.

Tình trạng:

- `RecursiveChunker`: dùng LangChain `RecursiveCharacterTextSplitter`, baseline cũ.
- `SectionChunker`: regex đơn giản cho `Chương` / `Điều`.
- `SemanticChunker`: placeholder, chưa implement.

Vì project đang chuyển sang LlamaIndex, các chunker cũ nên được xem là legacy
hoặc fallback. Không nên phát triển song song hai hệ chunking khác nhau nếu
không có lý do rõ.

---

## 3. Vì sao baseline hiện tại chưa đủ?

`SentenceSplitter` là lựa chọn tốt để bắt đầu vì nó không cắt bừa giữa câu như
fixed character splitter.

Nhưng sách/PDF trong thư viện có nhiều dạng:

- sách giáo trình có chapter/section/subsection;
- sách kỹ thuật có heading, list, code block, table;
- sách scan không có text layer;
- sách có bảng dài;
- sách tiếng Việt có mục `Chương`, `Bài`, `Mục`, `Điều`;
- ebook có heading bị parser chuyển thành Markdown.

Nếu chỉ dùng page-level `SentenceSplitter`, ta gặp các rủi ro:

```text
Heading nằm ở chunk trước, nội dung nằm ở chunk sau
→ retrieval mất ngữ cảnh.

Một page có nhiều chủ đề
→ chunk có thể trộn nhiều ý.

Một section kéo qua nhiều page
→ hệ thống không biết các chunk cùng section.

Table bị cắt ngang
→ answer sai quan hệ row/column.

Không có chunking version
→ sau này đổi strategy không biết chunk nào được tạo bởi logic nào.
```

Vì vậy strategy nên đi theo hướng:

```text
Baseline ổn định trước
→ thêm metadata/version
→ thêm quality gate
→ thêm heading-aware
→ sau đó mới parent-child / semantic
```

---

## 4. LlamaIndex có thể dùng gì cho chunking?

Trong môi trường hiện tại, LlamaIndex core đã có các NodeParser/TextSplitter phù
hợp:

```text
SentenceSplitter
SemanticSplitterNodeParser
HierarchicalNodeParser
MarkdownNodeParser
TokenTextSplitter
```

Ý nghĩa với project:

| LlamaIndex component | Dùng cho | Nên dùng khi nào |
|---|---|---|
| `SentenceSplitter` | baseline sentence-aware chunking | hiện tại, default cho PDF thường |
| `MarkdownNodeParser` | heading-aware theo Markdown | khi PyMuPDF4LLM/Docling parse ra Markdown giữ heading tốt |
| `HierarchicalNodeParser` | parent-child chunks | khi muốn retrieve chunk nhỏ nhưng trả context lớn |
| `SemanticSplitterNodeParser` | semantic chunking | sau khi đã có embedding provider ổn định |
| `TokenTextSplitter` | kiểm soát token budget chặt | fallback cho text thiếu cấu trúc |

Quan điểm cho đồ án này:

```text
Không tự viết splitter document thủ công nếu LlamaIndex đã có.
Code nội bộ chỉ nên làm:
- chọn strategy;
- enrich metadata;
- validate chunk quality;
- map LlamaIndex Node về DB/Qdrant contract.
```

---

## 5. Strategy đề xuất cho Library RAG

### 5.1. Strategy đầu tiên: tiểu thuyết / narrative book

Tên đề xuất:

```text
library_pdf_narrative_v1
```

Áp dụng:

```text
source_type = LIBRARY_EBOOK
content_type = pdf
parser = pymupdf4llm
document_profile = novel_narrative | normal_narrative_text
```

Đây là loại nên làm trước nếu mục tiêu hiện tại là xử lý sách tiểu thuyết.

Chi tiết triển khai riêng cho tiểu thuyết nằm ở:

```text
docs/chunking/novel-chunking-strategy.md
```

Đặc điểm:

- nội dung chủ yếu là đoạn văn liên tục;
- ít bảng;
- ít code;
- ít công thức;
- ít yêu cầu table/cell relation;
- citation theo page/chapter là đủ tốt ở giai đoạn đầu;
- chunking nên ưu tiên giữ câu/đoạn văn tự nhiên.

Flow:

```text
Cleaned LlamaIndex Documents, mỗi page là một Document
  -> SentenceSplitter(chunk_size=512, chunk_overlap=64)
  -> Nodes
  -> enrich metadata
  -> internal Chunk
```

Metadata bắt buộc:

```json
{
  "document_profile": "novel_narrative",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunker": "llamaindex_sentence_splitter",
  "chunk_size": 512,
  "chunk_overlap": 64,
  "chunk_index": 0,
  "chunk_hash": "sha256",
  "pageStart": 12,
  "pageEnd": 12,
  "chapter_index": 1,
  "chapter_title": "Chương 1",
  "section_path": ["Chương 1"],
  "token_count": 384,
  "token_counter": "approx_whitespace_char_v1",
  "embedding_status": "pending"
}
```

Vì sao dùng `SentenceSplitter` trước:

- tiểu thuyết cần giữ câu văn tự nhiên;
- chưa cần semantic chunking ngay;
- dễ debug;
- gần với code hiện tại nhất;
- chưa kéo thêm độ phức tạp của heading/table/code/math.

Lưu ý quan trọng:

```text
V1 giữ page boundary để citation/debug dễ.
V2 có thể chunk theo chapter hoặc merge vài page liền nhau nếu evaluation cho thấy page boundary làm mất mạch truyện.
```

Tên cũ `library_pdf_sentence_v1` có thể xem là baseline kỹ thuật. Với roadmap mới
của hệ thống thư viện, strategy chính thức đầu tiên nên đặt tên theo loại tài
liệu:

```text
library_pdf_narrative_v1
```

### 5.2. Strategy cho PDF có Markdown heading tốt

Tên đề xuất:

```text
library_pdf_markdown_heading_v1
```

Áp dụng khi parser output có heading Markdown đáng tin:

```markdown
# Chapter 1
## Section 1.1
### Subsection
```

Flow:

```text
Cleaned Markdown Documents
  -> MarkdownNodeParser
  -> nếu node quá dài thì SentenceSplitter split tiếp
  -> enrich section_path metadata
  -> Chunk
```

Mục tiêu:

- không tách heading khỏi nội dung;
- attach `section_path` vào từng chunk;
- embedding text có thể thêm header context.

Metadata thêm:

```json
{
  "section_path": ["Chapter 1", "Section 1.1"],
  "section_title": "Section 1.1",
  "heading_level": 2
}
```

Chiến lược này phù hợp với sách giáo trình/kỹ thuật hơn baseline.

### 5.3. Strategy mặc định fallback cho text thường

Tên đề xuất:

```text
library_pdf_sentence_v1
```

Áp dụng khi hệ thống chưa đủ tín hiệu để xếp tài liệu vào nhóm rõ ràng hơn.

Flow giống `library_pdf_narrative_v1`, vẫn dùng:

```text
LlamaIndex SentenceSplitter
```

Khác biệt chính là ý nghĩa:

```text
library_pdf_narrative_v1
→ sách kể chuyện/đoạn văn dài, ưu tiên trải nghiệm đọc theo mạch văn.

library_pdf_sentence_v1
→ fallback chung cho text PDF chưa phân loại được.
```

### 5.4. Strategy parent-child giai đoạn sau

Tên đề xuất:

```text
library_pdf_parent_child_v1
```

LlamaIndex component:

```text
HierarchicalNodeParser
```

Flow:

```text
Parent node: section/page group lớn
Child node: chunk nhỏ để embed/search

Embed child
Retrieve child
Expand về parent hoặc neighboring chunks khi tạo context
```

Metadata cần:

```json
{
  "chunk_level": "child",
  "parent_chunk_id": "doc-7-parent-12",
  "section_path": ["Chapter 1", "Section 1.1"]
}
```

Chưa nên làm ngay nếu retrieval/context expansion chưa có, vì parent-child cần
thêm logic ở search phase.

### 5.5. Strategy semantic chunking giai đoạn sau nữa

Tên đề xuất:

```text
library_pdf_semantic_v1
```

LlamaIndex component:

```text
SemanticSplitterNodeParser
```

Không nên làm trước embedding/Qdrant, vì semantic chunking cần embedding model
ngay trong ingestion để phát hiện điểm đổi chủ đề.

Phù hợp:

- sách dạng narrative;
- note/wiki không có heading tốt;
- tài liệu nhiều chủ đề trong một page.

Không phù hợp làm default ban đầu vì:

- ingestion chậm hơn;
- tốn compute hơn;
- khó debug hơn;
- nhiều hyperparameter hơn.

---

## 6. Phân loại sách và chunking strategy theo từng loại

Hệ thống thư viện có nhiều loại sách, vì vậy không nên có duy nhất một chunking
strategy cho mọi PDF. Tuy nhiên cũng không nên làm tất cả ngay. Nên có taxonomy
đầy đủ, rồi implement từng nhóm.

### 6.1. Bảng phân loại tổng quan

| Book profile | Ví dụ | Tín hiệu nhận diện | Strategy đề xuất | Ưu tiên |
|---|---|---|---|---|
| `novel_narrative` | tiểu thuyết, truyện dài, văn học | paragraph nhiều, ít heading/table/code/math | `library_pdf_narrative_v1` | Làm đầu tiên |
| `short_story_collection` | tập truyện ngắn | nhiều heading tên truyện, đoạn văn tự sự | `library_pdf_narrative_v1`, sau đó chapter/story-aware | Sau tiểu thuyết |
| `chaptered_textbook` | giáo trình, sách nhập môn | nhiều chapter/section/heading | `library_pdf_markdown_heading_v1` | Sau narrative |
| `technical_book` | sách lập trình, hệ thống, API | heading, code block, command, JSON/XML | `library_pdf_technical_markdown_v1` | Sau heading strategy |
| `table_heavy_reference` | sách tra cứu, tài chính, thống kê | nhiều markdown table, row/column dày | `library_pdf_table_aware_v1` | Làm sau |
| `qa_exam_book` | đề thi, câu hỏi đáp, flashcard | nhiều `Câu hỏi`, `Đáp án`, `Question`, `Answer` | `library_pdf_qa_pair_v1` | Làm sau |
| `math_formula_book` | toán, vật lý, công thức | nhiều ký hiệu toán, equation, latex-like text | `library_pdf_formula_aware_v1` | Khó, để sau |
| `image_rich_book` | mỹ thuật, atlas, sách ảnh | text ít, image/caption nhiều | `library_pdf_caption_aware_v1` | Sau OCR/caption plan |
| `scanned_pdf` | scan ảnh, không có text layer | avg chars/page thấp | OCR pipeline, chưa chunk thường | Không làm ngay |
| `mixed_unknown` | tài liệu lẫn nhiều dạng | tín hiệu không rõ | `library_pdf_sentence_v1` fallback | Luôn cần |

### 6.2. Vì sao chọn tiểu thuyết trước?

Tiểu thuyết là nhóm tốt nhất để ổn định pipeline lõi:

```text
parse
  -> clean
  -> sentence-aware chunk
  -> persist chunks
  -> embed
  -> upsert Qdrant
  -> retrieve/citation
```

Vì nó tránh được nhiều bài toán khó:

- không cần OCR nếu PDF có text layer;
- không cần table-aware chunking;
- không cần code-aware chunking;
- không cần formula-aware chunking;
- không cần preserve row/column relation;
- không cần parent-child ngay từ đầu.

Đây là đường dễ kiểm soát nhất để đưa RAG chạy end-to-end trước.

### 6.3. Document profile cho tiểu thuyết

Profile đề xuất:

```json
{
  "document_profile": "novel_narrative",
  "document_profile_version": "v1",
  "document_profile_confidence": 0.75,
  "document_profile_signals": {
    "avg_text_chars_per_page": 1800,
    "heading_count": 24,
    "paragraph_count": 3200,
    "table_count": 0,
    "code_block_count": 0,
    "qa_pattern_count": 0,
    "math_symbol_ratio": 0.002
  }
}
```

Rule v1 có thể rất đơn giản:

```text
if avg_text_chars_per_page >= 300
and table_count is low
and code_block_count is low
and qa_pattern_count is low
and math_symbol_ratio is low
and paragraph_count is high:
    document_profile = novel_narrative
```

Nếu có heading kiểu:

```text
Chương 1
Chapter 1
Phần 1
```

thì vẫn có thể giữ profile là `novel_narrative`, đồng thời attach thêm:

```json
{
  "chapter_title": "Chương 1",
  "section_path": ["Chương 1"]
}
```

### 6.4. Chunking policy cho tiểu thuyết

Policy v1:

```text
strategy = library_pdf_narrative
version = v1
node_parser = LlamaIndex SentenceSplitter
chunk_size = 512
chunk_overlap = 64
page_boundary = preserved
```

Nên giữ trong chunk metadata:

```json
{
  "document_profile": "novel_narrative",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunker": "llamaindex_sentence_splitter",
  "pageStart": 12,
  "pageEnd": 12,
  "chapter_title": "Chương 3",
  "section_path": ["Chương 3"],
  "chunk_hash": "sha256",
  "embedding_status": "pending"
}
```

Với embedding text, tiểu thuyết không cần nhồi quá nhiều metadata. Chỉ nên thêm
context nhẹ:

```text
Book: <book title>
Chapter: <chapter title nếu có>
Page: <page number>

<chunk content>
```

Không nên đưa các ID kỹ thuật dài vào embedding text.

### 6.5. Roadmap mở rộng theo từng nhóm sách

Sau khi `library_pdf_narrative_v1` chạy ổn end-to-end, mở rộng theo thứ tự:

```text
1. short_story_collection
   -> vẫn dùng narrative strategy
   -> thêm story/chapter boundary nếu detect được tên truyện

2. chaptered_textbook
   -> MarkdownNodeParser
   -> section_path metadata

3. technical_book
   -> MarkdownNodeParser + code block preservation
   -> có thể thêm CodeSplitter sau

4. qa_exam_book
   -> Q/A pair chunking

5. table_heavy_reference
   -> table-aware chunking

6. math_formula_book
   -> formula/caption-aware strategy

7. image_rich_book và scanned_pdf
   -> cần OCR/caption pipeline riêng
```

Điểm quan trọng:

```text
Mỗi profile mới nên có strategy/version riêng.
Không sửa strategy cũ âm thầm.
Nếu đổi boundary chunk thì phải re-index tài liệu thuộc profile đó.
```

---

## 7. Embedding text khác raw chunk text

Một điểm nên bổ sung vào strategy: text dùng để lưu/citation và text dùng để
embed không nhất thiết phải giống nhau.

Ví dụ chunk content lưu DB:

```text
Full-time employees receive 14 days of annual leave.
```

Embedding text nên có context:

```text
Document: HR Policy 2026
Section: Annual Leave
Page: 5

Full-time employees receive 14 days of annual leave.
```

Với sách thư viện:

```text
Book: Database System Concepts
Chapter: Query Processing
Page: 213

...
```

Đề xuất metadata:

```json
{
  "embedding_text_policy": "title_section_page_prefix_v1"
}
```

Ở giai đoạn hiện tại, DB `document_chunks.content` có thể vẫn lưu chunk text
sạch. Khi implement embedding, service embedding nên build `embedding_text` từ:

```text
document title + section_path + page + chunk content
```

---

## 8. Chunk quality gate trước embedding

Sau khi tạo chunk, chưa nên embed ngay. Nên có bước validate:

```text
chunks
  -> chunk quality validation
  -> embedding
```

Các check tối thiểu:

- chunk text không empty;
- chunk không quá ngắn bất thường;
- chunk không quá dài so với embedding model;
- chunk có `document_id`;
- chunk có `pageStart/pageEnd`;
- chunk có `chunk_hash`;
- chunk có `chunking_strategy_version`;
- chunk có `token_count` và `token_counter`;
- duplicate chunk ratio không quá cao;
- chunk không chỉ toàn header/footer;
- chunk count không vượt `MAX_CHUNKS_PER_DOCUMENT`.

Quality report đề xuất:

```json
{
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunks_count": 84,
  "avg_chunk_chars": 1450,
  "min_chunk_chars": 120,
  "max_chunk_chars": 3100,
  "avg_chunk_tokens": 384,
  "min_chunk_tokens": 42,
  "max_chunk_tokens": 820,
  "duplicate_chunk_ratio": 0.02,
  "chunks_missing_page": 0,
  "chunks_missing_strategy_version": 0
}
```

Report này nên lưu vào:

```text
rag-artifacts/{documentId}/chunk_quality_report.json
```

---

## 9. Implementation plan từng bước

### Step C1 — Chuẩn hóa tên strategy/version cho tiểu thuyết

Không đổi thuật toán trước. Chỉ thêm metadata:

```text
document_profile = novel_narrative
chunking_strategy = library_pdf_narrative
chunking_strategy_version = v1
chunker = llamaindex_sentence_splitter
```

Lý do: giúp biết chunk đang được tạo bởi logic nào và thuộc loại sách nào.

### Step C2 — Tạo ChunkingStrategy adapter bằng LlamaIndex

Tách `node_adapter.py` thành cấu trúc rõ hơn:

```text
app/ingestion/chunking/
  __init__.py
  models.py
  strategy.py
  llama_sentence_strategy.py
  selector.py
  quality.py
```

Interface nội bộ:

```python
class ChunkingStrategy(Protocol):
    name: str
    version: str

    def build_nodes(self, documents: Sequence[Document]) -> list[BaseNode]:
        ...
```

Implementation đầu tiên vẫn dùng LlamaIndex `SentenceSplitter`.

### Step C3 — Thêm DocumentProfiler v1 tối giản

Thêm profiler rule-based rất nhẹ để nhận diện tiểu thuyết/text narrative:

```text
app/ingestion/profiling/
  __init__.py
  document_profiler.py
  models.py
```

Đầu ra:

```json
{
  "document_profile": "novel_narrative",
  "document_profile_version": "v1",
  "document_profile_signals": {}
}
```

Ban đầu chỉ cần phân biệt:

```text
novel_narrative
mixed_unknown
scanned_pdf_or_ocr_required
```

Các loại khác ghi trong taxonomy, nhưng chưa cần implement detector ngay.

### Step C4 — Thêm chunk quality validation

Thêm:

```text
app/ingestion/chunking/quality.py
```

Check empty/too short/too long/missing metadata/duplicate.

Pipeline chỉ đi tiếp embedding nếu quality pass.

### Step C5 — Thêm token count

Thêm token count vào metadata:

```json
{
  "token_count": 384,
  "token_counter": "approx_whitespace_char_v1"
}
```

C5 hiện đã implement bằng counter ước lượng:

```text
app/ingestion/chunking/token_counter.py
```

Quality report cũng có:

```json
{
  "min_chunk_tokens": 42,
  "max_chunk_tokens": 820,
  "avg_chunk_tokens": 384
}
```

Sau khi chọn embedding model chính thức thì token counter nên align với tokenizer
của model đó.

### Step C6 — Heading/chapter metadata cho tiểu thuyết

Trước khi làm textbook strategy, nên thêm detect chapter nhẹ cho tiểu thuyết:

```text
Chương 1
Chương I
Chapter 1
Chap. One
Part II
Book Three
Volume IV
Vol. IV
Act 5
Phần 1
Hồi 1
Prologue
Epilogue
Preface
Foreword
Afterword
Introduction
Conclusion
```

Không cần chunk theo chapter ngay. Chỉ cần attach:

```json
{
  "chapter_detection_version": "v1",
  "chapter_detected": true,
  "chapter_detected_on_page": true,
  "chapter_index": 1,
  "chapter_number": "1",
  "chapter_title": "Chương 1",
  "chapter_source_page": 12,
  "section_path": ["Chương 1"]
}
```

C6 hiện đã implement bằng:

```text
app/ingestion/chunking/chapter_detector.py
```

Scope hiện tại:

```text
Cleaned page-level Documents
  -> detect chapter heading ở vài dòng đầu page
  -> carry-forward chapter metadata sang page sau
  -> LlamaIndex SentenceSplitter
  -> chunks inherit chapter metadata
```

Chưa làm ở C6:

```text
chapter-wise reconstruction
chapter-level parent chunks
split theo chapter boundary
```

### Step C7 — Heading-aware Markdown strategy cho textbook

Thêm strategy:

```text
library_pdf_markdown_heading_v1
```

Dùng LlamaIndex:

```text
MarkdownNodeParser
```

Nếu một node heading quá dài, split tiếp bằng:

```text
SentenceSplitter
```

Đây là bước rất đáng làm cho ebook vì PyMuPDF4LLM thường trả Markdown.

### Step C8 — Strategy selector

Thêm selector:

```text
if document_profile == novel_narrative:
    use library_pdf_narrative_v1
elif parser output has reliable markdown headings:
    use library_pdf_markdown_heading_v1
else:
    use library_pdf_sentence_v1
```

Sau này có thể mở rộng:

```text
faq -> qa_pair
table-heavy -> table_aware
technical markdown -> markdown_heading
default pdf -> sentence
```

### Step C9 — Chunk artifacts

Upload:

```text
parsed_text.txt
cleaned_text.txt
parsed/parsed_pages.jsonl
cleaned/cleaned_pages.jsonl
processed_doc.md
chunks.jsonl
chunk_quality_report.json
manifest.json
```

vào:

```text
rag-artifacts
```

Mục tiêu: debug/reindex không cần xem trực tiếp DB.

A1 hiện đã implement trong code bằng:

```text
app/ingestion/artifacts/writer.py
```

Key structure hiện tại:

```text
rag-artifacts/
  documents/{documentId}/versions/v{sourceChecksumPrefix}/
    parsed_text.txt
    cleaned_text.txt
    processed_doc.md
    parsed/parsed_pages.jsonl
    cleaned/cleaned_pages.jsonl
    chunks/chunks.jsonl
    reports/chunk_quality_report.json
    manifest.json
```

### Step C10 — Parent-child / semantic sau embedding

Chỉ làm sau khi đã có:

```text
embedding provider
Qdrant upsert
retrieval API
evaluation set nhỏ
```

Vì parent-child và semantic chunking không chỉ ảnh hưởng ingestion, mà còn ảnh
hưởng retrieval/context expansion.

Danh sách chi tiết các việc cố ý để sau nằm ở:

```text
docs/chunking/deferred-implementation.md
```

---

## 10. Recommendation hiện tại

Không nên nhảy thẳng vào semantic chunking.

Lộ trình tốt nhất cho đồ án:

```text
Hiện tại:
  SentenceSplitter baseline đã chạy

Tiếp theo gần nhất:
  C1: thêm document_profile + chunking_strategy/version cho tiểu thuyết [done]
  C2: tách ChunkingStrategy adapter rõ ràng [done]
  C3: thêm DocumentProfiler v1 tối giản [done]
  C4: chunk quality validation [done]
  C5: token_count + token stats [done]
  C6: chapter metadata cho tiểu thuyết [done]
  A1: lưu artifacts vào rag-artifacts [done]

Sau đó:
  A2: embedding provider
  A3: Qdrant upsert

Sau khi có embedding/Qdrant/search:
  parent-child
  semantic chunking
  evaluation
```

Các bước parent-child/chapter-wise nâng cao và C7 MarkdownNodeParser cho
textbook/technical được ghi riêng trong:

```text
deferred-implementation.md
```

Nói ngắn:

```text
Project đã có chunking kỹ thuật.
Nhưng chưa có chunking strategy hoàn chỉnh.

Strategy đầu tiên nên là:
library_pdf_narrative_v1

Strategy nâng cấp gần nhất nên là:
library_pdf_markdown_heading_v1
```

---

## 11. Trạng thái so với `04-chunking-strategy.md`

| Ý trong tài liệu 04 | Project hiện tại | Đề xuất |
|---|---|---|
| Chunk size/overlap | Có `512/64` | Giữ baseline, thêm version |
| Sentence-based | Có qua LlamaIndex `SentenceSplitter` | Dùng cho `library_pdf_narrative_v1` |
| Recursive chunking | Có legacy LangChain | Không dùng main PDF path |
| Heading-aware | Chưa có | Dùng `MarkdownNodeParser` |
| Semantic chunking | Chưa có | Là phase sau embedding |
| Parent-child | Chưa có | Là phase sau retrieval |
| Metadata-aware | Có một phần | Thêm strategy/version/token_count/section_path |
| Chunk hash | Có `chunk_hash` | Giữ, dùng cho idempotency |
| Chunk quality | Có C4 v1 | Giữ quality gate trước embedding |
| Token count | Có C5 v1 bằng counter ước lượng | Align tokenizer sau khi chọn embedding model |
| Evaluation | Chưa có | Làm sau khi có retrieval API |
| Strategy theo loại sách | Chưa có | Thêm book profile taxonomy, bắt đầu với tiểu thuyết |
