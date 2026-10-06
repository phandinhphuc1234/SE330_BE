from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class Chunk:
    text: str
    metadata: dict = field(default_factory=dict)


class BaseChunker(ABC):
    @abstractmethod
    def chunk(self, text: str, metadata: dict | None = None) -> list[Chunk]:
        raise NotImplementedError
