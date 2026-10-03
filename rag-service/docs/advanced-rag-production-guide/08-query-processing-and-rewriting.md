# 08. Query Processing And Rewriting

Query processing là lớp xử lý câu hỏi của user trước khi retrieval. Đây là nơi biến một câu hỏi tự nhiên, mơ hồ, thiếu metadata thành một **retrieval plan** đủ rõ để hệ thống tìm đúng tài liệu.

Nếu ingestion tạo ra tri thức tốt, retrieval lấy candidates tốt, thì query processing là người "phiên dịch" câu hỏi của user sang ngôn ngữ mà retriever hiểu.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/retrieval/query_rewriter.py`
- `app/retrieval/retrieval_pipeline.py`
- `app/retrieval/vector_retriever.py`
- `app/retrieval/keyword_retriever.py`
- `app/core/dependencies.py`
- `app/auth/`
- `app/workspaces/`

## 1. Query Processing Là Gì?

Query processing là chuỗi xử lý câu hỏi trước retrieval.

Raw query:

```text
chính sách nghỉ phép năm ngoái thay đổi gì?
```

Sau query processing:

```json
{
  "original_query": "chính sách nghỉ phép năm ngoái thay đổi gì?",
  "normalized_query": "chính sách nghỉ phép năm ngoái thay đổi gì?",
  "language": "vi",
  "intent": "policy_change_lookup",
  "rewritten_query": "annual leave policy changes in 2025",
  "keyword_query": "nghỉ phép chính sách 2025 annual leave",
  "filters": {
    "document_type": "hr_policy",
    "department": "HR",
    "year": 2025
  }
}
```

Mục tiêu là giúp retrieval:

- tìm đúng topic
- tìm đúng document type
- tìm đúng thời gian/version
- dùng đúng keyword
- áp dụng đúng permission
- giảm ambiguity

## 2. Vì Sao Cần Query Processing?

### 2.1. User hỏi không giống cách document viết

User hỏi:

```text
nhân viên mới vào làm cần làm gì?
```

Document viết:

```text
Employee onboarding process includes account provisioning, orientation, mentor assignment...
```

Query rewriting giúp bridge hai cách diễn đạt.

### 2.2. User đưa filter bằng ngôn ngữ tự nhiên

User hỏi:

```text
meeting tháng trước nói gì về payment timeout?
```

Hệ thống cần extract:

```json
{
  "document_type": "meeting_notes",
  "time_range": "last_month",
  "topic": "payment timeout"
}
```

### 2.3. User hỏi mơ hồ

```text
policy đó thay đổi gì?
```

"đó" là gì? Cần user context:

- conversation history
- page hiện tại
- workspace hiện tại
- previous document reference

### 2.4. Retrieval cần nhiều dạng query

Vector search thích semantic query.

BM25 thích keyword query.

Hybrid search có thể cần cả:

- rewritten semantic query
- keyword-expanded query
- original query
- generated HyDE document

## 3. Query Processing Nằm Ở Đâu Trong Pipeline?

```text
User query
  ↓
Authentication and user context
  ↓
Query processing
  ↓
Metadata + permission filters
  ↓
Retrieval
  ↓
Reranking
  ↓
Generation
```

Trong `retrieval_pipeline.py`, query processing nên là bước đầu:

```python
processed_query = await query_processor.process(
    raw_query=query,
    user_context=current_user_context,
    conversation_history=history,
)
```

## 4. Query Normalization

### Nó là gì?

Normalize query là làm sạch câu hỏi để xử lý ổn định hơn.

Ví dụ:

```text
"  Chính   sách NGHỈ PHÉP năm nay??? "
```

thành:

```text
"chính sách nghỉ phép năm nay?"
```

### Các bước phổ biến

- trim whitespace
- normalize Unicode
- normalize case nếu phù hợp
- remove repeated punctuation
- normalize quotes
- normalize date phrases nếu rule-based
- giữ nguyên technical tokens như `ERR_PAYMENT_TIMEOUT`

### Lỗi thường gặp

Đừng lower-case mù nếu domain có case-sensitive identifiers:

```text
OrderService
orderService
ORDER_SERVICE
```

Với code/API docs, case có thể quan trọng.

### Pseudo-code

```python
def normalize_query(query: str) -> str:
    query = unicodedata.normalize("NFC", query)
    query = query.strip()
    query = re.sub(r"\s+", " ", query)
    query = re.sub(r"[?]{2,}", "?", query)
    return query
