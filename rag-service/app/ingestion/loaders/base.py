from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class RawDocument:
    text: str
    metadata: dict = field(default_factory=dict)


class BaseLoader(ABC):
    supported_extensions: set[str] = set()

    @abstractmethod
    def load(self, file_path: Path) -> list[RawDocument]:
        raise NotImplementedError

    def supports(self, file_extension: str) -> bool:
        return file_extension.lower() in self.supported_extensions


class LoaderFactory:
    def __init__(self, loaders: list[BaseLoader]) -> None:
        self.loaders = loaders

    def get_loader(self, file_path: Path) -> BaseLoader:
        extension = file_path.suffix.lower()
        for loader in self.loaders:
            if loader.supports(extension):
                return loader
        raise ValueError(f"No loader registered for extension: {extension}")
