# Novel Chunking Strategy

Tài liệu này mô tả strategy riêng cho **PDF tiểu thuyết / truyện dài / văn học
tự sự** trong hệ thống Library RAG.

Đây là nhóm sách được ưu tiên làm trước. Các nhóm khó hơn như bảng biểu, sách
kỹ thuật, sách công thức, scan/OCR sẽ làm sau khi pipeline tiểu thuyết chạy
end-to-end ổn định.

---

## 1. Kết luận nhanh

Với tiểu thuyết, không nên bắt đầu bằng fixed-length chunking hoặc semantic
chunking.

Strategy thực tế nhất:

```text
Recursive paragraph/chapter-aware chunking
  + Parent-child chunking
  + Chapter/page metadata
  + Neighbor expansion sau retrieval
```

Hiểu đơn giản:

```text
Book
  -> Chapter
    -> Parent chunk: một cảnh / cụm đoạn lớn
      -> Child chunk: đoạn nhỏ để embedding/search
```

Khi query:

```text
User hỏi
  -> search trên child chunks trong Qdrant
  -> lấy parent chunk hoặc neighbor chunks từ PostgreSQL
  -> đưa context giàu hơn vào LLM
```

---

## 2. Vì sao tiểu thuyết cần strategy riêng?

Tiểu thuyết khác tài liệu kỹ thuật hoặc policy ở chỗ:

- ý nghĩa thường nằm trong mạch truyện trước/sau;
- hội thoại dễ bị mất ngữ cảnh nếu cắt ngang;
- nhân vật, cảnh, cảm xúc cần vài đoạn liên tiếp để hiểu;
- citation nên trỏ được về chương/trang;
- câu hỏi có thể là cục bộ hoặc xuyên suốt tác phẩm.

Ví dụ nếu cắt fixed-length thô:

```text
... Cô im lặng rất lâu rồi nói:
[CHUNK BỊ CẮT]
"Em không chắc mình còn nhớ đường về nữa."
```

Chunk sau có câu thoại nhưng mất người nói, bối cảnh và cảm xúc trước đó.

Vì vậy boundary ưu tiên nên là:

```text
chapter boundary
  -> paragraph boundary
  -> sentence boundary
  -> token limit chỉ là fallback kỹ thuật
```

---

## 3. Strategy name/version

Strategy chính thức đầu tiên:

```text
library_pdf_narrative_v1
```

Profile:

```text
document_profile = novel_narrative
```

Mục tiêu của `v1`:

- xử lý tốt tiểu thuyết PDF có text layer;
- giữ mạch câu/đoạn tốt hơn fixed-length;
- có metadata đủ để debug/citation;
- chuẩn bị đường nâng cấp sang parent-child;
- chưa xử lý bảng biểu, công thức, OCR.

---

## 4. Full flow từ parser đến chunking

Một điểm rất dễ rối: **parser tách theo page** và **chunking theo chapter/paragraph**
không mâu thuẫn nhau.

Ta dùng page để giữ nguồn/citation, còn dùng chapter/paragraph để hiểu cấu trúc
tiểu thuyết.

```text
Page  = nguồn nằm ở đâu trong PDF
Chapter = cấu trúc truyện
Chunk = đơn vị search/LLM context
```

### 4.1. Sơ đồ tổng thể

```text
PDF trong library-private
        │
        ▼
┌────────────────────────────────────────────────────┐
│ 1. PDF Parser                                      │
│    PyMuPDF4LLM / parser adapter                    │
│    Output: ParsedDocument[] page-wise              │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 2. Page Cleaner                                    │
│    PdfCleaningTransformation / PdfCleaner          │
│    Output: CleanedPage[] page-wise                 │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 3. Narrative Book Reconstructor                    │
│    Ghép cleaned pages thành full text liên tục     │
│    nhưng giữ PageCharMapping                       │
│    Output: ReconstructedBookText                   │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 4. Chapter Detector                                │
│    Detect: Chương 1 / Chapter 1 / Phần 1 / Hồi 1   │
│    Output: ChapterSegment[]                        │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 5. LlamaIndex Document Adapter                     │
│    Mỗi chapter/parent candidate trở thành          │
│    LlamaIndex Document có metadata chapter/page    │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 6. LlamaIndex NodeParser / TextSplitter            │
│    MVP: SentenceSplitter                           │
│    Fallback: TokenTextSplitter nếu đoạn quá dài    │
│    Output: LlamaIndex Node[] child candidates      │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 7. Parent/Child Chunk Builder                      │
│    Parent chunk: cảnh/cụm paragraph lớn            │
│    Child chunk: 300-500 tokens để embed/search     │
│    Output: internal Chunk[]                        │
└───────────────────────┬────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────┐
│ 8. Persist                                         │
│    PostgreSQL: document_chunks parent + child      │
│    Qdrant sau này: child vectors                   │
│    rag-artifacts sau này: chunks.jsonl/report      │
└────────────────────────────────────────────────────┘
```

