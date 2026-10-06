from app.ingestion.chunkers.base import Chunk
from app.ingestion.chunking import ChunkQualityValidator, attach_chunk_quality_report


def valid_chunk(text: str = "This narrative chunk has enough context for retrieval.") -> Chunk:
    return Chunk(
        text=text,
        metadata={
            "document_id": 7,
            "documentId": "doc_ebook_55",
            "chunk_hash": "hash-1",
            "chunking_strategy": "library_pdf_narrative",
            "chunking_strategy_version": "v1",
            "chunk_level": "child",
            "pageStart": 1,
            "pageEnd": 1,
            "vector_id": "doc-7-chunk-0-hash",
            "token_count": 12,
            "token_counter": "approx_whitespace_char_v1",
        },
    )


def test_chunk_quality_validator_passes_valid_chunks() -> None:
    chunks = [
        valid_chunk("Minh bước vào thư viện khi trời vừa tối và nghe tiếng mưa ngoài phố."),
        valid_chunk("Cậu nhìn thấy một cuốn sách màu đen nằm lệch khỏi kệ gỗ cũ."),
    ]

    report = ChunkQualityValidator().validate(chunks)

    assert report.passed
    assert report.status == "PASS"
    assert report.chunk_count == 2
    assert report.min_chunk_chars > 0
    assert report.min_chunk_tokens == 12
    assert report.max_chunk_tokens == 12
    assert report.avg_chunk_tokens == 12
    assert report.to_metadata()["min_chunk_tokens"] == 12
    assert report.to_metadata()["error_count"] == 0


def test_chunk_quality_validator_reports_missing_required_metadata() -> None:
    chunk = valid_chunk()
    del chunk.metadata["chunking_strategy_version"]

    report = ChunkQualityValidator().validate([chunk])

    assert not report.passed
    assert report.status == "FAIL"
    assert report.errors[0].code == "CHUNK_METADATA_MISSING"
    assert "chunking_strategy_version" in report.errors[0].details["missing_keys"]


def test_chunk_quality_validator_reports_missing_token_count() -> None:
    chunk = valid_chunk()
    del chunk.metadata["token_count"]

    report = ChunkQualityValidator().validate([chunk])

    assert not report.passed
    assert any(issue.code == "CHUNK_METADATA_MISSING" for issue in report.errors)
    missing_issue = next(issue for issue in report.errors if issue.code == "CHUNK_METADATA_MISSING")
    assert "token_count" in missing_issue.details["missing_keys"]


def test_chunk_quality_validator_reports_invalid_token_count() -> None:
    chunk = valid_chunk()
    chunk.metadata["token_count"] = "not-a-number"

    report = ChunkQualityValidator().validate([chunk])

    assert not report.passed
    assert any(issue.code == "CHUNK_TOKEN_COUNT_INVALID" for issue in report.errors)


def test_chunk_quality_validator_warns_for_short_chunks_without_failing() -> None:
    chunk = valid_chunk("Too short.")

    report = ChunkQualityValidator(min_chunk_chars_warning=40).validate([chunk])

    assert report.passed
    assert report.warnings[0].code == "CHUNK_TOO_SHORT"


def test_chunk_quality_validator_fails_when_duplicate_ratio_is_too_high() -> None:
    chunks = [valid_chunk("Same duplicated narrative chunk text.") for _ in range(5)]
    for index, chunk in enumerate(chunks):
        chunk.metadata["chunk_hash"] = f"hash-{index}"
        chunk.metadata["vector_id"] = f"doc-7-chunk-{index}-hash"

    report = ChunkQualityValidator(max_duplicate_ratio=0.3).validate(chunks)

    assert not report.passed
    assert any(issue.code == "CHUNK_DUPLICATE_RATIO_TOO_HIGH" for issue in report.errors)


def test_attach_chunk_quality_report_adds_report_metadata_to_chunks() -> None:
    chunks = [valid_chunk()]
    report = ChunkQualityValidator().validate(chunks)

    attach_chunk_quality_report(chunks, report)

    assert chunks[0].metadata["chunk_quality_status"] == "PASS"
    assert chunks[0].metadata["chunk_quality_report"]["chunk_count"] == 1