```

## 5. Language Detection

### Nó là gì?

Detect query đang viết bằng ngôn ngữ nào.

Ví dụ:

```json
{
  "language": "vi",
  "confidence": 0.96
}
```

### Vì sao cần?

Internal KB có thể Việt-Anh lẫn lộn:

- user hỏi tiếng Việt
- technical docs tiếng Anh
- HR docs tiếng Việt

Language detection giúp:

- chọn rewrite prompt
- quyết định có dịch query không
- chọn embedding model nếu có nhiều model
- giữ answer language theo user

### Trade-off

- Rule/library nhanh nhưng có thể sai với query ngắn.
- LLM detect chính xác hơn nhưng tốn latency/cost.

Với query ngắn:

```text
payment timeout?
```

khó nói là tiếng Anh hay mixed. Có thể set `language=mixed`.

## 6. Spell Correction

### Nó là gì?

Sửa lỗi chính tả hoặc typo trong query.

Ví dụ:

```text
nghĩ phép năm nay
```

thành:

```text
nghỉ phép năm nay
```

### Vì sao cần?

BM25 rất nhạy với typo. Vector search chịu typo tốt hơn nhưng không phải lúc nào cũng ổn.

### Khi nào nên dùng?

- search docs cho người dùng phổ thông
- support tickets
- HR/product FAQ

### Khi nào cẩn thận?

Technical identifiers không nên sửa bừa:

```text
ERR_PAYMNT_TIMEOUT
```

có thể là typo, nhưng cũng có thể là mã nội bộ.

### Production recommendation

- spell correct natural language parts
- preserve code/error/API tokens
- log original and corrected query
- allow fallback to original query

## 7. Query Expansion

### Nó là gì?

Query expansion thêm synonyms, related terms, acronyms.

Ví dụ:

```text
nghỉ phép
```

expand:

```text
nghỉ phép annual leave leave entitlement paid time off PTO
```

### Vì sao cần?

Internal docs có thể dùng nhiều thuật ngữ:

- "nghỉ phép"
- "annual leave"
- "leave entitlement"
- "PTO"

### Cách làm

1. Dictionary/domain glossary.
2. LLM expansion.
3. Query rewrite prompt.
4. Historical search logs.

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Tăng recall | Dễ thêm noise |
| BM25 tốt hơn | Có thể retrieve nhầm |
| Hữu ích với acronym | Cần glossary bảo trì |

### Glossary example

```json
{
  "PTO": ["paid time off", "annual leave", "nghỉ phép"],
  "SOP": ["standard operating procedure", "quy trình chuẩn"],
  "Order Service": ["order-service", "order backend", "service order"]
}
```

## 8. Query Rewriting

### Nó là gì?

Query rewriting biến câu hỏi tự nhiên thành câu search rõ hơn.

Raw:

```text
service order xử lý payment sao vậy?
```

Rewritten:

```text
order service payment processing flow, payment gateway callback, payment retry policy
```

### Vì sao cần?

- user dùng từ không chuẩn
- document viết bằng tiếng Anh
- câu hỏi thiếu context
- cần query phù hợp embedding

### Rewriting prompt example

```text
You rewrite user questions for retrieval over an internal company knowledge base.
Keep important product names, service names, error codes, dates, and acronyms.
Return a concise search query. Do not answer the question.

User question:
{query}

User context:
workspace={workspace}
department={department}

Rewritten search query:
```

### Production concerns

- Không rewrite làm mất keyword quan trọng.
- Không hallucinate filter không có.
- Không đổi intent.
- Log raw + rewritten query.
- Có fallback raw query nếu rewrite fail.

## 9. Multi-Query Generation

### Nó là gì?

Tạo nhiều query biến thể để tăng recall.

Ví dụ:

```text
Raw query:
"quy trình onboarding nhân viên mới ra sao?"