### 4.2. Dữ liệu ở từng tầng

#### Tầng 1 — ParsedDocument page-wise

Parser hiện tại có thể trả ra một item cho mỗi page:

```json
{
  "page_number": 2,
  "text": "Chương 1\nTôi đã ba mươi bảy tuổi khi...",
  "metadata": {
    "parser": "pymupdf4llm",
    "source_type": "pdf"
  }
}
```

Đây là đúng và vẫn cần thiết.

#### Tầng 2 — CleanedPage page-wise

Cleaner làm sạch từng page:

```json
{
  "page_number": 2,
  "text": "Chương 1\nTôi đã ba mươi bảy tuổi khi...",
  "metadata": {
    "cleaning_version": "pdf-clean-v1.0.0",
    "removed_page_number_count": 1
  }
}
```

#### Tầng 3 — ReconstructedBookText

Sau cleaning, novel strategy cần ghép pages thành text liền mạch:

```json
{
  "text": "RỪNG NA UY\n...\nChương 1\nTôi đã ba mươi bảy tuổi khi...",
  "page_char_mappings": [
    {
      "page_number": 1,
      "char_start": 0,
      "char_end": 850
    },
    {
      "page_number": 2,
      "char_start": 851,
      "char_end": 2400
    }
  ]
}
```

Điểm quan trọng:

```text
Ghép full text không có nghĩa là mất page.
Page mapping cho biết đoạn char nào thuộc trang nào.
```

Nhờ mapping này, nếu child chunk bắt đầu ở `char_start=1900` và kết thúc ở
`char_end=3100`, hệ thống suy ra:

```json
{
  "pageStart": 2,
  "pageEnd": 3
}
```

#### Tầng 4 — ChapterSegment

Chapter detector chia full text theo chương:

```json
{
  "chapter_index": 1,
  "chapter_title": "Chương 1",
  "char_start": 851,
  "char_end": 18000,
  "pageStart": 2,
  "pageEnd": 15,
  "text": "Tôi đã ba mươi bảy tuổi khi..."
}
```

Nếu chưa detect được chapter, fallback:

```text
chapter_index = null
chapter_title = null
strategy vẫn chạy theo page/paragraph groups
```

#### Tầng 5 — LlamaIndex Document

Sau khi có chapter/parent candidate, chuyển sang LlamaIndex `Document`.

Ví dụ:

```python
from llama_index.core import Document

doc = Document(
    text=chapter_or_parent_text,
    metadata={
        "document_profile": "novel_narrative",
        "chapter_index": 1,
        "chapter_title": "Chương 1",
        "pageStart": 2,
        "pageEnd": 15,
    },
)
```

LlamaIndex `Document` là container chuẩn để đưa vào `NodeParser`.

#### Tầng 6 — LlamaIndex NodeParser / TextSplitter

MVP dùng:

```python
from llama_index.core.node_parser import SentenceSplitter

splitter = SentenceSplitter(
    chunk_size=400,
    chunk_overlap=80,
    include_metadata=False,
)

nodes = splitter.get_nodes_from_documents([doc])
```

Ở đây `Node` chính là chunk ứng viên của LlamaIndex.

Vì cleaner/chapter metadata có thể dài, implementation hiện tại có thể split
theo text trước rồi copy metadata vào node sau. Đây là lựa chọn an toàn để
tránh metadata làm ảnh hưởng chunk size.

