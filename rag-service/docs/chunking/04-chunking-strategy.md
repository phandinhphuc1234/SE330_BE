# 04. Chunking Strategy

Chunking là một trong những phần quan trọng nhất của RAG. Nhiều hệ thống RAG demo trả lời được vài câu đơn giản nhưng fail trong production không phải vì LLM yếu, mà vì chunking sai.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/ingestion/chunkers/base.py`
- `app/ingestion/chunkers/recursive_chunker.py`
- `app/ingestion/chunkers/semantic_chunker.py`
- `app/ingestion/chunkers/section_chunker.py`
- `app/core/config.py` với `CHUNK_SIZE`, `CHUNK_OVERLAP`, `MAX_CHUNKS_PER_DOCUMENT`
- `app/documents/models.py` với `DocumentChunk`

## 1. Chunk Là Gì?

Chunk là một đoạn nhỏ được cắt ra từ tài liệu lớn để:

- embed thành vector
- lưu vào vector database
- retrieve khi user hỏi
- đưa vào prompt làm context
- cite làm nguồn

Ví dụ tài liệu:

```text
HR Policy 2026

1. Annual Leave
Full-time employees receive 14 days of annual leave...

2. Sick Leave
Employees receive 30 days of sick leave...
```

Có thể cắt thành chunks:

```text
Chunk 1:
Title: HR Policy 2026
Section: Annual Leave
Content: Full-time employees receive 14 days...

Chunk 2:
Title: HR Policy 2026
Section: Sick Leave
Content: Employees receive 30 days...
```

Mỗi chunk nên có metadata:

```json
{
  "chunk_id": "hr-policy-2026-v3-c001",
  "document_id": "hr-policy-2026",
  "document_version": "v3",
  "chunk_index": 1,
  "section": "Annual Leave",
  "page_number": 5,
  "token_count": 180,
  "tenant_id": "company-alpha",
  "workspace_id": "hr",
  "allowed_roles": ["employee", "hr"]
}
```

## 2. Vì Sao Chunking Ảnh Hưởng Trực Tiếp Retrieval Quality?

Vector search không search trên tài liệu gốc. Nó search trên chunks đã embed.

Nếu chunk không chứa đủ context, embedding không đại diện đúng nghĩa.

Nếu chunk chứa quá nhiều nội dung, embedding bị nhiễu.

Ví dụ chunk quá nhỏ:

```text
14 ngày.
```

Query:

```text
Chính sách nghỉ phép năm nay là gì?
```

Chunk "14 ngày" đúng nhưng thiếu context. LLM không biết 14 ngày là nghỉ phép, áp dụng cho ai, năm nào.

Ví dụ chunk quá lớn:

```text
Annual leave policy...
Sick leave policy...
Travel reimbursement...
Laptop request...
Office parking...
```

Embedding của chunk này bị trộn nhiều ý. Query về nghỉ phép có thể retrieve được, nhưng context đưa vào LLM chứa quá nhiều noise.

Chunking tốt giúp:

- tăng recall
- tăng precision
- giảm token cost
- citation chính xác hơn
- reranking hiệu quả hơn
- giảm hallucination

## 3. Chunking Nằm Ở Đâu Trong Pipeline?

```text
Raw file
  ↓
Parser
  ↓
Cleaner
  ↓
Chunker
  ↓
Metadata extractor
  ↓
Embedding
  ↓
Vector DB
```

Chunking nằm sau cleaning và trước embedding.

Vì chunking quyết định unit được embed, đổi chunking strategy thường phải re-index.

## 4. Triển Khai Thực Tế Trong Project Hiện Tại

Interface hiện tại:

```python
@dataclass
class Chunk:
    text: str
    metadata: dict = field(default_factory=dict)


class BaseChunker(ABC):
    @abstractmethod
    def chunk(self, text: str, metadata: dict | None = None) -> list[Chunk]:
        raise NotImplementedError
```

Hiện có:

- `RecursiveChunker`
- `SemanticChunker` placeholder
- `SectionChunker`

Về production, `Chunk` nên có thêm:

```python
@dataclass
class Chunk:
    id: str
    text: str
    metadata: dict
    chunk_index: int
    token_count: int
    hash: str
    parent_id: str | None = None