Generated queries:
1. employee onboarding process
2. new hire onboarding checklist
3. account provisioning and orientation process for new employees
4. quy trình hội nhập nhân viên mới
```

### Flow

```text
Raw query
  ↓
Generate N search queries
  ↓
Retrieve for each query
  ↓
Merge by RRF
  ↓
Rerank
```

### Khi nào dùng?

- query ambiguous
- first retrieval score thấp
- question quan trọng cần recall cao
- corpus đa ngôn ngữ

### Khi nào không dùng?

- latency budget thấp
- query rất rõ
- user hỏi exact error code
- cost cần tối ưu

### Pseudo-code

```python
async def multi_query_retrieve(raw_query):
    queries = await query_rewriter.generate_queries(raw_query, n=4)
    result_sets = []

    for q in queries:
        results = await vector_retriever.search(q, top_k=20)
        result_sets.append(results)

    return rrf_merge(result_sets)
```

## 10. HyDE

HyDE là **Hypothetical Document Embeddings**.

### Nó là gì?

Thay vì embed query trực tiếp, dùng LLM sinh một câu trả lời/tài liệu giả định, rồi embed tài liệu giả định đó để retrieve.

Flow:

```text
User query
  ↓
LLM generates hypothetical answer/document
  ↓
Embed hypothetical document
  ↓
Vector search
```

Ví dụ:

```text
Query:
"nhân viên mới cần làm gì trong tuần đầu?"

Hypothetical document:
"The new employee onboarding process includes creating accounts, joining orientation, meeting the mentor, reading HR policy..."
```

Embedding hypothetical document có thể gần với actual onboarding docs hơn raw query.

### Vì sao cần?

HyDE tốt khi:

- query ngắn
- query không dùng từ giống document
- semantic gap lớn

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Tăng recall semantic | Tốn LLM call |
| Hữu ích cho query mơ hồ | Có thể hallucinate direction sai |
| Tốt với long-form docs | Latency cao |

### Production recommendation

Không bật HyDE cho mọi request. Dùng khi:

- initial retrieval score thấp
- user query quá ngắn
- query thuộc loại exploratory

## 11. Intent Classification

### Nó là gì?

Xác định user muốn loại tác vụ gì.

Ví dụ intents:

- `policy_lookup`
- `technical_doc_lookup`
- `troubleshooting`
- `comparison`
- `summary`
- `how_to`
- `definition`
- `chit_chat`
- `out_of_scope`

### Vì sao cần?

Intent quyết định retrieval strategy.

Ví dụ:

| Intent | Strategy |
|---|---|
| `policy_lookup` | metadata filter HR/policy, cite strict |
| `technical_doc_lookup` | hybrid BM25 + vector, preserve code tokens |
| `troubleshooting` | support tickets + runbooks, recent docs |
| `comparison` | retrieve multiple versions/docs |
| `summary` | retrieve broader parent sections |
| `out_of_scope` | no retrieval or safe refusal |

### Example

```text
User: "Có lỗi nào thường gặp khi deploy backend không?"
Intent: troubleshooting
Filters: document_type in ["runbook", "support_ticket", "technical_doc"]
```

## 12. Entity Extraction

### Nó là gì?

Tách entities quan trọng từ query.

Ví dụ:

```text
ERR_PAYMENT_TIMEOUT trong order-service xử lý thế nào?
```

Entities:

```json
{
  "error_code": "ERR_PAYMENT_TIMEOUT",
  "service": "order-service"
}
```

### Vì sao cần?

Entities giúp:

- metadata filtering
- BM25 keyword boosting
- routing retriever
- better prompt context

### Entity types trong Internal KB

- service name
- product name
- department
- policy name
- error code
- API endpoint
- person/team
- date/time
- document title

### Production concern

Entity extraction không được tự tin quá mức. Nếu extract sai service, filter quá hẹp sẽ miss docs.

Nên có confidence:

```json
{
  "entity": "order-service",
  "type": "service",
  "confidence": 0.91
}
```

## 13. Metadata Extraction From Query

### Nó là gì?

Tách metadata filters từ câu hỏi.

Ví dụ:

```text
chính sách nghỉ phép năm 2025 của phòng HR
```

Filters:

```json
{
  "document_type": "hr_policy",
  "department": "HR",
  "year": 2025
}
```

### Vì sao cần?

Không có metadata filters, vector search có thể retrieve nhầm:

- policy năm khác
- phòng ban khác
- archived docs

### Cẩn thận filter quá hẹp

Nếu filter sai, retrieval empty.

Strategy:

1. Apply high-confidence filters.
2. Keep low-confidence filters as soft signals.
3. If empty results, retry with relaxed filters.

Pseudo-code:

```python
results = await retrieve(query, filters=hard_filters)