#### Tầng 7 — Internal Chunk

Sau khi có LlamaIndex Node, map về internal `Chunk` để lưu DB:

```json
{
  "text": "Tôi đã ba mươi bảy tuổi khi chiếc Boeing 747 hạ cánh...",
  "metadata": {
    "document_profile": "novel_narrative",
    "chunking_strategy": "library_pdf_narrative",
    "chunking_strategy_version": "v1",
    "chunk_level": "child",
    "chapter_index": 1,
    "chapter_title": "Chương 1",
    "chapter_source_page": 2,
    "chapter_detection_version": "v1",
    "chapter_detected_on_page": true,
    "pageStart": 2,
    "pageEnd": 3,
    "chunk_index": 0,
    "chunk_hash": "sha256",
    "token_count": 384,
    "token_counter": "approx_whitespace_char_v1",
    "embedding_status": "pending"
  }
}
```

### 4.3. Page parsing và chapter chunking phối hợp như thế nào?

Ví dụ parser trả ra:

```text
Page 1:
Chương 1
Tôi gặp cô ấy vào một buổi chiều mưa. Thành phố hôm đó rất lạnh.

Page 2:
Cô đứng trước hiệu sách, tay cầm một chiếc ô màu xanh.
Tôi không biết vì sao mình lại nhớ cảnh đó lâu đến vậy.

Page 3:
Chương 2
Nhiều năm sau, tôi trở lại con phố cũ.
```

Reconstructed full text:

```text
Chương 1
Tôi gặp cô ấy vào một buổi chiều mưa. Thành phố hôm đó rất lạnh.
Cô đứng trước hiệu sách, tay cầm một chiếc ô màu xanh.
Tôi không biết vì sao mình lại nhớ cảnh đó lâu đến vậy.

Chương 2
Nhiều năm sau, tôi trở lại con phố cũ.
```

Chapter detector:

```text
Chapter 1: page 1-2
Chapter 2: page 3
```

Child chunk có thể là:

```json
{
  "text": "Tôi gặp cô ấy vào một buổi chiều mưa. Thành phố hôm đó rất lạnh. Cô đứng trước hiệu sách, tay cầm một chiếc ô màu xanh.",
  "metadata": {
    "chapter_index": 1,
    "chapter_title": "Chương 1",
    "pageStart": 1,
    "pageEnd": 2
  }
}
```

Nó đi qua page 1 và page 2, nhưng vẫn biết citation page.

### 4.4. MVP và phase sau

MVP gần code hiện tại nhất:

```text
ParsedDocument page-wise
  -> CleanedPage page-wise
  -> LlamaIndex Document page-wise
  -> SentenceSplitter
  -> child chunks
```

Novel strategy đầy đủ hơn:

```text
ParsedDocument page-wise
  -> CleanedPage page-wise
  -> ReconstructedBookText + PageCharMapping
  -> ChapterSegment
  -> LlamaIndex Document chapter/parent-wise
  -> SentenceSplitter / TokenTextSplitter
  -> parent/child chunks
```

Vì vậy implementation nên đi theo thứ tự:

```text
1. Giữ path hiện tại để không phá pipeline.
2. Thêm metadata strategy/profile cho narrative chunks.
3. Thêm NovelBookReconstructor.
4. Thêm ChapterDetector.
5. Sau đó mới đổi input của LlamaIndex từ page-wise sang chapter/parent-wise.
6. Cuối cùng thêm parent-child retrieval.
```

---

## 5. Recursive chunking trong ngữ cảnh tiểu thuyết

Trong tài liệu gợi ý có nhắc tới **recursive chunking**. Với tiểu thuyết, nên
hiểu recursive chunking không phải là cắt bừa nhiều lần, mà là:

```text
Thử cắt ở boundary tự nhiên nhất trước.
Nếu vẫn quá dài, mới fallback xuống boundary nhỏ hơn.
```

Thứ tự fallback đề xuất:

```text
1. Chapter
2. Scene / block đoạn văn lớn nếu detect được
3. Paragraph
4. Sentence
5. Token splitter
```

