import asyncio
import os

from dotenv import load_dotenv
from llama_index.core.llms import ChatMessage
from llama_index.llms.google_genai import GoogleGenAI


load_dotenv()


async def main() -> None:
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("Missing GEMINI_API_KEY in .env")

    print("Connecting to Google AI Studio...")

    llm = GoogleGenAI(
        model=os.getenv("LLM_MODEL", "gemini-2.5-flash"),
        api_key=api_key,
        temperature=float(os.getenv("LLM_TEMPERATURE", "0.1")),
    )

    messages = [
        ChatMessage(role="system", content="You are a witty, experienced programmer."),
        ChatMessage(
            role="user",
            content="Hello! Please confirm the Gemini API streaming connection is working.",
        ),
    ]

    print("Gemini streaming response:\n")
    response_stream = await llm.astream_chat(messages)

    async for token in response_stream:
        print(token.delta, end="", flush=True)

    print("\n\nGemini test completed successfully.")


if __name__ == "__main__":
    asyncio.run(main())
