from collections.abc import Sequence
from typing import Any

from llama_index.core import Document
from llama_index.core.bridge.pydantic import Field
from llama_index.core.schema import BaseNode, MetadataMode, TransformComponent

from app.ingestion.cleaners.pdf_cleaner import PdfCleaner
from app.ingestion.parsers.base import ParsedDocument


class PdfCleaningTransformation(TransformComponent):
    """LlamaIndex transformation that applies the internal page-wise PDF cleaner.

    The transformation expects page-level LlamaIndex Documents created from
    parser output. It does not parse PDF files itself. Its only job is to reuse
    the project's tested PdfCleaner inside a LlamaIndex-compatible ingestion
    pipeline stage.
    """

    cleaning_version: str | None = Field(default=None)
    normalize_unicode: bool | None = Field(default=None)
    collapse_spaces: bool | None = Field(default=None)
    max_blank_lines: int | None = Field(default=None)
    remove_page_numbers_enabled: bool = Field(default=True)
    page_number_scan_lines: int = Field(default=2)
    fix_hyphenation_enabled: bool = Field(default=True)
    fix_line_breaks_enabled: bool = Field(default=True)
    header_footer_detection_enabled: bool | None = Field(default=None)
    header_footer_top_lines: int | None = Field(default=None)
    header_footer_bottom_lines: int | None = Field(default=None)
    header_footer_min_repeat_ratio: float | None = Field(default=None)
    header_footer_removal_enabled: bool | None = Field(default=None)

    @classmethod
    def class_name(cls) -> str:
        """Stable LlamaIndex serialization name for cache/hash support."""

        return "PdfCleaningTransformation"

    def __call__(self, nodes: Sequence[BaseNode], **kwargs: Any) -> Sequence[BaseNode]:
        """Clean page-level Documents and return page-level cleaned Documents."""

        parsed_pages = [
            ParsedDocument(
                text=self._get_node_text(node),
                metadata=dict(node.metadata or {}),
            )
            for node in nodes
        ]
        cleaning_result = self._build_cleaner().clean(parsed_pages)

        cleaned_documents: list[Document] = []
        for original_node, cleaned_page in zip(nodes, cleaning_result.pages, strict=False):
            metadata = {
                **dict(cleaned_page.metadata or {}),
                "page_number": cleaned_page.page_number,
                "cleaning_quality_status": cleaning_result.quality_report.quality_status,
                "cleaning_can_chunk": cleaning_result.quality_report.can_chunk,
                "cleaning_warnings": cleaned_page.warnings,
                "cleaning_quality_warnings": cleaning_result.quality_report.warnings,
                "llama_index_transformation": self.class_name(),
            }
            cleaned_documents.append(
                Document(
                    text=cleaned_page.cleaned_text,
                    metadata=metadata,
                    id_=original_node.id_,
                )
            )

        return cleaned_documents

    def _build_cleaner(self) -> PdfCleaner:
        """Create PdfCleaner from primitive transformation fields.

        Keeping the cleaner as a runtime object avoids putting non-serializable
        state into the LlamaIndex TransformComponent/Pydantic model.
        """

        return PdfCleaner(
            cleaning_version=self.cleaning_version,
            normalize_unicode=self.normalize_unicode,
            collapse_spaces=self.collapse_spaces,
            max_blank_lines=self.max_blank_lines,
            remove_page_numbers_enabled=self.remove_page_numbers_enabled,
            page_number_scan_lines=self.page_number_scan_lines,
            fix_hyphenation_enabled=self.fix_hyphenation_enabled,
            fix_line_breaks_enabled=self.fix_line_breaks_enabled,
            header_footer_detection_enabled=self.header_footer_detection_enabled,
            header_footer_top_lines=self.header_footer_top_lines,
            header_footer_bottom_lines=self.header_footer_bottom_lines,
            header_footer_min_repeat_ratio=self.header_footer_min_repeat_ratio,
            header_footer_removal_enabled=self.header_footer_removal_enabled,
        )

    @staticmethod
    def _get_node_text(node: BaseNode) -> str:
        """Read text from a LlamaIndex node without including metadata text."""

        text = getattr(node, "text", None)
        if isinstance(text, str):
            return text
        return node.get_content(metadata_mode=MetadataMode.NONE)
