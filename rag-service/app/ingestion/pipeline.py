from dataclasses import dataclass
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
from time import perf_counter

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from app.core.config import get_settings
from app.core.exceptions import IngestionError, NotFoundError, ValidationError
from app.core.logger import get_logger
from app.db.session import async_session_factory
from app.documents.repository import (
    create_document_chunk,
    delete_chunks_for_document,
    get_document,
    get_document_artifact,
    update_document_metadata,
    update_document_chunk_metadata,
)
from app.documents.models import Document
from app.documents.object_storage import get_object_storage_adapter
from app.indexing.base import VectorChunk
from app.indexing.embedding_service import ChunkEmbeddingService, EmbeddedChunk
from app.indexing.providers import GeminiEmbeddingProvider
from app.indexing.qdrant_store import QdrantVectorStore, deterministic_qdrant_point_id
from app.ingestion.chunking import (
    ApproxTokenCounter,
    ChapterDetector,
    ChunkQualityReport,
    ChunkQualityValidator,
    attach_chunk_quality_report,
    attach_token_counts,
    select_chunking_strategy,
)
from app.ingestion.chunkers.base import Chunk
from app.ingestion.artifacts import IngestionArtifactWriter
from app.ingestion.llama_index import (
    LLAMAINDEX_SENTENCE_CHUNKER,
    PdfCleaningTransformation,
    llama_nodes_to_chunks,
    parsed_documents_to_llama_documents,
)
from app.ingestion.parsers.base import ParsedDocument, ParserRegistry
from app.ingestion.parsers.factory import build_default_parser_registry
from app.ingestion.profiling import DocumentProfiler
from app.ingestion.repository import get_ingestion_job, mark_job_status

logger = get_logger(__name__)

PDF_MAGIC_BYTES = b"%PDF-"
PDF_MAGIC_READ_LIMIT_BYTES = 1024
AWS_CHUNKED_FRAMING_MARKERS = (
    b";chunk-signature=",
    b"x-amz-decoded-content-length",
    b"streaming-aws4-hmac-sha256-payload",
)


@dataclass(frozen=True)
class IngestionResult:
    document_id: int
    job_id: int | None
    page_count: int
    chunk_count: int
    status: str


@dataclass(frozen=True)
class ChunkBuildResult:
    chunks: list[Chunk]
    cleaned_documents: list
    chunk_quality_report: ChunkQualityReport


@dataclass(frozen=True)
class VectorIndexingResult:
    chunk_count: int
    vector_count: int
    collection_name: str
    embedding_model: str | None
    embedding_version: str | None