```

## 5. Chunk Size

Chunk size là độ dài mỗi chunk.

Có thể đo bằng:

- characters
- words
- tokens
- sentences
- paragraphs
- semantic boundaries

### 5.1. Character-based size

Ví dụ:

```text
chunk_size = 1000 characters
chunk_overlap = 100 characters
```

Ưu điểm:

- dễ làm
- nhanh
- không cần tokenizer

Nhược điểm:

- token count không chính xác
- tiếng Việt/Anh khác nhau
- có thể cắt giữa câu

### 5.2. Token-based size

Token-based chunking cắt theo token model dùng cho embedding/LLM.

Ưu điểm:

- kiểm soát token budget tốt hơn
- phù hợp với embedding/LLM limits

Nhược điểm:

- cần tokenizer
- phụ thuộc model
- có thể phức tạp hơn

### 5.3. Size bao nhiêu là hợp lý?

Không có con số đúng cho mọi corpus. Với Internal KB, điểm bắt đầu có thể là:

| Loại tài liệu | Chunk size gợi ý | Overlap gợi ý |
|---|---:|---:|
| FAQ | 100-300 tokens | 0-30 |
| HR policy | 300-700 tokens | 50-100 |
| Technical docs | 400-900 tokens | 80-150 |
| Meeting notes | 300-700 tokens | 50-100 |
| Research paper | 700-1200 tokens | 100-200 |
| Source code | theo function/class | ít hoặc không overlap |
| Table-heavy docs | theo table/row group | tùy cấu trúc |

Trong `.env` project hiện tại:

```text
CHUNK_SIZE=512
CHUNK_OVERLAP=64
MAX_CHUNKS_PER_DOCUMENT=1000
```

Đây là điểm bắt đầu ổn cho học và prototype.

## 6. Chunk Overlap

Overlap là phần nội dung lặp giữa hai chunk liên tiếp.

Ví dụ:

```text
Chunk 1: A B C D E
Chunk 2: D E F G H
```

Overlap giúp không mất ý khi câu/đoạn bị cắt ở boundary.

### Trade-off

| Overlap | Ưu điểm | Nhược điểm |
|---|---|---|
| Quá thấp | Ít duplicate, rẻ hơn | Dễ mất context ở biên |
| Vừa đủ | Giữ continuity | Tăng ít cost |
| Quá cao | Ít mất ý | Duplicate nhiều, tốn embedding, retrieval nhiễu |

### Khi nào cần overlap cao hơn?

- Technical docs có nhiều câu phụ thuộc đoạn trước.
- Policy/legal document có điều kiện kéo dài.
- Research paper có luận điểm nhiều câu.

### Khi nào giảm overlap?

- FAQ độc lập từng câu hỏi.
- Source code theo function.
- Table row-level chunks.
- Support tickets ngắn.

## 7. Token-Based Chunking

### Nó là gì?

Token-based chunking cắt text theo số token thay vì số ký tự.

### Vì sao cần?

Embedding model và LLM đều giới hạn token. Nếu chunk vượt limit, embedding call fail hoặc bị truncate.

### Pseudo-code

```python
def token_chunk(text: str, tokenizer, max_tokens: int, overlap: int) -> list[str]:
    tokens = tokenizer.encode(text)
    chunks = []
    start = 0

    while start < len(tokens):
        end = min(start + max_tokens, len(tokens))
        chunk_tokens = tokens[start:end]
        chunks.append(tokenizer.decode(chunk_tokens))

        if end == len(tokens):
            break

        start = end - overlap

    return chunks
```

### Lỗi thường gặp

- Cắt giữa câu.
- Cắt hỏng markdown table.
- Cắt hỏng code block.
- Tokenizer của embedding khác tokenizer của LLM.

## 8. Sentence-Based Chunking

### Nó là gì?

Cắt text theo câu, gom nhiều câu đến khi đạt size target.

### Vì sao cần?

Giữ câu nguyên vẹn giúp embedding tốt hơn và LLM đọc dễ hơn.

### Pseudo-code

```python
def sentence_chunk(sentences: list[str], max_tokens: int) -> list[str]:
    chunks = []
    current = []
    current_tokens = 0

    for sentence in sentences:
        sentence_tokens = count_tokens(sentence)

        if current and current_tokens + sentence_tokens > max_tokens:
            chunks.append(" ".join(current))
            current = []
            current_tokens = 0

        current.append(sentence)
        current_tokens += sentence_tokens

    if current:
        chunks.append(" ".join(current))

    return chunks
