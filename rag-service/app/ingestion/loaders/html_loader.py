from pathlib import Path

from bs4 import BeautifulSoup

from app.ingestion.loaders.base import BaseLoader, RawDocument


class HtmlLoader(BaseLoader):
    supported_extensions = {".html", ".htm"}

    def load(self, file_path: Path) -> list[RawDocument]:
        soup = BeautifulSoup(file_path.read_text(encoding="utf-8"), "lxml")
        return [RawDocument(text=soup.get_text("\n"), metadata={"source": str(file_path)})]