Pseudo-flow:

```text
Nếu chapter đủ nhỏ:
    giữ chapter/section làm parent
Nếu chapter quá dài:
    gom nhiều paragraph thành parent chunks
Nếu paragraph quá dài:
    dùng sentence splitter
Nếu sentence quá dài:
    dùng token splitter
```

Trong implementation, không nên tự viết splitter PDF thô. Nên để parser/cleaner
đưa ra text sạch, rồi dùng LlamaIndex component ở tầng chunking:

```text
LlamaIndex Document
  -> SentenceSplitter / TokenTextSplitter
  -> LlamaIndex Node
```

Code nội bộ của project chỉ nên làm:

- detect chapter/paragraph boundary;
- chọn strategy;
- enrich metadata;
- map Node về internal Chunk;
- validate chunk quality.

---

## 6. Parent-child chunking cho tiểu thuyết

Parent-child là hướng rất hợp với tiểu thuyết.

### 6.1. Child chunk

Child chunk dùng cho:

```text
embedding
Qdrant search
retrieval precision
```

Cấu hình khởi đầu:

```text
child_chunk_size = 300-500 tokens
child_overlap = 50-100 tokens
default đề xuất = 400 / 80
```

Quy tắc:

- không vượt qua chapter boundary nếu có thể;
- không cắt giữa hội thoại nếu tránh được;
- giữ page/chapter metadata;
- child chunk được embed và upsert vào Qdrant.

### 6.2. Parent chunk

Parent chunk dùng cho:

```text
context expansion
LLM answer generation
giữ mạch cảnh/truyện
```

Cấu hình khởi đầu:

```text
parent_chunk_size = 1000-2000 tokens
default đề xuất = 1500 tokens
```

Parent chunk có thể là:

- một cảnh;
- một cụm đoạn văn liên tiếp;
- một phần của chương;
- hoặc một page group nếu chưa detect scene tốt.

Parent chunk thường **không cần embed** ở MVP. MVP có thể chỉ embed child, còn
parent lưu trong PostgreSQL để fetch khi cần.

### 6.3. Flow retrieval

```text
User query
  -> embed query
  -> search child chunks trong Qdrant
  -> top_k = 20
  -> rerank còn 5-8 chunk
  -> fetch parent_chunk_id tương ứng từ PostgreSQL
  -> lấy thêm neighbor child chunks nếu cần
  -> đưa context vào LLM
  -> trả lời kèm book/chapter/page citation
```

Đây còn gọi là:

```text
small-to-big retrieval
```

Search bằng đoạn nhỏ, trả lời bằng bối cảnh lớn hơn.

---

## 7. Lộ trình implementation theo phase

Không nên implement parent-child hoàn chỉnh ngay nếu embedding/Qdrant/retrieval
chưa xong. Nên chia ra.

### Phase N1 — Narrative child chunks trước

Mục tiêu:

```text
PDF tiểu thuyết
  -> parse
  -> clean
  -> detect profile novel_narrative
  -> create child chunks
  -> lưu PostgreSQL
```

Strategy:

```text
library_pdf_narrative_v1
```

LlamaIndex:

```text
SentenceSplitter
```

Metadata thêm:

```json
{
  "document_profile": "novel_narrative",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunk_level": "child",
  "chunker": "llamaindex_sentence_splitter"
}
```

Phase này chưa cần parent table/schema riêng. Quan trọng là có chunk child sạch,
có metadata tốt và có thể embed sau.

### Phase N2 — Chapter metadata

Mục tiêu:

```text
detect chương/phần/hồi
attach chapter metadata vào chunk
```

Pattern ban đầu:

```text
Chương 1
Chương I
CHƯƠNG 1
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

Metadata:

```json
{
  "chapter_detection_version": "v1",
  "chapter_detected": true,
  "chapter_detected_on_page": true,
  "chapter_index": 3,
  "chapter_title": "Chương 3",
  "chapter_source_page": 45,
  "section_path": ["Chương 3"]
}
```

Status hiện tại: C6 đã implement ở mức MVP bằng `ChapterDetector`.

```text
CleanedPage page-wise
  -> ChapterDetector detect heading ở vài dòng đầu page
  -> carry-forward chapter metadata sang page sau
  -> LlamaIndex SentenceSplitter tạo child chunks
