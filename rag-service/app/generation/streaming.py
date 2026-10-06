import json
from collections.abc import AsyncIterator


async def sse_stream(tokens: AsyncIterator[str]) -> AsyncIterator[str]:
    async for token in tokens:
        yield f"data: {json.dumps({'token': token})}\n\n"
    yield "data: [DONE]\n\n"
