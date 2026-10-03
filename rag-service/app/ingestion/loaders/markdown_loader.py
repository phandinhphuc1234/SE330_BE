from pathlib import Path

from app.ingestion.loaders.base import BaseLoader, RawDocument


class MarkdownLoader(BaseLoader):
    supported_extensions = {".md", ".markdown"}

    def load(self, file_path: Path) -> list[RawDocument]:
        return [RawDocument(text=file_path.read_text(encoding="utf-8"), metadata={"source": str(file_path)})]
