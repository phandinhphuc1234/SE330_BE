def build_citations(results: list) -> list[dict]:
    return [{"source": getattr(result, "id", None)} for result in results]
