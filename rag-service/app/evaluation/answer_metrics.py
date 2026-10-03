def exact_match(expected: str, actual: str) -> float:
    return float(expected.strip() == actual.strip())