if not results and soft_filters:
    results = await retrieve(query, filters=relax(filters))
```

## 14. Time Filter Extraction

### Nó là gì?

Tách thời gian từ query.

Ví dụ:

```text
năm ngoái
tháng trước
quý này
trước release 2.4
trong 30 ngày gần đây
```

### Vì sao cần?

Internal docs versioned theo thời gian. Câu hỏi thời gian cần retrieve đúng version.

Ví dụ hiện tại là năm 2026:

```text
"năm ngoái" -> 2025
```

Time filter:

```json
{
  "start_date": "2025-01-01",
  "end_date": "2025-12-31"
}
```

### Production concern

- Timezone.
- Fiscal year khác calendar year.
- Document `created_at` khác `effective_date`.
- Query comparison cần nhiều time ranges.

Ví dụ:

```text
chính sách nghỉ phép năm ngoái thay đổi gì?
```

Cần retrieve:

- policy 2025
- policy 2026

để compare, không chỉ filter 2025.

## 15. User Context Injection

### Nó là gì?

Dùng thông tin user/session để hiểu query.

User context:

```json
{
  "user_id": "u_42",
  "tenant_id": "company-alpha",
  "department": "Engineering",
  "roles": ["backend_engineer", "employee"],
  "workspace_id": "engineering",
  "current_page": "order-service-docs"
}
```

### Vì sao cần?

User hỏi:

```text
timeout này xử lý sao?
```

Nếu họ đang xem order service docs, "timeout này" có thể là payment timeout.

### Cẩn thận

User context giúp retrieval tốt hơn nhưng có thể bias sai.

Nên log context đã dùng:

```json
{
  "used_context": {
    "workspace_id": "engineering",
    "current_page": "order-service-docs"
  }
}
```

## 16. Conversation History

RAG chat thường có multi-turn.

Ví dụ:

```text
User: Chính sách nghỉ phép năm nay là gì?
Assistant: ...
User: So với năm ngoái thì sao?
```

Query thứ hai cần rewrite:

```text
Compare annual leave policy between 2025 and 2026.
```

### Conversation-aware rewrite

Prompt:

```text
Given the conversation history and latest user question,
rewrite the latest question into a standalone retrieval query.
Do not answer.
Preserve dates, entities, and document references.
```

### Lỗi thường gặp

- Dùng quá nhiều history làm tốn token.
- History cũ bias sai.
- Rewrite làm mất query mới.

Khuyến nghị:

- chỉ dùng vài turns gần nhất
- summarize history nếu dài
- log standalone query

## 17. Retrieval Plan Object

Thay vì chỉ return string, query processor nên return structured object.

```python
@dataclass
class ProcessedQuery:
    original_query: str
    normalized_query: str
    standalone_query: str
    rewritten_query: str
    keyword_query: str
    language: str
    intent: str
    entities: dict
    filters: dict
    soft_filters: dict
    retrieval_strategy: str
    confidence: float
