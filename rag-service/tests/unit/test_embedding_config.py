from app.core.config import Settings


def test_embedding_defaults_follow_gemini_mvp_decision() -> None:
    settings = Settings(_env_file=None)

    assert settings.embedding_provider == "gemini"
    assert settings.embedding_model == "gemini-embedding-2"
    assert settings.embedding_dim == 3072
    assert settings.embedding_fallback_model == "gemini-embedding-001"
    assert settings.embedding_version == "gemini-embedding-2-3072-v1"
    assert settings.embedding_text_policy == "gemini_search_title_text_v1"
    assert settings.embedding_batch_size == 8
    assert settings.embedding_max_retries == 5
    assert settings.embedding_retry_base_delay_seconds == 2.0
    assert settings.embedding_retry_max_delay_seconds == 60.0
    assert settings.embedding_retry_jitter_seconds == 0.5
    assert settings.embedding_distance_metric == "Cosine"
