"""Small provider boundary for structured answer generation."""

from __future__ import annotations

import asyncio
from typing import Any, Protocol

from app.core.config import Settings, get_settings
from app.core.exceptions import LLMError


class LLMClient(Protocol):
    async def complete_json(self, *, system_prompt: str, user_prompt: str) -> str:
        """Return one JSON object as text."""


class ConfiguredLLMClient:
    """Call the configured provider without leaking provider SDK types upstream."""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._openai_client: Any | None = None
        self._gemini_client: Any | None = None

    async def complete_json(self, *, system_prompt: str, user_prompt: str) -> str:
        provider = self.settings.llm_provider.strip().lower()
        try:
            if provider == "openai":
                operation = self._complete_openai(system_prompt, user_prompt)
            elif provider == "gemini":
                operation = self._complete_gemini(system_prompt, user_prompt)
            else:
                raise LLMError("Unsupported LLM provider", error_code="LLM_PROVIDER_UNSUPPORTED")
            return await asyncio.wait_for(operation, timeout=self.settings.llm_timeout_seconds)
        except LLMError:
            raise
        except TimeoutError as error:
            raise LLMError("LLM request timed out", error_code="LLM_TIMEOUT") from error
        except Exception as error:  # noqa: BLE001 - provider SDK exceptions vary.
            status = getattr(error, "status_code", None) or getattr(error, "code", None)
            if status == 429:
                raise LLMError("LLM provider quota or rate limit reached", error_code="LLM_RATE_LIMITED") from error
            raise LLMError("LLM provider request failed", error_code="LLM_PROVIDER_FAILED") from error

    async def _complete_openai(self, system_prompt: str, user_prompt: str) -> str:
        if not self.settings.openai_api_key:
            raise LLMError("OpenAI API key is missing", error_code="LLM_CONFIG_MISSING")
        from openai import AsyncOpenAI

        if self._openai_client is None:
            self._openai_client = AsyncOpenAI(api_key=self.settings.openai_api_key)
        response = await self._openai_client.chat.completions.create(
            model=self.settings.llm_model,
            temperature=self.settings.llm_temperature,
            max_tokens=self.settings.llm_max_tokens,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        )
        content = response.choices[0].message.content
        if response.choices[0].finish_reason == "length":
            raise LLMError("LLM output was truncated", error_code="LLM_OUTPUT_TRUNCATED")
        if not content:
            raise LLMError("LLM returned an empty response", error_code="LLM_EMPTY_RESPONSE")
        return content

    async def _complete_gemini(self, system_prompt: str, user_prompt: str) -> str:
        if not self.settings.gemini_api_key:
            raise LLMError("Gemini API key is missing", error_code="LLM_CONFIG_MISSING")
        from google import genai
        from google.genai import types

        if self._gemini_client is None:
            self._gemini_client = genai.Client(api_key=self.settings.gemini_api_key)
        thinking = None
        if self.settings.gemini_thinking_budget is not None and self.settings.llm_model.startswith("gemini-2.5-flash"):
            thinking = types.ThinkingConfig(thinking_budget=self.settings.gemini_thinking_budget)
        response = await self._gemini_client.aio.models.generate_content(
            model=self.settings.llm_model,
            contents=user_prompt,
            config=types.GenerateContentConfig(
                system_instruction=system_prompt,
                temperature=self.settings.llm_temperature,
                max_output_tokens=self.settings.llm_max_tokens,
                response_mime_type="application/json",
                thinking_config=thinking,
            ),
        )
        content = response.text
        for candidate in response.candidates or []:
            finish_reason = getattr(candidate.finish_reason, "value", candidate.finish_reason)
            if str(finish_reason) == "MAX_TOKENS":
                raise LLMError("LLM output was truncated", error_code="LLM_OUTPUT_TRUNCATED")
        if not content:
            raise LLMError("LLM returned an empty response", error_code="LLM_EMPTY_RESPONSE")
        return content