```

### Trade-off

- Tốt hơn character split.
- Nhưng sentence detection tiếng Việt/technical docs có thể sai.
- Không hiểu heading/table nếu chỉ dựa vào câu.

## 9. Paragraph-Based Chunking

### Nó là gì?

Gom paragraph thành chunk.

### Phù hợp với

- HR policies
- internal wiki
- product docs
- meeting notes

### Ưu điểm

- Giữ ý tự nhiên.
- Dễ preserve formatting.
- Ít cắt giữa câu.

### Nhược điểm

- Paragraph có thể quá dài.
- Có thể thiếu heading nếu không attach section path.

### Khuyến nghị

Khi chunk paragraph, luôn attach heading gần nhất:

```text
Section: Annual Leave
Content: Full-time employees receive 14 days...
```

Không nên chỉ embed paragraph body mà bỏ heading.

## 10. Heading-Aware Chunking

### Nó là gì?

Chunking dựa trên cấu trúc heading/section của tài liệu.

Ví dụ Markdown:

```markdown
# Order Service
## Payment Flow
### Retry Policy
```

Chunk metadata:

```json
{
  "section_path": ["Order Service", "Payment Flow", "Retry Policy"]
}
```

### Vì sao cần?

Heading là context cực mạnh. Nhiều đoạn body chỉ có:

```text
Retry after 30 seconds.
```

Nếu không có heading, không biết retry của cái gì.

Với heading:

```text
Order Service > Payment Flow > Retry Policy
Retry after 30 seconds.
```

Retrieval tốt hơn nhiều.

### Nằm ở đâu?

Trong chunker, sau parser đã preserve headings.

### Triển khai thực tế

```python
def heading_aware_chunk(blocks):
    section_stack = []
    chunks = []
    current_text = []

    for block in blocks:
        if block.type == "heading":
            flush_current_chunk(chunks, current_text, section_stack)
            section_stack = update_section_stack(section_stack, block)
        else:
            current_text.append(block.text)

    flush_current_chunk(chunks, current_text, section_stack)
    return chunks
```

### Lỗi thường gặp

- Parser không giữ heading level.
- Heading bị tách khỏi body.
- Section quá dài nhưng không split tiếp.
- Heading style trong DOCX không chuẩn.

## 11. Semantic Chunking

### Nó là gì?

Semantic chunking cắt text theo sự thay đổi ý nghĩa, không chỉ độ dài.

Ý tưởng:

```text
Split text into sentences/paragraphs
  ↓
Embed each unit
  ↓
Detect semantic distance between adjacent units
  ↓
Split where topic changes
```

### Vì sao cần?

Fixed-size chunk có thể cắt giữa hai ý khác nhau hoặc trộn nhiều topic.

Semantic chunking giúp:

- giữ topic coherent
- giảm noise
- tăng retrieval precision

### Dùng khi nào?

- Meeting notes dài, topic thay đổi liên tục.
- Internal wiki không có heading tốt.
- Research papers.
- Product docs dạng narrative.

### Không nên dùng khi nào?

- FAQ đã có Q/A rõ.
- Source code nên chunk theo function/class.
- Table-heavy docs.
- Corpus nhỏ, cần đơn giản.

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Topic coherent hơn | Tốn embedding/compute ở ingestion |
| Retrieval precision tốt hơn | Khó debug hơn fixed chunk |
| Ít cắt sai giữa ý | Có nhiều hyperparameters |

### Pseudo-code

```python
def semantic_chunk(paragraphs, embedding_model, threshold: float):
    paragraph_vectors = embedding_model.embed(paragraphs)
    chunks = []
    current = [paragraphs[0]]

    for i in range(1, len(paragraphs)):
        distance = cosine_distance(paragraph_vectors[i - 1], paragraph_vectors[i])

        if distance > threshold:
            chunks.append("\n\n".join(current))
            current = [paragraphs[i]]
        else:
            current.append(paragraphs[i])

    if current:
        chunks.append("\n\n".join(current))

    return chunks
