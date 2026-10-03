def build_prompt(query: str, contexts: list[str]) -> str:
    context_text = "\n\n".join(contexts)
    return f"Context:\n{context_text}\n\nQuestion:\n{query}"