class IngestionPipeline:
    def __init__(
        self,
        parser_registry: ParserRegistry | None = None,
        chunker: object | None = None,
        embedding_service: ChunkEmbeddingService | None = None,
        vector_store: QdrantVectorStore | None = None,
    ) -> None:
        self.parser_registry = parser_registry or build_default_parser_registry()
        self.document_profiler = DocumentProfiler()
        self.chapter_detector = ChapterDetector()
        self.chunk_quality_validator = ChunkQualityValidator()
        self.token_counter = ApproxTokenCounter()
        self.artifact_writer = IngestionArtifactWriter()
        self.embedding_service = embedding_service
        self.vector_store = vector_store
        # Kept for backwards-compatible construction while the pipeline moves
        # from the old text chunker to the LlamaIndex Document/Node path.
        self.legacy_chunker = chunker

    async def run(self, document_id: int, ingestion_job_id: int | None = None) -> IngestionResult:
        started_at = perf_counter()
        logger.info(
            "ingestion_pipeline_started",
            document_id=document_id,
            ingestion_job_id=ingestion_job_id,
        )
        async with async_session_factory() as session:
            job = None
            if ingestion_job_id is not None:
                job = await get_ingestion_job(session, ingestion_job_id)
                if job is None:
                    raise NotFoundError(
                        f"Ingestion job {ingestion_job_id} was not found",
                        error_code="INGESTION_JOB_NOT_FOUND",
                    )
                await mark_job_status(
                    session,
                    job,
                    "PROCESSING",
                    stage="loading_document",
                    increment_attempts=True,
                )
                await session.commit()

            try:
                temporary_directory = None
                # The worker owns the ingestion transaction boundary after the API enqueue step.
                document = await get_document(session, document_id)
                if document is None:
                    raise NotFoundError(
                        f"Document {document_id} was not found",
                        error_code="DOCUMENT_NOT_FOUND",
                    )
                logger.info(
                    "ingestion_document_loaded",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    external_document_id=document.external_document_id,
                    source_type=document.source_type,
                    source_id=document.source_id,
                    book_id=document.book_id,
                    ebook_id=document.ebook_id,
                    filename=document.filename,
                    storage_path=document.storage_path,
                    storage_backend=(document.metadata_ or {}).get("storage_backend"),
                )

                file_path, temporary_directory = await self._prepare_input_file(
                    session,
                    document,
                    source_snapshot=(job.metadata_ or {}) if job is not None else None,
                )
                logger.info(
                    "ingestion_input_file_ready",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    file_path=str(file_path),
                    file_size_bytes=file_path.stat().st_size if file_path.exists() else None,
                )
                self._validate_input_file(file_path)
                source_checksum_sha256 = self._calculate_sha256(file_path)

                if job is not None:
                    await mark_job_status(session, job, "PROCESSING", stage="parsing_pdf")
                    await session.commit()

                parser = self.parser_registry.get_parser(file_path)
                logger.info(
                    "ingestion_parse_started",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    file_extension=file_path.suffix.lower(),
                    parser=parser.name,
                )
                raw_documents = parser.parse(file_path)
                non_empty_pages = [raw for raw in raw_documents if raw.text.strip()]
                if not non_empty_pages:
                    raise IngestionError(
                        "PDF parsing produced no text. The file may be scanned or image-only.",
                        error_code="PDF_TEXT_NOT_FOUND",
                    )

                logger.info(
                    "ingestion_parse_completed",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    page_count=len(raw_documents),
                    text_page_count=len(non_empty_pages),
                )
                if job is not None:
                    await mark_job_status(
                        session,
                        job,
                        "PARSED",
                        stage="cleaning_text",
                        metadata={"page_count": len(raw_documents), "text_page_count": len(non_empty_pages)},
                    )
                    await session.commit()

                logger.info(
                    "ingestion_clean_chunk_started",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    text_page_count=len(non_empty_pages),
                    chunker=LLAMAINDEX_SENTENCE_CHUNKER,
                )
                chunk_build_result = self._build_chunk_result(
                    raw_documents, document=document, source_checksum_sha256=source_checksum_sha256,
                )
                chunks = chunk_build_result.chunks
                if not chunks:
                    raise IngestionError(
                        "PDF produced no chunks after cleaning",
                        error_code="PDF_CHUNKS_NOT_FOUND",
                    )

                logger.info(
                    "ingestion_chunking_completed",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    chunk_count=len(chunks),
                    document_profile=chunks[0].metadata.get("document_profile"),
                    chunking_strategy=chunks[0].metadata.get("chunking_strategy"),
                    chunking_strategy_version=chunks[0].metadata.get("chunking_strategy_version"),
                    chunker=chunks[0].metadata.get("chunker", LLAMAINDEX_SENTENCE_CHUNKER),
                    chunk_quality_status=chunks[0].metadata.get("chunk_quality_status"),
                )
                if job is not None:
                    await mark_job_status(
                        session,
                        job,
                        "CHUNKED",
                        stage="persisting_chunks",
                        metadata={"chunk_count": len(chunks)},
                    )
                    await session.commit()

                artifact_set = await self.artifact_writer.write_ingestion_artifacts(
                    session,
                    document=document,
                    ingestion_job_id=ingestion_job_id,
                    source_checksum_sha256=source_checksum_sha256,
                    parsed_documents=raw_documents,
                    cleaned_documents=chunk_build_result.cleaned_documents,
                    chunks=chunks,
                    chunk_quality_report=chunk_build_result.chunk_quality_report,
                )
                logger.info(
                    "ingestion_artifacts_uploaded",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    artifact_bucket=artifact_set.bucket,
                    artifact_prefix=artifact_set.prefix,
                    manifest_object_key=artifact_set.manifest_object_key,
                )

                # Rebuild chunks idempotently. A retry should replace stale partial chunks,
                # not append duplicates for the same document.
                await delete_chunks_for_document(session, document.id)
                persisted_chunks = []
                for chunk_index, chunk in enumerate(chunks):
                    persisted_chunk = await create_document_chunk(
                        session,
                        document_id=document.id,
                        chunk_index=chunk_index,
                        content=chunk.text,
                        metadata=chunk.metadata,
                        vector_id=chunk.metadata["vector_id"],
                    )
                    persisted_chunks.append(persisted_chunk)

                logger.info(
                    "ingestion_chunks_persisted",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    chunk_count=len(chunks),
                )
                metadata = {
                    **(document.metadata_ or {}),
                    "ingestion_status": "CHUNKED",
                    "ingestion_stage": "chunks_persisted",
                    "source_type": "pdf",
                    "page_count": len(raw_documents),
                    "text_page_count": len(non_empty_pages),
                    "chunk_count": len(chunks),
                    "document_profile": chunks[0].metadata.get("document_profile"),
                    "document_profile_version": chunks[0].metadata.get("document_profile_version"),
                    "document_profile_confidence": chunks[0].metadata.get("document_profile_confidence"),
                    "document_profile_signals": chunks[0].metadata.get("document_profile_signals"),
                    "chunking_strategy": chunks[0].metadata.get("chunking_strategy"),
                    "chunking_strategy_version": chunks[0].metadata.get("chunking_strategy_version"),
                    "chunker": chunks[0].metadata.get("chunker", LLAMAINDEX_SENTENCE_CHUNKER),
                    "chunk_quality_status": chunks[0].metadata.get("chunk_quality_status"),
                    "chunk_quality_report": chunks[0].metadata.get("chunk_quality_report"),
                    "source_checksum_sha256": source_checksum_sha256,
                    **artifact_set.to_metadata(),
                    "vector_indexing_status": "pending_embedding",
                }
                await update_document_metadata(session, document, metadata)

                if job is not None:
                    await mark_job_status(
                        session,
                        job,
                        "CHUNKED",
                        stage="chunks_persisted",
                        metadata={
                            "page_count": len(raw_documents),
                            "text_page_count": len(non_empty_pages),
                            "chunk_count": len(chunks),
                            "document_profile": chunks[0].metadata.get("document_profile"),
                            "chunking_strategy": chunks[0].metadata.get("chunking_strategy"),
                            "chunking_strategy_version": chunks[0].metadata.get("chunking_strategy_version"),
                            "chunk_quality_status": chunks[0].metadata.get("chunk_quality_status"),
                            "chunk_quality_report": chunks[0].metadata.get("chunk_quality_report"),
                            **artifact_set.to_metadata(),
                            "vector_indexing_status": "pending_embedding",
                        },
                    )

                await session.commit()

                if job is not None:
                    await mark_job_status(
                        session,
                        job,
                        "EMBEDDING",
                        stage="embedding_chunks",
                        metadata={"vector_indexing_status": "embedding"},
                    )
                    await session.commit()

                logger.info(
                    "ingestion_embedding_started",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    chunk_count=len(chunks),
                    embedding_provider=get_settings().embedding_provider,
                    embedding_model=get_settings().embedding_model,
                    embedding_dim=get_settings().embedding_dim,
                    embedding_version=get_settings().embedding_version,
                )
                embedded_chunks = await self._embed_chunks(chunks)
                logger.info(
                    "ingestion_embedding_completed",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    embedded_chunk_count=len(embedded_chunks),
                    embedding_model=embedded_chunks[0].chunk.metadata.get("embedding_model") if embedded_chunks else None,
                    embedding_version=embedded_chunks[0].chunk.metadata.get("embedding_version") if embedded_chunks else None,
                )

                if job is not None:
                    await mark_job_status(
                        session,
                        job,
                        "EMBEDDED",
                        stage="embedding_completed",
                        metadata={
                            "vector_indexing_status": "pending_qdrant_upsert",
                            "embedded_chunk_count": len(embedded_chunks),
                        },
                    )
                    await session.commit()

                    await mark_job_status(
                        session,
                        job,
                        "INDEXING",
                        stage="upserting_qdrant",
                        metadata={"vector_indexing_status": "upserting_qdrant"},
                    )
                    await session.commit()

                logger.info(
                    "ingestion_qdrant_upsert_started",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    vector_count=len(embedded_chunks),
                    qdrant_collection=get_settings().qdrant_collection_name,
                )
                indexing_result = await self._upsert_embedded_chunks(embedded_chunks)
                logger.info(
                    "ingestion_qdrant_upsert_completed",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    vector_count=indexing_result.vector_count,
                    qdrant_collection=indexing_result.collection_name,
                    embedding_model=indexing_result.embedding_model,
                    embedding_version=indexing_result.embedding_version,
                )

                for persisted_chunk, chunk in zip(persisted_chunks, chunks, strict=True):
                    await update_document_chunk_metadata(
                        session,
                        persisted_chunk,
                        metadata=chunk.metadata,
                        vector_id=chunk.metadata.get("vector_id"),
                    )

                final_metadata = {
                    **(document.metadata_ or {}),
                    "ingestion_status": "INDEXED",
                    "ingestion_stage": "indexed",
                    "vector_indexing_status": "indexed",
                    "indexed_chunk_count": indexing_result.chunk_count,
                    "indexed_vector_count": indexing_result.vector_count,
                    "qdrant_collection": indexing_result.collection_name,
                    "embedding_model": indexing_result.embedding_model,
                    "embedding_version": indexing_result.embedding_version,
                }
                await update_document_metadata(session, document, final_metadata)

                if job is not None:
                    await mark_job_status(
                        session,
                        job,
                        "INDEXED",
                        stage="indexed",
                        metadata={
                            "vector_indexing_status": "indexed",
                            "indexed_chunk_count": indexing_result.chunk_count,
                            "indexed_vector_count": indexing_result.vector_count,
                            "qdrant_collection": indexing_result.collection_name,
                            "embedding_model": indexing_result.embedding_model,
                            "embedding_version": indexing_result.embedding_version,
                        },
                        completed=True,
                    )

                await session.commit()
                logger.info(
                    "ingestion_pipeline_completed",
                    document_id=document.id,
                    ingestion_job_id=ingestion_job_id,
                    page_count=len(raw_documents),
                    chunk_count=len(chunks),
                    status="INDEXED",
                    qdrant_collection=indexing_result.collection_name,
                    duration_ms=round((perf_counter() - started_at) * 1000, 2),
                )
                return IngestionResult(
                    document_id=document.id,
                    job_id=job.id if job is not None else None,
                    page_count=len(raw_documents),
                    chunk_count=len(chunks),
                    status="INDEXED",
                )

            except Exception as exc:
                await session.rollback()
                logger.error(
                    "ingestion_pipeline_failed",
                    document_id=document_id,
                    ingestion_job_id=ingestion_job_id,
                    duration_ms=round((perf_counter() - started_at) * 1000, 2),
                    error=str(exc),
                    exc_info=True,
                )
                if job is not None:
                    failed_job = await get_ingestion_job(session, job.id)
                    if failed_job is None:
                        raise
                    # Persist the final failure on the job row so the API can expose it.
                    await mark_job_status(
                        session,
                        failed_job,
                        "FAILED",
                        stage="failed",
                        error_message=str(exc),
                        completed=True,
                    )
                    await session.commit()
                raise
            finally:
                if temporary_directory is not None:
                    logger.info(
                        "ingestion_temp_directory_cleanup",
                        document_id=document_id,
                        ingestion_job_id=ingestion_job_id,
                        temp_dir=temporary_directory.name,
                    )
                    temporary_directory.cleanup()

    async def _prepare_input_file(
        self,
        session,
        document: Document,
        source_snapshot: dict | None = None,
    ) -> tuple[Path, TemporaryDirectory | None]:
        """Return a local parser file using the immutable source snapshot of this job.

        ``RAW_ORIGINAL`` remains the latest source pointer for the document. A
        queued/retrying job must prefer its own metadata snapshot so another
        ingestion request cannot silently switch that running job to a newer
        checksum at the same object key.
        """

        metadata = document.metadata_ or {}
        storage_backend = str(metadata.get("storage_backend") or get_settings().storage_backend).lower()
        if storage_backend == "local":
            # Legacy/dev fallback: the stored path is already a local filesystem path.
            logger.info(
                "ingestion_local_source_selected",
                document_id=document.id,
                storage_path=document.storage_path,
            )
            return Path(document.storage_path), None

        # Production path: object storage is durable, parser input is a temporary file.
        artifact = await get_document_artifact(session, document.id, "RAW_ORIGINAL")
        snapshot = source_snapshot or {}
        object_key = (
            snapshot.get("object_key")
            or (artifact.object_key if artifact is not None else None)
            or metadata.get("raw_object_key")
        )
        if not object_key:
            raise IngestionError(
                f"Raw object key was not found for document {document.id}",
                error_code="RAW_OBJECT_KEY_NOT_FOUND",
            )

        storage = get_object_storage_adapter()
        source_bucket = snapshot.get("bucket") or (artifact.bucket if artifact is not None else None)
        expected_size = (
            snapshot.get("file_size_bytes")
            if snapshot.get("file_size_bytes") is not None
            else (artifact.size_bytes if artifact is not None else None)
        )
        expected_checksum = (
            snapshot.get("checksum_sha256")
            or (artifact.checksum_sha256 if artifact is not None else None)
        )
        logger.info(
            "ingestion_source_head_started",
            document_id=document.id,
            bucket=source_bucket,
            object_key=object_key,
            source_metadata_origin="job_snapshot" if source_snapshot else "raw_artifact",
        )
        source_metadata = await storage.head_object(
            str(object_key),
            bucket=source_bucket,
        )
        if expected_size is not None and source_metadata.content_length != expected_size:
            raise IngestionError(
                (
                    "Source object size does not match registered job metadata: "
                    f"expected={expected_size}, actual={source_metadata.content_length}"
                ),
                error_code="SOURCE_SIZE_MISMATCH",
            )

        logger.info(
            "ingestion_source_object_validated",
            document_id=document.id,
            bucket=source_metadata.bucket,
            object_key=source_metadata.object_key,
            content_length=source_metadata.content_length,
            content_type=source_metadata.content_type,
        )
        suffix = Path(document.filename).suffix.lower()
        # The worker downloads the source object into a task-scoped temp folder.
        # This keeps the durable source in SeaweedFS, but gives checksum/PDF
        # validators and parsers a local seekable file that can be read multiple
        # times without repeatedly streaming from S3.
        temporary_directory = TemporaryDirectory(prefix=f"rag-document-{document.id}-")
        destination_path = Path(temporary_directory.name) / f"original{suffix}"
        try:
            download_started_at = perf_counter()
            logger.info(
                "ingestion_source_download_started",
                document_id=document.id,
                bucket=source_metadata.bucket,
                object_key=source_metadata.object_key,
                destination_path=str(destination_path),
            )
            await storage.download_to_path(
                str(object_key),
                destination_path,
                bucket=source_bucket,
            )
            logger.info(
                "ingestion_source_download_completed",
                document_id=document.id,
                bucket=source_metadata.bucket,
                object_key=source_metadata.object_key,
                destination_path=str(destination_path),
                file_size_bytes=destination_path.stat().st_size if destination_path.exists() else None,
                duration_ms=round((perf_counter() - download_started_at) * 1000, 2),
            )
            if expected_checksum:
                self._validate_source_checksum(
                    destination_path,
                    expected_checksum=expected_checksum,
                )
        except Exception:
            # If download fails, _prepare_input_file never returns the temp
            # handle to run(). Checksum failures behave the same way: the temp
            # file is not useful anymore, so cleanup must happen here instead
            # of relying on the caller's finally block.
            temporary_directory.cleanup()
            raise

        # After a successful return, run() owns cleanup in its finally block.
        return destination_path, temporary_directory

    def _validate_source_checksum(self, file_path: Path, *, expected_checksum: str) -> None:
        """Verify the downloaded temp file still matches Library metadata."""

        actual_checksum = self._calculate_sha256(file_path)
        if actual_checksum != expected_checksum.lower():
            raise IngestionError(
                (
                    "Downloaded PDF checksum does not match registered artifact metadata: "
                    f"expected={expected_checksum.lower()}, actual={actual_checksum}"
                ),
                error_code="PDF_CHECKSUM_MISMATCH",
            )

        logger.info(
            "ingestion_source_checksum_validated",
            file_path=str(file_path),
            checksum_sha256=actual_checksum,
        )

    @staticmethod
    def _calculate_sha256(file_path: Path) -> str:
        """Calculate SHA-256 without loading the whole PDF into memory."""

        digest = hashlib.sha256()
        with file_path.open("rb") as input_file:
            for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def _validate_input_file(self, file_path: Path) -> None:
        settings = get_settings()
        if not file_path.exists():
            raise NotFoundError(f"Stored file was not found: {file_path}", error_code="STORED_FILE_NOT_FOUND")
        if file_path.suffix.lower() not in settings.ingestion_allowed_extensions:
            raise ValidationError(
                f"Unsupported file extension for ingestion: {file_path.suffix}",
                error_code="UNSUPPORTED_FILE_TYPE",
            )
        if file_path.suffix.lower() == ".pdf":
            self._validate_pdf_magic_bytes(file_path)
            self._validate_pdf_structure(file_path, max_pages=settings.max_pdf_pages)
            self._validate_pdf_text_layer(
                file_path,
                sample_pages=settings.pdf_text_sample_pages,
                min_average_chars=settings.pdf_min_avg_text_chars,
            )

    def _validate_pdf_magic_bytes(self, file_path: Path) -> None:
        """Reject fake PDFs before the expensive parser/chunker stages run.

        This is intentionally a lightweight first check. We only inspect the
        first bytes of the local temp file and look for the PDF header marker.
        A deeper structural check with pypdf runs immediately after this gate.
        """

        try:
            with file_path.open("rb") as input_file:
                header = input_file.read(PDF_MAGIC_READ_LIMIT_BYTES)
        except OSError as exc:
            raise IngestionError(
                f"Could not read PDF header from stored file: {file_path}",
                error_code="PDF_HEADER_READ_FAILED",
            ) from exc

        pdf_header_offset = header.find(PDF_MAGIC_BYTES)
        if pdf_header_offset < 0:
            raise IngestionError(
                "File extension is .pdf but file content does not contain a PDF header marker.",
                error_code="PDF_MAGIC_BYTES_INVALID",
            )

        # Some S3-compatible servers can persist AWS SigV4 streaming chunk
        # framing as part of the object when the uploader and server disagree
        # about aws-chunked encoding. Such objects may still contain `%PDF-`
        # later in the first KiB, but their byte stream and checksum are no
        # longer the original PDF. Reject this explicitly before pypdf/MuPDF.
        header_prefix = header[:pdf_header_offset].lower()
        if any(marker in header_prefix for marker in AWS_CHUNKED_FRAMING_MARKERS):
            raise IngestionError(
                "PDF contains AWS chunked-transfer framing before the PDF header. "
                "Re-upload the original file with S3 chunked encoding disabled.",
                error_code="PDF_AWS_CHUNKED_FRAMING_DETECTED",
            )

        logger.info(
            "ingestion_pdf_magic_bytes_validated",
            file_path=str(file_path),
            inspected_bytes=len(header),
            pdf_header_offset=pdf_header_offset,
        )

    def _validate_pdf_structure(self, file_path: Path, *, max_pages: int) -> None:
        """Open the PDF with pypdf and reject unsafe/unsupported structures.

        Magic bytes only tell us the file looks like a PDF. This second gate
        checks whether a real PDF reader can parse the cross-reference/table
        structure, whether the file is encrypted, and whether the page count is
        inside the worker's configured safety bounds.
        """

        try:
            with file_path.open("rb") as input_file:
                reader = PdfReader(input_file, strict=False)

                if reader.is_encrypted:
                    raise IngestionError(
                        "PDF is encrypted or password protected.",
                        error_code="PDF_ENCRYPTED",
                    )

                page_count = len(reader.pages)
        except IngestionError:
            raise
        except (PdfReadError, OSError, ValueError, TypeError, KeyError, IndexError) as exc:
            raise IngestionError(
                "PDF structure could not be parsed.",
                error_code="PDF_STRUCTURE_INVALID",
            ) from exc

        if page_count <= 0:
            raise IngestionError(
                "PDF has no pages.",
                error_code="PDF_PAGE_COUNT_INVALID",
            )
        if page_count > max_pages:
            raise IngestionError(
                f"PDF page count {page_count} exceeds configured max_pdf_pages={max_pages}.",
                error_code="PDF_PAGE_LIMIT_EXCEEDED",
            )

        logger.info(
            "ingestion_pdf_structure_validated",
            file_path=str(file_path),
            page_count=page_count,
            max_pages=max_pages,
        )

    def _validate_pdf_text_layer(
        self,
        file_path: Path,
        *,
        sample_pages: int,
        min_average_chars: int,
    ) -> None:
        """Detect image-only/scanned PDFs before sending them to the normal parser.

        The normal RAG path expects a real text layer. If pypdf can open the
        file but extracts almost no text from the sampled pages, the PDF likely
        needs an OCR pipeline instead of the standard parse/clean/chunk flow.
        """

        try:
            with file_path.open("rb") as input_file:
                reader = PdfReader(input_file, strict=False)
                if reader.is_encrypted:
                    raise IngestionError(
                        "PDF is encrypted or password protected.",
                        error_code="PDF_ENCRYPTED",
                    )

                page_count = len(reader.pages)
                if page_count <= 0:
                    raise IngestionError(
                        "PDF has no pages.",
                        error_code="PDF_PAGE_COUNT_INVALID",
                    )

                effective_sample_pages = min(max(sample_pages, 1), page_count)
                sampled_char_counts = []
                for page_index in range(effective_sample_pages):
                    text = reader.pages[page_index].extract_text() or ""
                    sampled_char_counts.append(len(text.strip()))
        except IngestionError:
            raise
        except (PdfReadError, OSError, ValueError, TypeError, KeyError, IndexError) as exc:
            raise IngestionError(
                "PDF text layer could not be inspected.",
                error_code="PDF_TEXT_LAYER_READ_FAILED",
            ) from exc

        total_chars = sum(sampled_char_counts)
        average_chars = total_chars / len(sampled_char_counts)
        if average_chars < min_average_chars:
            raise IngestionError(
                (
                    "PDF appears to have little or no extractable text layer. "
                    "OCR is required before standard RAG ingestion."
                ),
                error_code="PDF_OCR_REQUIRED",
            )

        logger.info(
            "ingestion_pdf_text_layer_validated",
            file_path=str(file_path),
            sampled_pages=len(sampled_char_counts),
            total_extracted_chars=total_chars,
            average_extracted_chars=round(average_chars, 2),
            min_average_chars=min_average_chars,
        )

    def _build_chunks(self, raw_documents: list[ParsedDocument], document) -> list[Chunk]:
        return self._build_chunk_result(raw_documents, document=document).chunks

    def _build_chunk_result(
        self, raw_documents: list[ParsedDocument], document,
        *, source_checksum_sha256: str | None = None,
    ) -> ChunkBuildResult:
        settings = get_settings()
        base_metadata = self._document_base_metadata(document)
        # Runtime ingestion already computes the SHA-256 of the validated PDF.
        # Carry it into every chunk/payload so expansion can bind the source
        # revision, not just the embedding model or reused document identifier.
        if source_checksum_sha256 is not None:
            base_metadata["source_checksum_sha256"] = source_checksum_sha256
        page_documents = parsed_documents_to_llama_documents(
            raw_documents,
            base_metadata=base_metadata,
        )
        cleaned_documents = list(PdfCleaningTransformation()(page_documents))
        if cleaned_documents and cleaned_documents[0].metadata.get("cleaning_can_chunk") is False:
            raise IngestionError(
                "Cleaned PDF quality did not pass the chunking gate.",
                error_code="PDF_CLEANING_QUALITY_FAILED",
            )

        document_profile = self.document_profiler.profile(cleaned_documents)
        profiled_documents = self._attach_metadata_to_llama_documents(
            cleaned_documents,
            document_profile.to_metadata(),
        )
        chunking_strategy = select_chunking_strategy(
            document_profile.name,
            strategy_version=getattr(settings, "chunking_strategy_version", "v1"),
        )
        # v1 attaches one chapter label to each page. v2 must see unlabelled
        # cleaned pages and build actual boundaries itself; its artifacts keep
        # the original page shape while chunk metadata owns chapter provenance.
        chaptered_documents = (
            self.chapter_detector.attach_metadata(profiled_documents)
            if chunking_strategy.version == "v1" else profiled_documents
        )
        nodes = chunking_strategy.build_nodes(
            chaptered_documents,
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
        )
        chunks = llama_nodes_to_chunks(nodes)
        attach_token_counts(chunks, self.token_counter)
        if len(chunks) > settings.max_chunks_per_document:
            raise IngestionError(
                f"Document exceeded max_chunks_per_document={settings.max_chunks_per_document}",
                error_code="MAX_CHUNKS_EXCEEDED",
            )
        chunk_quality_report = self.chunk_quality_validator.validate(chunks)
        if not chunk_quality_report.passed:
            first_error = chunk_quality_report.errors[0].to_metadata() if chunk_quality_report.errors else {}
            raise IngestionError(
                f"Chunk quality validation failed: {first_error}",
                error_code="CHUNK_QUALITY_FAILED",
            )
        attach_chunk_quality_report(chunks, chunk_quality_report)
        return ChunkBuildResult(
            chunks=chunks,
            cleaned_documents=chaptered_documents,
            chunk_quality_report=chunk_quality_report,
        )

    async def _index_chunks(self, chunks: list[Chunk]) -> VectorIndexingResult:
        """Embed chunks and upsert vectors into Qdrant.

        This helper is intentionally separate from parse/clean/chunk. It lets
        the worker persist chunks first, then retry vector indexing with stable
        vector IDs if Gemini or Qdrant fails.
        """

        embedded_chunks = await self._embed_chunks(chunks)
        return await self._upsert_embedded_chunks(embedded_chunks)

    async def _embed_chunks(self, chunks: list[Chunk]) -> list[EmbeddedChunk]:
        """Turn clean chunks into embedding vectors using the configured provider."""

        service = self.embedding_service
        if service is None:
            service = ChunkEmbeddingService(provider=GeminiEmbeddingProvider())
            self.embedding_service = service
        started_at = perf_counter()
        try:
            return await service.embed_chunks(chunks)
        finally:
            logger.info(
                "ingestion_embed_chunks_finished",
                chunk_count=len(chunks),
                duration_ms=round((perf_counter() - started_at) * 1000, 2),
            )

    async def _upsert_embedded_chunks(self, embedded_chunks: list[EmbeddedChunk]) -> VectorIndexingResult:
        """Write embedded chunks to Qdrant and attach Qdrant metadata to chunks."""

        if not embedded_chunks:
            return VectorIndexingResult(
                chunk_count=0,
                vector_count=0,
                collection_name=get_settings().qdrant_collection_name,
                embedding_model=None,
                embedding_version=None,
            )

        vector_store = self.vector_store
        if vector_store is None:
            vector_store = QdrantVectorStore()
            self.vector_store = vector_store

        vector_chunks = [self._embedded_chunk_to_vector_chunk(embedded) for embedded in embedded_chunks]
        upsert_started_at = perf_counter()
        await vector_store.upsert(vector_chunks)
        logger.info(
            "ingestion_vector_store_upsert_finished",
            collection_name=str(getattr(vector_store, "collection_name", None) or get_settings().qdrant_collection_name),
            vector_count=len(vector_chunks),
            duration_ms=round((perf_counter() - upsert_started_at) * 1000, 2),
        )

        collection_name = str(getattr(vector_store, "collection_name", None) or get_settings().qdrant_collection_name)
        for embedded, vector_chunk in zip(embedded_chunks, vector_chunks, strict=True):
            metadata = embedded.chunk.metadata or {}
            embedding_version = str(metadata.get("embedding_version") or get_settings().embedding_version)
            qdrant_point_key = f"{vector_chunk.id}:{embedding_version}"
            metadata.update(
                {
                    "vector_indexing_status": "indexed",
                    "qdrant_collection": collection_name,
                    "qdrant_point_key": qdrant_point_key,
                    "qdrant_point_id": deterministic_qdrant_point_id(qdrant_point_key),
                }
            )
            embedded.chunk.metadata = metadata

        first_metadata = embedded_chunks[0].chunk.metadata or {}
        return VectorIndexingResult(
            chunk_count=len(embedded_chunks),
            vector_count=len(vector_chunks),
            collection_name=collection_name,
            embedding_model=first_metadata.get("embedding_model"),
            embedding_version=first_metadata.get("embedding_version"),
        )

    @staticmethod
    def _embedded_chunk_to_vector_chunk(embedded: EmbeddedChunk) -> VectorChunk:
        metadata = embedded.chunk.metadata or {}
        vector_id = embedded.vector_id or metadata.get("vector_id")
        if not vector_id:
            raise IngestionError(
                "Embedded chunk is missing vector_id, cannot upsert into Qdrant.",
                error_code="VECTOR_ID_MISSING",
            )
        return VectorChunk(
            id=str(vector_id),
            text=embedded.chunk.text,
            vector=embedded.vector,
            metadata=metadata,
        )

    @staticmethod
    def _attach_metadata_to_llama_documents(documents, metadata: dict):
        """Copy shared metadata onto LlamaIndex documents without mutating inputs."""

        from llama_index.core import Document as LlamaDocument

        return [
            LlamaDocument(
                text=document.text or "",
                metadata={**dict(document.metadata or {}), **metadata},
                id_=document.id_,
            )
            for document in documents
        ]

    @staticmethod
    def _document_base_metadata(document) -> dict:
        """Build metadata that must flow from a RAG document down to every chunk."""

        return {
            "document_id": document.id,
            "documentId": document.external_document_id,
            "external_document_id": document.external_document_id,
            "sourceType": document.source_type,
            "source_id": document.source_id,
            # Keep old chunk metadata compatibility: sourceType is the business
            # source, while source_type described the content kind as PDF.
            "source_type": "pdf",
            "content_type": "pdf",
            "book_id": document.book_id,
            "bookId": document.book_id,
            "ebook_id": document.ebook_id,
            "ebookId": document.ebook_id,
            "filename": document.filename,
        }
