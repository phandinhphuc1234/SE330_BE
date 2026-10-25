# RAG-P0-06 Prompt-Injection and Citation Regression Evidence — 2026-10-18

Branch: `codex/rag-v1-production-readiness`
Baseline commit: `0e974ae317c5383b1183de09025091feb291a3f2`

## Regression coverage added

Four deterministic security/error boundaries are now explicit in unit tests:

1. `test_answer_prompt_escapes_prompt_injection_from_evidence_and_source_id`
   proves ebook text cannot close the prompt's `<source>` element, create a new
   `<question>` element or inject an attribute through a source ID.
2. `test_generate_treats_prompt_injection_in_ebook_as_escaped_data` proves the
   orchestration keeps the immutable system rule that evidence is untrusted and
   sends the injected PDF text only as escaped source data.
3. `test_generate_rejects_retrieval_evidence_from_another_ebook_before_llm`
   proves a high-scoring foreign-ebook hit is discarded and never reaches the
   model.
4. `test_generate_reports_malformed_llm_json_as_operational_error` proves broken
   provider JSON becomes `LLM_INVALID_RESPONSE`, rather than a fake abstention.

The existing
`test_generate_fails_closed_when_llm_invents_citation` remains the regression
for a model-selected citation ID outside the current evidence allow-list. It
returns `INVALID_CITATIONS`, no answer citation and no grounded claim.

## Test executions

Focused new/changed test files:

```text
11 passed in 2.74s
```

Wider answer boundary suite:

```text
18 passed in 18.76s
```

The wider suite included prompt building, answer generation, internal answer
API, provider error mapping and the hybrid-to-answer integration flow.

Six existing Pydantic alias warnings were emitted by the integration request
models. They are not failures and were already present in the full baseline;
they remain visible for later cleanup rather than being suppressed.

## Boundary of the evidence

The prompt-injection check is deterministic code-level evidence: it verifies
the structural boundary before provider invocation. It does not claim that any
LLM is mathematically immune to every adversarial string. The runtime still
fails closed on foreign/missing citations and malformed model output.

## Conclusion

`RAG-P0-06` passed. Prompt structure, ebook scope, citation allow-list and model
schema failures now have named regression tests with passing output.
