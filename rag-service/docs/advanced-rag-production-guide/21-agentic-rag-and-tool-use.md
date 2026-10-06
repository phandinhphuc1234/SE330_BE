# 21. Agentic RAG And Tool Use

Agentic RAG là phiên bản RAG nơi hệ thống không chỉ đi theo pipeline cố định, mà có thể lập kế hoạch, chọn tool, gọi nhiều retrievers, query SQL, tính toán, hỏi lại user hoặc yêu cầu human approval.

Agentic RAG rất mạnh, nhưng cũng khó kiểm soát hơn, tốn cost hơn và cần logging/guardrails mạnh hơn.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

## 1. Agentic RAG Là Gì?

Deterministic RAG:

```text
query -> rewrite -> retrieve -> rerank -> prompt -> answer
```

Agentic RAG:

```text
query
  ↓
agent plans steps
  ↓
agent chooses tools
  ↓
retrieve/query/calculate/lookup
  ↓
agent observes results
  ↓
agent decides next step
  ↓
answer
```

Example:

User asks:

```text
Tháng trước payment timeout tăng bao nhiêu và runbook xử lý hiện tại là gì?
```

Agent may:

1. Use SQL tool to count support tickets last month.
2. Use SQL tool to count previous month.
3. Use retriever tool to find payment timeout runbook.
4. Use calculator tool to compute percentage increase.
5. Generate answer with citations.

## 2. Khi Nào Cần Agent?

Cần agent khi câu hỏi:

- cần nhiều bước
- cần tool ngoài document retrieval
- cần query live database
- cần tính toán
- cần so sánh nhiều nguồn
- cần quyết định strategy động
- cần hỏi user xác nhận trước action

Không cần agent khi:

- câu hỏi lookup tài liệu đơn giản
- policy Q/A
- FAQ
- technical doc search bình thường

Đừng dùng agent chỉ vì nghe hiện đại. Deterministic RAG dễ debug và production hơn.

## 3. Planner

Planner là component tạo kế hoạch.

Input:

```text
Question: Tháng trước có bao nhiêu ticket payment timeout và cách xử lý là gì?
```

Plan:

```json
[
  {"step": 1, "tool": "sql_tool", "purpose": "count payment timeout tickets last month"},
  {"step": 2, "tool": "retriever_tool", "purpose": "find payment timeout runbook"},
  {"step": 3, "tool": "calculator_tool", "purpose": "compute trend if previous month count exists"},
  {"step": 4, "tool": "final_answer", "purpose": "answer with citations"}
]
```

### Planner risks

- tạo plan quá dài
- gọi tool không cần
- loop vô hạn
- chọn sai tool

Controls:

- max steps
- tool allowlist
- timeout
- cost budget
- human approval for risky actions

## 4. Tool Calling

Tool calling là cho model gọi function/API đã định nghĩa.

Examples:

- `retriever.search(query, filters)`
- `sql.query(readonly_sql)`
- `calculator.calculate(expression)`
- `document_lookup.get(document_id)`
- `ticket_lookup.search(filters)`

Tool schema:

```json
{
  "name": "retriever_search",
  "description": "Search authorized internal knowledge base chunks.",
  "parameters": {
    "query": "string",
    "document_type": "string",
    "top_k": "integer"
  }
}
```

## 5. Retriever As Tool

Retriever tool lets agent search docs on demand.

Example:

```python
async def retriever_search(query: str, document_type: str | None, user_context):
    filters = build_permission_filter(user_context)
    if document_type:
        filters["document_type"] = document_type
    return await retrieval_pipeline.retrieve(query, filters)
```

Important:

- tool must enforce permission internally
- agent cannot pass arbitrary tenant/user filters
- log every tool call

## 6. SQL Tool

SQL tool allows structured queries.

Use cases:

- ticket counts
- usage metrics
- current product status
- user onboarding progress

### Security rules

- read-only
- allowlisted tables/views
- row-level security
- query timeout
- max rows
- no raw SQL from model without validation
- audit every query

Better:

```text
natural language -> validated query builder
```

instead of free-form SQL.

## 7. Web Search Tool

For internal RAG, web search is optional and risky.

Use when:

- public documentation needed
- external dependency docs
- current events

Risks:

- untrusted web content
- prompt injection
- data exfiltration
- answer not based on internal policy

If used, separate internal sources and web sources clearly.

## 8. Calculator Tool

Use for:

- percentages
- cost estimates
- date calculations
- totals

LLMs can make arithmetic mistakes. Calculator tool improves reliability.