```

Điều này giúp chunks có chapter metadata để citation/debug tốt hơn, nhưng chưa
đổi boundary chunking sang chapter-wise.

### Phase N3 — Parent chunk records

Mục tiêu:

```text
Tạo parent chunks lớn hơn và lưu PostgreSQL
Child chunks trỏ về parent_chunk_id
```

Metadata child:

```json
{
  "chunk_level": "child",
  "parent_chunk_id": "doc-7-parent-12"
}
```

Metadata parent:

```json
{
  "chunk_level": "parent",
  "child_chunk_ids": ["doc-7-child-31", "doc-7-child-32"]
}
```

Lưu ý: parent chunk chưa nhất thiết phải upsert Qdrant.

### Phase N4 — Embed child + upsert Qdrant

Mục tiêu:

```text
child chunks
  -> embedding
  -> Qdrant vector points
```

Qdrant payload tối thiểu:

```json
{
  "chunk_id": "doc-7-child-31",
  "document_id": 7,
  "book_id": 101,
  "ebook_id": 55,
  "chapter_index": 3,
  "pageStart": 45,
  "pageEnd": 46,
  "parent_chunk_id": "doc-7-parent-12",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1"
}
```

### Phase N5 — Retrieval expand parent/neighbor

Mục tiêu:

```text
Search child
  -> fetch parent
  -> optionally fetch neighbor child chunks
```

Config khởi đầu:

```text
retrieval_top_k = 20
rerank_top_k = 5 hoặc 8
neighbor_window = 1 chunk trước + 1 chunk sau
```

Chỉ expand trong cùng:

```text
document_id
chapter_index
permission scope
```

---

## 8. Metadata contract cho novel chunks

### 8.1. Common metadata

Mọi chunk nên có:

```json
{
  "book_id": 101,
  "bookId": 101,
  "ebook_id": 55,
  "ebookId": 55,
  "document_id": 7,
  "documentId": "doc_ebook_55",
  "sourceType": "LIBRARY_EBOOK",
  "document_profile": "novel_narrative",
  "document_profile_version": "v1",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunk_index": 31,
  "chunkIndex": 31,
  "chunk_hash": "sha256",
  "pageStart": 45,
  "pageEnd": 46,
  "token_count": 384,
  "token_counter": "approx_whitespace_char_v1",
  "embedding_status": "pending"
}
```

### 8.2. Novel-specific metadata

```json
{
  "chapter_index": 3,
  "chapter_title": "Chương 3",
  "chapter_source_page": 45,
  "chapter_detection_version": "v1",
  "chapter_detected_on_page": true,
  "section_path": ["Chương 3"],
  "paragraph_start": 120,
  "paragraph_end": 128,
  "char_start": 15200,
  "char_end": 18120,
  "chunk_level": "child",
  "parent_chunk_id": "doc-7-parent-12"
}
```

Không phải field nào cũng phải có ngay ở MVP. Thứ tự ưu tiên:

```text
Must have:
  document_profile
  chunking_strategy/version
  chunk_level
  pageStart/pageEnd
  chunk_hash
  token_count/token_counter

Should have:
  chapter_index/title
  parent_chunk_id

Nice to have:
  paragraph_start/end
  char_start/end
  scene_id
```

---

## 9. Embedding text cho tiểu thuyết

Text lưu DB và text đem đi embed có thể khác nhau.

`document_chunks.content` nên lưu content sạch:

```text
Minh bước vào căn phòng tối. Trên bàn là một phong thư cũ...
```

Embedding text nên thêm context nhẹ:

```text
Book: <book title>
Chapter: <chapter title nếu có>
Page: 45-46

