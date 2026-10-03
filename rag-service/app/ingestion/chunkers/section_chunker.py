import re

from app.ingestion.chunkers.base import BaseChunker, Chunk

SECTION_PATTERN = re.compile(
    r"(?P<header>(?:Chuong|Chương)\s+[IVXLCDM0-9]+|(?:Dieu|Điều)\s+\d+[a-zA-Z]?\.)",
    re.IGNORECASE,
)


class SectionChunker(BaseChunker):
    def chunk(self, text: str, metadata: dict | None = None) -> list[Chunk]:
        base_metadata = metadata or {}
        matches = list(SECTION_PATTERN.finditer(text))
        if not matches:
            return [Chunk(text=text, metadata=base_metadata)]

        chunks: list[Chunk] = []
        for index, match in enumerate(matches):
            start = match.start()
            end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
            chunk_text = text[start:end].strip()
            chunks.append(
                Chunk(
                    text=chunk_text,
                    metadata={**base_metadata, "section_header": match.group("header")},
                )
            )
        return chunks