```

## 12. Sliding Window Chunking

### Nó là gì?

Sliding window tạo chunks bằng cửa sổ trượt với overlap cố định.

Ví dụ:

```text
Window size = 512 tokens
Step = 448 tokens
Overlap = 64 tokens
```

### Dùng khi nào?

- Document không có cấu trúc tốt.
- Cần baseline nhanh.
- Plain text.

### Ưu điểm

- Dễ implement.
- Không cần parser phức tạp.
- Có overlap tránh mất context.

### Nhược điểm

- Có thể cắt hỏng section/table/code.
- Duplicate nhiều.
- Citation kém tự nhiên.

Trong project hiện tại, `RecursiveChunker` gần với hướng này.

## 13. Recursive Chunking

Recursive chunking thử split theo các separator ưu tiên:

```text
\n\n
\n
.
space
character
```

Ý tưởng: cố gắng cắt ở boundary tự nhiên trước, nếu chunk vẫn quá dài thì cắt nhỏ tiếp.

### Ưu điểm

- Dễ dùng.
- Tốt hơn fixed character split.
- Phù hợp baseline.

### Nhược điểm

- Không thật sự hiểu semantic.
- Không đủ tốt cho table/code/legal section nếu không customize.

Project hiện tại:

```python
RecursiveCharacterTextSplitter(
    chunk_size=settings.chunk_size,
    chunk_overlap=settings.chunk_overlap,
)
```

Đây là điểm bắt đầu tốt, nhưng production nên bổ sung metadata-aware/heading-aware logic.

## 14. Parent-Child Chunking

### Nó là gì?

Tạo hai cấp chunk:

- child chunk nhỏ để retrieval chính xác
- parent chunk lớn hơn để cung cấp context cho LLM

Flow:

```text
Document section
  ↓
Parent chunk: full section
  ↓
Child chunks: smaller pieces
  ↓
Embed child chunks
  ↓
Retrieve child
  ↓
Return parent context
```

### Vì sao cần?

Child chunk nhỏ giúp match query tốt. Parent chunk lớn giúp LLM có đủ context.

Ví dụ:

Query:

```text
Nhân viên thử việc có được nghỉ phép không?
```

Child chunk match:

```text
Probation employees cannot use annual leave in advance.
```

Parent chunk đưa vào prompt:

```text
Section: Annual Leave
Full-time employees receive 14 days...
Probation employees cannot use annual leave in advance...
Unused leave policy...
```

### Trade-off

- Retrieval tốt hơn.
- Context đầy đủ hơn.
- Nhưng schema phức tạp hơn.
- Cần mapping child → parent.

### Schema metadata

```json
{
  "chunk_id": "child_123",
  "parent_chunk_id": "parent_45",
  "chunk_level": "child",
  "section": "Annual Leave"
}
```

## 15. Small-To-Big Retrieval

Small-to-big retrieval gần với parent-child nhưng linh hoạt hơn.

Flow:

```text
Retrieve small chunks
  ↓
Find neighboring chunks / parent section
  ↓
Expand context
  ↓
