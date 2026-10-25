# RAG-P0-04 Retrieval and Authorization Evidence — 2026-10-18

Branch: `codex/rag-v1-production-readiness`
Baseline commit: `0e974ae317c5383b1183de09025091feb291a3f2`

## Internal retrieval

The authenticated internal retrieval smoke test ran against the real indexed
document with this scope:

- Book ID: `3342`
- Ebook ID: `10`
- External document ID: `doc_ebook_10`
- Retrieval mode: `hybrid`
- Requested top K: `5`

Query:

```text
How much did their furnished rooms cost each week?
```

Result:

- HTTP `200`.
- Five hits returned.
- The highest-ranked hit was page 1, chunk 0, score `0.7351522`.
- Every returned citation remained in book `3342`, ebook `10`.
- The applied filters included active document state, source type
  `LIBRARY_EBOOK`, embedding version, book ID and ebook ID.

Negative internal cases:

| Case | Result |
|---|---|
| Missing `X-RAG-API-Key` | HTTP `401`, `INVALID_INTERNAL_API_KEY` |
| Wrong `X-RAG-API-Key` | HTTP `401`, `INVALID_INTERNAL_API_KEY` |
| Wrong book/ebook scope (`999999`) | HTTP `200`, zero hits, no leaked chunk |

No API key or token value was written to this evidence file.

## Public Spring flow

The public test went through Spring rather than calling the RAG service from
the browser:

1. Demo member login returned HTTP `200` and an access token.
2. The member had one active ebook loan for book `3342`.
3. `POST /api/ebooks/3342/reading-sessions` returned HTTP `200`.
4. The session was for book `3342`, ebook `10`; a raw session token was returned
   only by the session-creation response.
5. `POST /api/ebooks/3342/reader/semantic-search`, with the bearer token and
   `X-Reading-Session`, returned HTTP `200` and five scoped hits.

The public top results matched the internal ranking and all reported citation
objects carried book `3342` and ebook `10`.

Authorization negatives:

| Actor/case | Result |
|---|---|
| Anonymous semantic search | HTTP `401`, `UNAUTHORIZED` |
| Authenticated admin on member-only reader endpoint | HTTP `403`, `ACCESS_DENIED` |
| Authenticated member opening ebook `3226` without a loan | HTTP `403`, `EBOOK_LOAN_REQUIRED` |

The RAG API itself is bound to `127.0.0.1:8000` in the local Compose runtime;
the browser-facing contract remains the Spring API and does not receive the
internal RAG credential.

## Conclusion

`RAG-P0-04` passed. The positive path proves retrieval through the actual
authorization/session boundary, while the negative cases prove that API-key,
role, loan and ebook-scope checks do not silently fall through.
