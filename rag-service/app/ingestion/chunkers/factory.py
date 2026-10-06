from app.core.exceptions import ValidationError
from app.ingestion.chunkers.models import RawChunkingDocument
from app.ingestion.chunkers.strategy import ChunkingStrategy

# Chọn ra chiến lược chunking phù hợp dựa trên loại tài liệu hoặc các đặc điểm khác của tài liệu. Điều này cho phép linh hoạt trong việc xử lý các loại tài liệu khác nhau mà không cần phải thay đổi logic chunking bên trong dịch vụ ingestion.
class ChunkingStrategyFactory:
    """Select the first document-aware chunking strategy that supports a file."""

    def __init__(self, strategies: list[ChunkingStrategy]) -> None:
        self.strategies = strategies
    # Lấy chiến lược chunking phù hợp cho một tài liệu cụ thể. Nếu không tìm thấy chiến lược nào hỗ trợ tài liệu đó, ném ra lỗi ValidationError với mã lỗi "CHUNKING_STRATEGY_NOT_FOUND".
    def get_strategy(self, document: RawChunkingDocument) -> ChunkingStrategy:
        for strategy in self.strategies:
            if strategy.supports(document):
                return strategy

        raise ValidationError(
            message=f"No chunking strategy found for source_type={document.source_type}",
            error_code="CHUNKING_STRATEGY_NOT_FOUND",
        )