Send expanded context to LLM
```

Trong project hiện tại, component này map vào:

- `app/retrieval/context_expander.py`

### Dùng khi nào?

- Technical docs nhiều đoạn phụ thuộc nhau.
- Legal/HR policy cần điều kiện trước/sau.
- Meeting notes có context gần đó.

### Lỗi thường gặp

- Expand quá nhiều làm prompt nhiễu.
- Expand qua section khác.
- Không kiểm tra permission của neighboring chunks.

Permission quan trọng: khi expand context, chunk lân cận cũng phải qua permission filter.

## 16. Late Chunking

### Nó là gì?

Late chunking là kỹ thuật embed tài liệu dài hoặc section lớn trước, sau đó tạo chunk representation dựa trên contextual embeddings.

Ý tưởng đơn giản:

- Model nhìn context lớn hơn trước.
- Chunk vector được tạo với hiểu biết về toàn section/document.

### Dùng khi nào?

- Cần chất lượng cao.
- Có model hỗ trợ long-context embeddings.
- Tài liệu có dependency mạnh giữa các đoạn.

### Trade-off

- Phức tạp hơn nhiều.
- Không phải stack nào cũng hỗ trợ tốt.
- Khó debug hơn.
- Chưa cần ở giai đoạn đầu project hiện tại.

Khuyến nghị: hiểu concept, nhưng bắt đầu với heading-aware + parent-child + hybrid retrieval trước.

## 17. Chunking Theo Loại Tài Liệu

### 17.1. Legal / Policy document

Ví dụ:

- HR policies
- compliance docs
- legal terms

Đặc điểm:

- section/điều/khoản quan trọng
- câu dài
- điều kiện nhiều
- citation cần chính xác

Strategy:

- heading-aware
- section-aware
- không cắt giữa điều/khoản
- chunk theo section nhỏ
- overlap vừa phải
- metadata page/section/clause

Ví dụ chunk:

```json
{
  "section": "Annual Leave",
  "clause": "2.1",
  "page_number": 5,
  "text": "Full-time employees receive 14 days of annual leave..."
}
```

Checklist:

- Không split giữa một điều khoản quan trọng.
- Có section/clause metadata.
- Có page number.
- Có preserve table policy.
- Có version/year.

### 17.2. Technical documentation

Ví dụ:

- service architecture
- API docs
- runbooks
- deployment docs

Đặc điểm:

- heading/code block/table
- keyword chính xác quan trọng
- tên service/error code phải giữ

Strategy:

- heading-aware
- preserve code block
- chunk theo section/subsection
- code chunk theo function/class nếu là source code
- hybrid retrieval rất quan trọng

Metadata:

```json
{
  "service": "order-service",
  "component": "payment-worker",
  "doc_type": "runbook",
  "section_path": ["Order Service", "Payment Flow", "Retry Policy"]
}
```

Checklist:

- Code block không bị cắt hỏng.
- Error code được giữ.
- Section path được attach.
- API endpoint path được giữ.
- Metadata service/component được extract.

### 17.3. FAQ

Đặc điểm:

- câu hỏi/đáp độc lập
- thường ngắn
- retrieval nên match question và answer

Strategy:

- mỗi Q/A là một chunk
- overlap gần như không cần
- embed cả question + answer

Ví dụ:

```text
Question: Nhân viên thử việc có được nghỉ phép không?
Answer: Nhân viên thử việc chưa được ứng trước ngày phép...
```

Checklist:

- Q và A nằm cùng chunk.
- Không gộp quá nhiều FAQ.
- Có category/product metadata.

### 17.4. Research paper

Đặc điểm:

- section dài
- nhiều reference
- concept phụ thuộc nhau

Strategy:

- section-aware
- paragraph-based trong section
- larger chunk size
- overlap cao hơn
- preserve figure/table captions

Checklist:

- Abstract riêng.
- Section metadata.
- Table/figure captions giữ lại.
- References có thể index riêng hoặc bỏ tùy use case.

### 17.5. Source code

Đặc điểm:

- syntax quan trọng
- function/class boundary quan trọng
- comments/docstrings quan trọng

Strategy:

- chunk theo file/module/function/class
- không dùng arbitrary character split nếu có thể
- metadata repo/path/language/function/class

Ví dụ metadata:

```json
{
  "repo": "backend-services",
  "file_path": "services/order/payment.py",
  "language": "python",
  "symbol": "PaymentProcessor.handle_callback"
}
```

Checklist:

- Không cắt giữa function nếu tránh được.
- Có file path.
- Có language.
- Có symbol name.
- Có import context nếu cần.

### 17.6. Table-heavy document

Đặc điểm:

- meaning nằm trong row/column relation
- flatten text dễ sai

Strategy:

- preserve markdown table nếu table nhỏ
- row-level chunks nếu table lớn
- include table title/caption
- include column names trong từng row chunk

Ví dụ row chunk:

```text
Table: Annual Leave Entitlement
Employee Type: Full-time
Annual Leave: 14 days
Carry-over: Maximum 5 days
```

Checklist:

- Column names không mất.
- Row identity rõ.
- Table caption/title attach.
- Không split table giữa chừng nếu table nhỏ.

## 18. Metadata-Aware Chunking

Metadata-aware chunking nghĩa là chunk không chỉ có text, mà có context metadata đầy đủ.

Chunk text nên có thể include context header:

```text
Document: HR Policy 2026
Section: Annual Leave
Page: 5

