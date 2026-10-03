from abc import ABC, abstractmethod

from app.ingestion.chunkers.base import Chunk
from app.ingestion.chunkers.models import ChunkingContext, RawChunkingDocument

# Định nghĩa một giao diện trừu tượng cho chiến lược chunking, 
# yêu cầu các phương thức supports và chunk phải 
# được triển khai bởi các lớp con.
#  Điều này giúp đảm bảo rằng tất cả các 
# chiến lược chunking đều tuân thủ cùng một hợp đồng,
#  cho phép chúng được sử dụng thay thế cho nhau trong quá trình chunking tài liệu.
class ChunkingStrategy(ABC):
    """High-level document-aware chunking contract.

    Existing chunkers such as RecursiveChunker are text-level chunkers. This
    interface sits one layer above them so ingestion can choose chunking by
    source_type without putting if/else branches inside the ingestion service.
    """

    @abstractmethod
    def supports(self, document: RawChunkingDocument) -> bool:
        raise NotImplementedError

    @abstractmethod
    def chunk(self, document: RawChunkingDocument, context: ChunkingContext | None = None) -> list[Chunk]:
        raise NotImplementedError
