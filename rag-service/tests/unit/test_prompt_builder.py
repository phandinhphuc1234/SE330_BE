from app.generation.prompt_builder import build_prompt


def test_build_prompt_includes_query_and_context():
    prompt = build_prompt("Question?", ["Context"])
    assert "Question?" in prompt
    assert "Context" in prompt