Full-time employees receive 14 days of annual leave...
```

Metadata riêng:

```json
{
  "document_title": "HR Policy 2026",
  "section": "Annual Leave",
  "page_number": 5
}
```

### Có nên đưa metadata vào text embed không?

Tùy metadata.

Nên đưa vào text:

- title
- heading/section
- product/service name
- FAQ question
- table caption

Không nên đưa vào text:

- internal IDs dài
- permission arrays
- timestamps không liên quan
- hash values

Metadata có thể vừa:

- dùng trong vector payload để filter
- dùng trong text để embedding hiểu context

## 19. Chunk ID Và Hash

Production chunk cần stable identity.

### Chunk ID gợi ý

```text
{tenant_id}:{document_id}:{version_id}:chunk:{chunk_index}
```

Nếu muốn idempotency mạnh hơn:

```text
{tenant_id}:{document_id}:{version_id}:{chunk_index}:{chunk_hash}
```

### Chunk hash

```python
def chunk_hash(text: str, metadata: dict) -> str:
    normalized = normalize_for_hash(text)
    key_metadata = {
        "section": metadata.get("section"),
        "page_number": metadata.get("page_number"),
    }
    payload = json.dumps(
        {"text": normalized, "metadata": key_metadata},
        sort_keys=True,
        ensure_ascii=False,
    )
    return sha256(payload.encode("utf-8")).hexdigest()
```

### Vì sao cần hash?

- Detect chunk unchanged.
- Avoid re-embedding unchanged chunks.
- Debug version differences.
- Idempotent upsert.

## 20. Chunking Versioning

Chunking strategy phải có version.

Ví dụ:

```json
{
  "chunking_strategy": "heading_aware_recursive",
  "chunking_strategy_version": "v2",
  "chunk_size": 512,
  "chunk_overlap": 64
}
```

### Vì sao cần?

Nếu bạn đổi từ recursive chunking sang section-aware chunking, retrieval score thay đổi. Không có version, bạn không biết corpus được index bằng strategy nào.

### Khi đổi chunking strategy có cần re-index không?

Thường là có.

Vì:

- chunk boundaries đổi
- chunk IDs đổi
- embeddings đổi
- citation mapping đổi

Có thể incremental re-index theo document nếu chỉ đổi strategy cho một loại document.

## 21. Pseudo-code Chunking Pipeline

```python
async def chunk_document(cleaned_parts, document, version, strategy):
    all_chunks = []
    chunk_index = 0

    for part in cleaned_parts:
        section_context = extract_section_context(part.metadata)

        raw_chunks = strategy.chunk(
            text=part.text,
            metadata={
                "tenant_id": document.tenant_id,
                "workspace_id": document.workspace_id,
                "document_id": document.id,
                "document_version_id": version.id,
                "source_type": document.source_type,
                "page_number": part.metadata.get("page_number"),
                "section_path": section_context,
                "allowed_roles": document.allowed_roles,
            },
        )

        for raw_chunk in raw_chunks:
            enriched_text = build_embedding_text(
                title=document.title,
                section_path=section_context,
                content=raw_chunk.text,
            )

            chunk = ChunkRecord(
                id=build_chunk_id(document, version, chunk_index),
                chunk_index=chunk_index,
                content=raw_chunk.text,
                embedding_text=enriched_text,
                metadata=raw_chunk.metadata,
                token_count=count_tokens(enriched_text),
                chunk_hash=hash_text(enriched_text),
                chunking_strategy=strategy.name,
                chunking_strategy_version=strategy.version,
            )

            validate_chunk(chunk)
            all_chunks.append(chunk)
            chunk_index += 1

    if len(all_chunks) > settings.max_chunks_per_document:
        raise TooManyChunksError(document.id, len(all_chunks))

    return all_chunks
