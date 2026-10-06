import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.config import Settings
from app.core.exceptions import LLMError
from app.generation.llm_client import ConfiguredLLMClient


class SlowOpenAIClient(ConfiguredLLMClient):
    async def _complete_openai(self, system_prompt: str, user_prompt: str) -> str:
        await asyncio.sleep(0.05)
        return "{}"


@pytest.mark.asyncio
async def test_configured_llm_client_maps_timeout_to_stable_error():
    client = SlowOpenAIClient(
        Settings(
            llm_provider="openai",
            openai_api_key="test-key",
            llm_timeout_seconds=0.01,
        )
    )

    with pytest.raises(LLMError) as error:
        await client.complete_json(system_prompt="system", user_prompt="user")

    assert error.value.error_code == "LLM_TIMEOUT"


@pytest.mark.asyncio
async def test_configured_llm_client_rejects_unknown_provider():
    client = ConfiguredLLMClient(Settings(llm_provider="unknown-provider"))

    with pytest.raises(LLMError) as error:
        await client.complete_json(system_prompt="system", user_prompt="user")

    assert error.value.error_code == "LLM_PROVIDER_UNSUPPORTED"


@pytest.mark.asyncio
async def test_gemini_truncated_json_is_reported_as_provider_failure_before_parsing():
    operation = AsyncMock(return_value=SimpleNamespace(
        text='{"relations":[', candidates=[SimpleNamespace(finish_reason="MAX_TOKENS")],
    ))
    client = ConfiguredLLMClient(Settings(_env_file=None, llm_provider="gemini",
        gemini_api_key="test-key", llm_model="gemini-2.5-flash", gemini_thinking_budget=0))
    client._gemini_client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=operation)))
    with pytest.raises(LLMError) as error:
        await client.complete_json(system_prompt="system", user_prompt="user")
    assert error.value.error_code == "LLM_OUTPUT_TRUNCATED"
    assert operation.call_args.kwargs["config"].thinking_config.thinking_budget == 0


@pytest.mark.asyncio
async def test_provider_quota_failure_has_stable_sanitized_error_code():
    class ProviderQuotaError(Exception):
        code = 429

    client = ConfiguredLLMClient(Settings(_env_file=None, llm_provider="openai"))
    client._complete_openai = AsyncMock(side_effect=ProviderQuotaError("sensitive-provider-details"))
    with pytest.raises(LLMError) as error:
        await client.complete_json(system_prompt="system", user_prompt="user")
    assert error.value.error_code == "LLM_RATE_LIMITED"
    assert "sensitive-provider-details" not in str(error.value)