Minh bước vào căn phòng tối. Trên bàn là một phong thư cũ...
```

Không nên đưa các field kỹ thuật vào embedding text:

```text
document_id
vector_id
chunk_hash
allowed_roles
timestamp
```

---

## 10. Những thứ không làm ở phase đầu

### Semantic chunking

Chưa nên làm ngay.

Lý do:

- cần embedding trong lúc chunking;
- khó debug;
- dễ bị ảnh hưởng nếu text extraction còn bẩn;
- nhiều hyperparameter;
- chưa cần cho MVP.

### Agentic chunking

Chưa cần.

Lý do:

- tốn chi phí;
- khó kiểm soát;
- khó giải thích trong đồ án;
- parent-child đã đủ tốt cho giai đoạn đầu.

### Table-aware / formula-aware / OCR

Không thuộc scope tiểu thuyết phase đầu.

Các loại này sẽ có strategy riêng sau:

```text
library_pdf_table_aware_v1
library_pdf_formula_aware_v1
library_pdf_ocr_v1
```

---

## 11. Quality checks riêng cho novel chunks

Sau khi chunk, kiểm tra:

- chunk không empty;
- chunk không quá ngắn;
- chunk không quá dài;
- chunk không toàn header/footer/page number;
- chunk không cắt ngang chapter boundary nếu đã detect được;
- duplicate chunk ratio không cao;
- `pageStart/pageEnd` tồn tại;
- `chunking_strategy_version` tồn tại;
- `chunk_level` tồn tại.
- `token_count/token_counter` tồn tại.

Quality report:

```json
{
  "document_profile": "novel_narrative",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunks_count": 320,
  "parent_chunks_count": 64,
  "child_chunks_count": 320,
  "avg_child_tokens": 410,
  "avg_parent_tokens": 1450,
  "min_chunk_tokens": 42,
  "max_chunk_tokens": 820,
  "avg_chunk_tokens": 384,
  "duplicate_chunk_ratio": 0.01,
  "chunks_missing_page": 0,
  "chunks_missing_chapter": 12
}
```

---

## 12. Recommended defaults

Cho MVP tiểu thuyết:

```text
document_profile = novel_narrative
chunking_strategy = library_pdf_narrative
chunking_strategy_version = v1

child_chunk_size = 400 tokens
child_overlap = 80 tokens
parent_chunk_size = 1500 tokens
retrieval_top_k = 20
rerank_top_k = 5 hoặc 8
neighbor_window = 1
```

Nếu chưa có parent-child retrieval:

```text
Vẫn dùng child chunks trước.
Lưu sẵn metadata để sau này thêm parent-child mà không phá toàn bộ pipeline.
```

---

## 13. Roadmap ngắn gọn

```text
N1. Narrative child chunk metadata
N2. Chapter detection metadata
N3. Parent chunk records
N4. Embed child chunks + upsert Qdrant
N5. Retrieval parent/neighbor expansion
N6. Evaluation bằng câu hỏi tiểu thuyết
```

Sau N1-N6 mới tính:

```text
semantic chunking
character memory index
chapter summary index
event timeline index
theme index
```

Các index nâng cao này hữu ích cho câu hỏi lớn kiểu:

```text
Nhân vật chính thay đổi tâm lý như thế nào xuyên suốt truyện?
Chủ đề cô đơn được thể hiện qua các chương ra sao?
Vì sao kết truyện có ý nghĩa?
```

Nhưng phase đầu chỉ cần làm tốt:

```text
clean text
  -> narrative chunks
  -> metadata
  -> embedding
  -> Qdrant
  -> retrieval citation
```

Current implementation status:

```text
Done:
  - document_profile metadata v1
  - library_pdf_narrative chunking_strategy/version v1
  - DocumentProfiler v1
  - LlamaIndex SentenceSplitter strategy adapter
  - ChunkQualityValidator v1
  - token_count/token_counter metadata C5
  - token stats trong chunk_quality_report
  - ChapterDetector v1
  - chapter metadata carry-forward vào chunks

Not done yet:
  - NovelBookReconstructor
  - parent chunk records
  - embedding/Qdrant upsert
  - parent/neighbor retrieval expansion
```

Các việc này được ghi riêng thành backlog cố ý để sau tại:

```text
docs/chunking/deferred-implementation.md
```

Khi đã có embedding + Qdrant + retrieval/search API cơ bản, quay lại backlog này
và bắt đầu từ `D4 NovelBookReconstructor`.
