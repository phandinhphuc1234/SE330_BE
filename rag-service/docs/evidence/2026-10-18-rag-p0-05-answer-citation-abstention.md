# RAG-P0-05 Answer, Citation and Abstention Evidence — 2026-10-18

Branch: `codex/rag-v1-production-readiness`
Baseline commit: `0e974ae317c5383b1183de09025091feb291a3f2`

## Runtime configuration

The running RAG API used:

- Retrieval mode: `hybrid`
- LLM provider: `gemini`
- LLM model: `gemini-2.5-flash`
- Prompt version: `library-ebook-answer-v1`

No provider or internal API key was printed or stored in this document.

## Answerable case

Question:

```text
How much did their furnished rooms cost each week?
```

Internal answer result:

- HTTP `200`.
- Answer: `Their furnished rooms cost $8 a week.`
- `grounded=true`, `abstained=false`.
- One citation.
- Citation book `3342`, ebook `10`, page 1, chunk 0.
- Cited chunk ID: `doc-1-chunk-0-66a6bcec62ec`.
- Retrieval score: `0.7351522`.

The same request was then sent through the public Spring reader endpoint using
a member bearer token and reading-session token:

- `POST /api/ebooks/3342/reader/ask` returned HTTP `200`.
- Public answer: `The furnished rooms cost $8 a week.`
- `grounded=true`, `abstained=false`.
- Citation remained scoped to book `3342`, ebook `10`, page 1.

## Citation source validation

The protected reader-content endpoint returned a short-lived signed PDF URL.
Fetching that URL produced:

- HTTP `200`.
- Content type `application/pdf`.
- Content length `86822` bytes, matching the ingested source.

Together with `pageStart=1`, this proves the citation has both an accessible
source document and a deterministic PDF page target. It does not claim that a
human semantic review of every generated sentence has been automated.

## Unanswerable case

Question:

```text
According to this book, what is the capital of France?
```

Both the internal endpoint and the public Spring reader endpoint returned:

- HTTP `200`.
- `grounded=false`.
- `abstained=true`.
- Reason `MODEL_ABSTAINED`.
- Zero citations.
- The configured insufficient-evidence message instead of a guessed answer.

## Conclusion

`RAG-P0-05` passed for the factual and out-of-scope smoke cases. Provider calls,
retrieval, citation allow-list validation, Spring authorization/session checks
and signed source access all executed in the real local stack.