Example:

```text
Tickets last month = 120
Previous month = 80
Increase = 50%
```

## 9. Document Lookup Tool

Tool fetches specific document/chunk by ID.

Useful when:

- citation verification
- user asks about a specific document
- agent needs full parent section

Must check permission.

## 10. Human Approval

Some actions need human approval:

- deleting docs
- re-indexing large corpus
- sending sensitive data to external provider
- changing permissions
- generating customer-facing response

Agent should propose:

```text
I need approval to run a full re-index of HR documents. Estimated cost: ...
```

For this project, keep agents read-only at first.

## 11. Guardrails

Guardrails are constraints on agent behavior.

Examples:

- max tool calls = 5
- max total cost
- allowed tools per role
- no write tools without approval
- no external web for private queries
- all retrieved chunks must be authorized
- no secrets in output

## 12. Deterministic RAG Vs Agentic RAG

| Aspect | Deterministic RAG | Agentic RAG |
|---|---|---|
| Flow | fixed | dynamic |
| Debug | easier | harder |
| Cost | predictable | variable |
| Latency | lower | higher |
| Control | stronger | needs guardrails |
| Best for | Q/A over docs | multi-step tasks |
| Risk | lower | higher |

Start deterministic. Add agentic behavior only where needed.

## 13. Agentic RAG Cảnh Báo

Agentic RAG:

- khó kiểm soát hơn
- tốn cost hơn
- latency cao hơn
- cần logging mạnh hơn
- cần security tool permissions
- cần eval riêng cho tool traces

Common failure:

- agent loops
- agent chooses wrong tool
- agent over-retrieves
- agent leaks via tool output
- agent makes unsupported conclusion

## 14. Agent Trace

Every agent run should log:

```json
{
  "request_id": "req_123",
  "agent_version": "agent-rag-v1",
  "steps": [
    {
      "step": 1,
      "tool": "retriever_search",
      "input": {"query": "payment timeout runbook"},
      "output_summary": "3 chunks",
      "latency_ms": 120
    },
    {
      "step": 2,
      "tool": "sql_query",
      "input": {"metric": "ticket_count", "month": "2026-04"},
      "output_summary": "120",
      "latency_ms": 80
    }
  ],
  "total_cost_usd": 0.0032
}
```

Do not log sensitive full tool outputs unless policy allows.

## 15. Agentic RAG Evaluation

Evaluate:

- final answer correctness
- tool choice correctness
- number of steps
- tool permission compliance
- cost
- latency
- groundedness

Test cases:

```text
Agent should use SQL for count questions.
Agent should use retriever for policy questions.
Agent must not use web search for private HR questions.
Agent must stop after max steps.
Agent must ask approval before write actions.
```

## 16. Recommended Implementation For Project

Phase 1:

- no agent
- deterministic RAG

Phase 2:

- expose retriever as internal service/tool
- add SQL read-only tool for analytics questions

Phase 3:

- add planner for selected intents
- max 3-5 steps
- read-only tools only
- strong tracing

Phase 4:

- human approval for write tools
- agent eval suite

## 17. Minimal Agent Tool Registry

```python
class ToolRegistry:
    def __init__(self):
        self.tools = {}

    def register(self, name, tool, allowed_roles):
        self.tools[name] = {
            "tool": tool,
            "allowed_roles": allowed_roles,
        }

    async def call(self, name, args, user_context):
        entry = self.tools[name]
        if not set(user_context.roles).intersection(entry["allowed_roles"]):
            raise ForbiddenError("Tool not allowed")
        return await entry["tool"](**args, user_context=user_context)
```

## 18. Production Checklist

- Deterministic RAG works before agentic RAG.
- Agent has max steps.
- Tool allowlist exists.
- Tools enforce permissions internally.
- SQL tool is read-only and validated.
- Web search disabled for private queries unless explicitly allowed.
- Tool calls are traced.
- Cost budget enforced.
- Human approval for write/risky actions.
- Agent eval tests exist.
- Fallback if agent fails.

## 19. Tóm Tắt Chương

Agentic RAG là mạnh nhưng không phải điểm bắt đầu. Nó phù hợp khi câu hỏi cần nhiều bước hoặc nhiều tools. Với Internal KB, hãy xây deterministic RAG production-grade trước:

```text
ingestion -> hybrid retrieval -> rerank -> grounded generation -> eval/security
```

Sau đó thêm agent cho các use case thật sự cần SQL/tool/planning.

Chương tiếp theo là roadmap triển khai project theo phase từ basic đến production.