```

## 22. Validate Chunk Quality

Sau khi chunk, không nên embed ngay. Nên validate.

### Checks

- Chunk text có empty không?
- Token count có vượt limit không?
- Chunk count có bất thường không?
- Chunk có metadata tenant/workspace/document không?
- Permission metadata có không?
- Section/page metadata có không?
- Có quá nhiều duplicate chunks không?
- Có chunk toàn header/footer không?

### Quality metrics

```json
{
  "document_id": "hr-policy-2026",
  "chunks_count": 84,
  "avg_chunk_tokens": 421,
  "min_chunk_tokens": 48,
  "max_chunk_tokens": 812,
  "duplicate_chunk_ratio": 0.03,
  "chunks_missing_section": 4,
  "chunks_missing_permission": 0
}
```

Nếu `chunks_missing_permission > 0`, nên fail ingestion để tránh data leakage.

## 23. Common Chunking Mistakes

### 23.1. Chunk quá nhỏ

Triệu chứng:

- Retrieval match đúng keyword nhưng answer thiếu context.
- LLM hỏi lại hoặc trả lời mơ hồ.
- Citation trỏ vào chunk không đủ thông tin.

Ví dụ xấu:

```text
14 days.
```

### 23.2. Chunk quá lớn

Triệu chứng:

- Top-k có vẻ đúng nhưng answer lẫn ý.
- Token cost cao.
- Reranker chậm.
- LLM bị lost in the middle.

### 23.3. Overlap quá cao

Triệu chứng:

- Retrieved chunks gần như giống nhau.
- Context bị duplicate.
- Embedding cost tăng.
- LLM lặp câu trả lời.

### 23.4. Bỏ heading khỏi chunk

Triệu chứng:

- Chunk body đúng nhưng mất topic.
- Query theo title/section không match tốt.

### 23.5. Không có metadata permission

Đây là lỗi nghiêm trọng. Retrieval có thể lấy private chunk cho user không có quyền.

### 23.6. Split table sai

Triệu chứng:

- LLM nhầm giá trị row/column.
- Citation đúng table nhưng answer sai.

### 23.7. Không version chunking

Triệu chứng:

- Sau khi update strategy, quality thay đổi nhưng không biết vì sao.
- Evaluation không reproducible.

## 24. Debug Chunking

Khi retrieval sai, hãy xem chunk trước.

### Debug questions

- Chunk có chứa answer không?
- Chunk có đủ context không?
- Chunk có bị trộn nhiều topic không?
- Heading có đi kèm không?
- Metadata filter có dùng được không?
- Chunk có duplicate không?
- Chunk có token count phù hợp không?
- Chunk có bị split giữa table/code/list không?

### Debug view nên có

```text
Document: HR Policy 2026
Version: v3
Strategy: heading_aware_recursive_v2

Chunk 12
Token count: 384
Page: 5
Section: Annual Leave
Hash: abc123
Allowed roles: employee, hr

Text:
Document: HR Policy 2026
Section: Annual Leave

Full-time employees receive 14 days...
```

## 25. Evaluation Cho Chunking

Chunking không chỉ đánh giá bằng cảm giác. Cần test retrieval.

### Dataset nhỏ

```json
[
  {
    "question": "Nhân viên chính thức được nghỉ phép bao nhiêu ngày?",
    "expected_source_ids": ["hr-policy-2026-v3-c012"]
  },
  {
    "question": "Payment timeout của order service retry thế nào?",
    "expected_source_ids": ["order-runbook-v2-c007"]
  }
]
```

### Metrics

- Recall@k
- Precision@k
- MRR
- nDCG
- answer faithfulness
- citation accuracy

Nếu đổi chunking strategy, chạy lại evaluation:

```text
Old strategy recall@5 = 0.72
New strategy recall@5 = 0.84
Latency +10%
Embedding storage +15%
```

Đây mới là cách quyết định strategy tốt hơn, không phải nhìn vài câu demo.

## 26. Chunking Strategy Selector

Production có thể chọn strategy theo `document_type`.

```python
def select_chunker(document_type: str):
    if document_type in {"hr_policy", "legal"}:
        return SectionAwareChunker()

    if document_type in {"technical_doc", "runbook"}:
        return HeadingAwareRecursiveChunker()

    if document_type == "faq":
        return QAChunker()

    if document_type == "source_code":
        return CodeChunker()

    if document_type in {"csv", "xlsx"}:
        return TableAwareChunker()

    return RecursiveChunker()