```

Ví dụ:

```json
{
  "original_query": "chính sách nghỉ phép năm ngoái thay đổi gì?",
  "standalone_query": "chính sách nghỉ phép năm ngoái thay đổi gì?",
  "rewritten_query": "annual leave policy changes between 2025 and 2026",
  "keyword_query": "nghỉ phép annual leave 2025 2026 thay đổi",
  "language": "vi",
  "intent": "policy_comparison",
  "entities": {
    "policy": "annual_leave"
  },
  "filters": {
    "document_type": "hr_policy",
    "department": "HR"
  },
  "soft_filters": {
    "years": [2025, 2026]
  },
  "retrieval_strategy": "hybrid_multi_version",
  "confidence": 0.88
}
```

## 18. Pseudo-code Query Rewriting Pipeline

```python
class QueryProcessor:
    def __init__(self, llm_client, glossary, clock):
        self.llm_client = llm_client
        self.glossary = glossary
        self.clock = clock

    async def process(self, raw_query, user_context, conversation_history):
        normalized = normalize_query(raw_query)
        language = detect_language(normalized)

        standalone = await self.rewrite_standalone(
            query=normalized,
            conversation_history=conversation_history,
        )

        intent = await self.classify_intent(standalone, user_context)
        entities = extract_entities_rule_based(standalone)

        if needs_llm_entity_extraction(intent, entities):
            entities.update(await self.extract_entities_llm(standalone))

        time_filters = extract_time_filters(
            query=standalone,
            now=self.clock.now(),
            timezone=user_context.timezone,
        )

        metadata_filters = build_metadata_filters(
            intent=intent,
            entities=entities,
            time_filters=time_filters,
        )

        rewritten_query = await self.rewrite_for_semantic_search(
            standalone,
            intent=intent,
            entities=entities,
            language=language,
        )

        keyword_query = expand_keywords(
            standalone,
            entities=entities,
            glossary=self.glossary,
        )

        strategy = choose_retrieval_strategy(intent, entities, metadata_filters)

        return ProcessedQuery(
            original_query=raw_query,
            normalized_query=normalized,
            standalone_query=standalone,
            rewritten_query=rewritten_query,
            keyword_query=keyword_query,
            language=language,
            intent=intent,
            entities=entities,
            filters=metadata_filters.hard,
            soft_filters=metadata_filters.soft,
            retrieval_strategy=strategy,
            confidence=metadata_filters.confidence,
        )
```

## 19. Example: HR Policy Query

User hỏi:

```text
chính sách nghỉ phép năm ngoái thay đổi gì?
```

Giả sử hiện tại là 2026.

### Processing

```json
{
  "language": "vi",
  "intent": "policy_comparison",
  "topic": "annual_leave",
  "time_reference": "last_year",
  "years_to_compare": [2025, 2026],
  "rewritten_query": "annual leave policy changes between 2025 and 2026",
  "keyword_query": "nghỉ phép annual leave policy 2025 2026 thay đổi",
  "filters": {
    "department": "HR",
    "document_type": "hr_policy"
  }
}
```

### Retrieval plan

```text
Retrieve HR policy chunks for 2025 and 2026
  ↓
Rerank by annual leave relevance
  ↓
Generate comparison answer
  ↓
Cite both versions
```

## 20. Example: Technical Query

User hỏi:

```text
ERR_PAYMENT_TIMEOUT trong order-service xử lý thế nào?
```

Processing:

```json
{
  "language": "mixed",
  "intent": "troubleshooting",
  "entities": {
    "error_code": "ERR_PAYMENT_TIMEOUT",
    "service": "order-service"
  },
  "rewritten_query": "order-service ERR_PAYMENT_TIMEOUT handling payment timeout retry policy",
  "keyword_query": "ERR_PAYMENT_TIMEOUT order-service payment timeout",
  "filters": {
    "workspace_id": "engineering",
    "document_type": ["runbook", "technical_doc", "support_ticket"]
  },
  "retrieval_strategy": "hybrid_exact_keyword_boost"
}
```

Ở đây BM25 rất quan trọng vì error code exact.

## 21. Choosing Retrieval Strategy

Query processor có thể chọn strategy.

| Condition | Strategy |
|---|---|
| Query has error code/API path/service name | Hybrid with BM25 boost |
| Query is policy lookup | Metadata-filtered vector + BM25 |
| Query asks comparison over time | Multi-version retrieval |
| Query is ambiguous | Multi-query or ask clarification |
| Initial score low | Retry with HyDE or relaxed filters |
| User lacks workspace context | Search public/all authorized workspaces |

Pseudo-code:

```python
def choose_retrieval_strategy(intent, entities, filters):
    if entities.get("error_code") or entities.get("api_path"):
        return "hybrid_keyword_boost"

    if intent == "policy_comparison":
        return "multi_version_hybrid"

    if intent == "summary":
        return "parent_document_retrieval"

    return "hybrid"
```

## 22. Debug Query Processing

Khi retrieval sai, kiểm tra:

- raw query có được nhận đúng không?
- normalized query có phá technical token không?
- language detect đúng không?
- standalone query có đúng không?
- rewrite có mất keyword không?
- intent có đúng không?
- entity extraction có đúng không?
- time filter có đúng timezone/năm không?
- metadata filter có quá hẹp không?
- permission filter có đúng user không?

Debug trace:

```json
{
  "raw_query": "policy đó thay đổi gì?",
  "conversation_used": true,
  "standalone_query": "chính sách nghỉ phép năm 2026 thay đổi gì so với 2025?",
  "rewritten_query": "annual leave policy changes between 2025 and 2026",
  "intent": "policy_comparison",
  "filters": {
    "document_type": "hr_policy",
    "department": "HR"
  }
}
```

## 23. Monitoring Query Processing

### Metrics

- `query_processing_latency_ms`
- `query_rewrite_latency_ms`
- `query_rewrite_failures_total`
- `intent_distribution`
- `language_distribution`
- `hyde_usage_total`
- `multi_query_usage_total`
- `filter_relaxation_total`
- `empty_retrieval_after_filter_total`

### Logs

Log raw query cẩn thận vì có thể chứa sensitive data.

Production nên:

- redact PII nếu cần
- lưu query theo tenant policy
- không log secrets
- cho phép opt-out nếu compliance yêu cầu

## 24. Failure Cases

| Failure | Impact | Detection | Recovery | Prevention |
|---|---|---|---|---|
| Rewrite mất keyword | Miss exact docs | Compare raw vs rewritten | Search raw too | Preserve entities |
| Intent sai | Wrong filters | eval/user feedback | fallback strategy | intent tests |
| Time extraction sai | Wrong version docs | trace filters | ask clarification | timezone/date parser |
| Filter quá hẹp | Empty retrieval | empty results | relax filters | confidence scoring |
| HyDE hallucinate direction | Wrong retrieval | low rerank relevance | fallback raw query | use HyDE selectively |
| User context bias sai | Wrong docs | trace used context | remove context | context confidence |

## 25. Production Checklist

- Có query normalization.
- Có language detection hoặc fallback.
- Có standalone rewrite cho multi-turn chat.
- Có intent classification.
- Có entity extraction cho service/error/policy/date.
- Có metadata/time filter extraction.
- Có distinction hard filters vs soft filters.
- Có permission filters luôn được inject từ user context.
- Có query rewrite cho semantic retrieval.
- Có keyword query cho BM25.
- Có optional multi-query.
- Có optional HyDE khi cần.
- Có fallback raw query nếu rewrite fail.
- Có trace raw/rewritten/filter/strategy.
- Có tests cho tiếng Việt, mixed Vietnamese-English, error code, relative date.
- Có evaluation để đo rewrite có cải thiện retrieval không.

## 26. Tóm Tắt Chương

Query processing là lớp biến câu hỏi thô thành retrieval plan. Production RAG không nên chỉ embed raw query rồi search. Hệ thống cần:

- normalize
- detect language
- preserve entities
- rewrite query
- expand keywords
- classify intent
- extract metadata/time filters
- inject user context
- choose retrieval strategy
- log/monitor kết quả

Chương tiếp theo sẽ đi vào reranking và context compression: sau khi retrieve nhiều candidates, làm sao chọn đúng context cuối cùng trong token budget.