```

### Config example

```yaml
chunking:
  default:
    strategy: recursive
    chunk_size: 512
    chunk_overlap: 64
  hr_policy:
    strategy: section_aware
    max_section_tokens: 700
    overlap: 80
  technical_doc:
    strategy: heading_aware_recursive
    chunk_size: 700
    overlap: 100
  faq:
    strategy: qa_pair
    overlap: 0
```

## 27. Chunking Và Citation

Citation càng tốt khi chunk càng map rõ với nguồn.

Chunk metadata nên có:

- document title
- document version
- page number
- section path
- source URI
- chunk index

Answer citation:

```text
Nhân viên chính thức có 14 ngày nghỉ phép mỗi năm. [HR Policy 2026, page 5, Annual Leave]
```

Nếu chunk quá lớn, citation ít chính xác. Nếu chunk quá nhỏ, citation có thể không đủ context.

## 28. Chunking Và Permission Filtering

Permission phải được attach vào chunk.

```json
{
  "tenant_id": "company-alpha",
  "workspace_id": "hr",
  "visibility": "department",
  "allowed_roles": ["employee", "hr"]
}
```

Khi dùng parent-child hoặc context expansion, phải đảm bảo:

- child chunk được phép
- parent chunk được phép
- neighboring chunks được phép

Không được retrieve child được phép rồi expand sang parent/private section không được phép.

## 29. Chunking Và Cost

Chunking ảnh hưởng cost ở nhiều tầng.

| Quyết định | Cost impact |
|---|---|
| Chunk nhỏ hơn | Nhiều chunks hơn, embedding/storage tăng |
| Overlap cao hơn | Duplicate nhiều, embedding cost tăng |
| Chunk lớn hơn | Ít vector hơn, nhưng prompt token tăng |
| Parent-child | Storage và metadata phức tạp hơn |
| Semantic chunking | Ingestion compute tăng |
| Table row chunks | Nhiều chunks nếu table lớn |

Cost không phải lý do để chunk kém. Nhưng cần đo.

Metrics:

- chunks per document
- avg tokens per chunk
- embedding cost per document
- retrieved tokens per query
- duplicate chunk ratio

## 30. Production Checklist

- Có chunking strategy theo document type.
- Có token count cho mỗi chunk.
- Có chunk hash.
- Có stable chunk ID.
- Có chunking strategy version.
- Có document version ID.
- Có section/page metadata.
- Có permission metadata.
- Có max chunks per document.
- Có validate empty/oversized chunks.
- Có deduplicate chunks.
- Có preserve heading/table/code.
- Có test tiếng Việt.
- Có evaluation trước/sau khi đổi strategy.
- Có debug view chunk.
- Có context expansion strategy nếu dùng chunk nhỏ.
- Có policy re-index khi đổi chunking strategy.

## 31. Recommended Starting Point Cho Project Hiện Tại

Giai đoạn đầu:

```text
Default:
  RecursiveChunker
  chunk_size=512
  chunk_overlap=64

HR/legal:
  SectionChunker + recursive split trong section quá dài

Technical docs:
  Heading-aware recursive chunker

FAQ:
  Q/A pair chunker
```

Giai đoạn sau:

```text
Add parent-child retrieval
Add context_expander
Add hybrid retrieval evaluation
Add semantic chunking for meeting notes/wiki pages
```

Module nên mở rộng:

- `app/ingestion/chunkers/section_chunker.py`
- `app/ingestion/chunkers/recursive_chunker.py`
- thêm `heading_chunker.py`
- thêm `table_chunker.py`
- thêm `qa_chunker.py`
- `app/retrieval/context_expander.py`

## 32. Tóm Tắt Chương

Chunking là nơi quyết định đơn vị tri thức của RAG. Một chunk tốt phải:

- đủ nhỏ để retrieve chính xác
- đủ lớn để giữ context
- không phá heading/table/code
- có metadata đầy đủ
- có permission metadata
- có stable ID và hash
- có strategy version
- được đánh giá bằng retrieval metrics

Đừng chọn chunk size bằng cảm giác. Hãy bắt đầu với baseline hợp lý, tạo golden dataset nhỏ, đo Recall@k/MRR/citation accuracy, rồi cải tiến.

Part tiếp theo sẽ chuyển sang index layer: embedding, vectorization, vector database và retrieval strategies.
