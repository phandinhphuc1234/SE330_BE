"""Compare v1/v2 clean/chunk/citation paths without ingestion I/O.

This is a characterization baseline, not a retrieval quality benchmark.
Inputs are synthetic parsed pages; no PDF parser, DB, storage, or provider runs.
"""

from __future__ import annotations

import argparse
from contextlib import ExitStack, redirect_stdout
import difflib
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest.mock import patch

from app.core.config import Settings
from app.ingestion.chunking import LlamaSentenceChunkingStrategy
from app.ingestion.parsers.base import ParsedDocument, ParserRegistry
from app.retrieval.library_vector_retrieval import _build_citation


FIXTURE_DIR = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "chunking"
DEFAULT_CASES = FIXTURE_DIR / "baseline_cases.json"
DEFAULT_SNAPSHOT = FIXTURE_DIR / "expected_v1.json"

# Pin only the local processing configuration. Do not serialize Settings:
# normal application Settings can contain credentials from .env.
BASELINE_CONFIG = {
    "chunk_size": 512,
    "chunk_overlap": 64,
    "max_chunks_per_document": 1000,
    "pdf_cleaning_version": "pdf-clean-v1.0.0",
    "pdf_clean_normalize_unicode": True,
    "pdf_clean_collapse_spaces": True,
    "pdf_clean_max_blank_lines": 2,
    "pdf_clean_header_footer_detection_enabled": True,
    "pdf_clean_header_footer_top_lines": 3,
    "pdf_clean_header_footer_bottom_lines": 3,
    "pdf_clean_header_footer_min_repeat_ratio": 0.4,
    "pdf_clean_header_footer_removal_enabled": False,
}
SOURCE_DOCUMENT = {
    "id": 7,
    "external_document_id": "doc_ebook_55",
    "source_type": "LIBRARY_EBOOK",
    "source_id": "ebook:55",
    "book_id": 101,
    "ebook_id": 55,
    "filename": "synthetic-chunking-baseline.pdf",
}
METADATA_FIELDS = (
    "document_id", "documentId", "bookId", "ebookId", "sourceType",
    "page_number", "pageStart", "pageEnd", "document_profile",
    "chunking_strategy", "chunking_strategy_version", "chunk_level", "chunker",
    "chunk_size", "chunk_overlap", "chunk_index", "chunk_hash", "vector_id",
    "token_count", "token_counter", "cleaning_version", "cleaning_quality_status",
    "chunk_quality_status", "chapter_detected", "chapter_detected_on_page",
    "chapter_index", "chapter_number", "chapter_title", "chapter_source_page",
    "section_path",
    "section_id", "section_char_start", "section_char_end",
    "source_mapping_version", "source_spans",
)
PAGE_METADATA_FIELDS = (
    "page_number", "chapter_detected", "chapter_detected_on_page",
    "chapter_index", "chapter_title", "chapter_source_page",
)


def load_cases(path: Path = DEFAULT_CASES) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(payload, dict) or payload.get("schemaVersion") != 1
            or not isinstance(payload.get("cases"), list)):
        raise ValueError("Chunking fixtures require schemaVersion=1 and a cases list.")
    cases = payload["cases"]
    seen_ids: set[str] = set()
    if not cases:
        raise ValueError("Chunking fixtures must contain at least one case.")
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("purpose"), str):
            raise ValueError("Each fixture case requires an object with a purpose string.")
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in seen_ids:
            raise ValueError("Fixture case IDs must be non-empty and unique.")
        seen_ids.add(case_id)
        if case.get("language") not in {"vi", "en"}:
            raise ValueError(f"{case_id}: language must be vi or en.")
        pages = case.get("pages")
        if not isinstance(pages, list) or not pages:
            raise ValueError(f"{case_id}: pages must be non-empty.")
        previous_page = 0
        for page in pages:
            if not isinstance(page, dict):
                raise ValueError(f"{case_id}: each page must be an object.")
            number = page.get("pageNumber")
            if type(number) is not int or number <= previous_page:
                raise ValueError(f"{case_id}: page numbers must be positive and increasing.")
            paragraphs = page.get("paragraphs")
            if (not isinstance(paragraphs, list) or not paragraphs
                    or not all(isinstance(text, str) and text.strip() for text in paragraphs)):
                raise ValueError(f"{case_id}: paragraphs must contain non-empty strings.")
            previous_page = number
    return cases


def _select_metadata(metadata: dict, fields: tuple[str, ...]) -> dict:
    return {key: metadata[key] for key in fields if key in metadata}


def build_case_result(case: dict, *, strategy_version: str = "v1") -> dict:
    """Exercise the real synchronous production path, with unused I/O disabled."""

    from app.ingestion.pipeline import IngestionPipeline

    parsed_pages = [
        ParsedDocument(
            text="\n\n".join(page["paragraphs"]),
            metadata={"page_number": page["pageNumber"], "parser": "synthetic_fixture"},
        )
        for page in case["pages"]
    ]
    # model_construct uses local defaults, not BaseSettings' .env/environment
    # resolution. Never change the cached get_settings() or production config.
    if strategy_version not in {"v1", "v2"}:
        raise ValueError(f"Unsupported chunking strategy version: {strategy_version}")
    settings = Settings.model_construct(**BASELINE_CONFIG, chunking_strategy_version=strategy_version)
    with ExitStack() as stack:
        for module in (
            "app.ingestion.pipeline",
            "app.ingestion.cleaners.pdf_cleaner",
            "app.ingestion.llama_index.node_adapter",
        ):
            stack.enter_context(patch(f"{module}.get_settings", return_value=settings))
        # The constructor normally creates a storage adapter. It is unused by
        # _build_chunk_result, so disable it rather than instantiate an SDK client.
        stack.enter_context(patch("app.ingestion.pipeline.IngestionArtifactWriter", return_value=None))
        # Explicitly retain v1 when a v2/default selector is introduced later.
        if strategy_version == "v1":
            stack.enter_context(patch(
                "app.ingestion.pipeline.select_chunking_strategy",
                return_value=LlamaSentenceChunkingStrategy(),
            ))
        pipeline = IngestionPipeline(parser_registry=ParserRegistry([]))
        result = pipeline._build_chunk_result(
            parsed_pages, document=SimpleNamespace(**SOURCE_DOCUMENT),
        )

    report = {
        "id": case["id"],
        "language": case["language"],
        "purpose": case["purpose"],
        "knownLimitations": list(case.get("knownLimitations", [])),
        "pages": [
            {
                "input": parsed.text,
                "cleaned": cleaned.text,
                "metadata": _select_metadata(cleaned.metadata, PAGE_METADATA_FIELDS),
            }
            for parsed, cleaned in zip(parsed_pages, result.cleaned_documents, strict=True)
        ],
        "chunks": [
            {
                "text": chunk.text,
                "metadata": _select_metadata(chunk.metadata, METADATA_FIELDS),
                "citation": _build_citation(chunk.metadata),
            }
            for chunk in result.chunks
        ],
        "qualityReport": result.chunk_quality_report.to_metadata(),
    }
    if strategy_version == "v2":
        # Fixture annotations describe known v1 behavior, not measured v2 defects.
        report["v1KnownLimitations"] = report.pop("knownLimitations")
    return report


def build_report(path: Path = DEFAULT_CASES, *, strategy_version: str = "v1") -> dict:
    return {
        "schemaVersion": 1,
        "strategy": {"name": "library_pdf_narrative", "version": strategy_version},
        "config": dict(BASELINE_CONFIG),
        "cases": [build_case_result(case, strategy_version=strategy_version) for case in load_cases(path)],
    }


def serialize_report(report: dict) -> str:
    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def check_snapshot(report: dict, path: Path = DEFAULT_SNAPSHOT) -> None:
    expected = json.loads(path.read_text(encoding="utf-8"))
    if report != expected:
        diff = "".join(difflib.unified_diff(
            serialize_report(expected).splitlines(keepends=True),
            serialize_report(report).splitlines(keepends=True),
            fromfile=str(path),
            tofile="current-v1",
        ))
        raise ValueError("v1 baseline changed; review the diff, do not blindly replace it.\n" + diff[:8000])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--strategy", choices=("v1", "v2"), default="v1")
    output = parser.add_mutually_exclusive_group()
    output.add_argument("--json", action="store_true", help="Print the full deterministic report.")
    output.add_argument("--output", type=Path, help="Create a new local report; existing files are never overwritten.")
    output.add_argument("--check", action="store_true", help="Compare with the reviewed v1 snapshot.")
    args = parser.parse_args(argv)
    try:
        if args.check and args.strategy != "v1":
            raise ValueError("v2 has no reviewed snapshot yet; use --strategy v2 --json or --output.")
        # Lazy pipeline imports may log engine configuration (without connecting).
        # Keep diagnostics on stderr so --json is valid machine-readable stdout.
        with redirect_stdout(sys.stderr):
            report = build_report(args.fixtures, strategy_version=args.strategy)
        if args.check:
            check_snapshot(report)
        if args.json:
            print(serialize_report(report), end="")
            return 0
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8", newline="\n") as stream:
                stream.write(serialize_report(report))
        print(f"{args.strategy} baseline: {len(report['cases'])} cases, chunk size/overlap 512/64.")
        for case in report["cases"]:
            chapters = sorted({
                chunk["metadata"]["chapter_title"] for chunk in case["chunks"]
                if "chapter_title" in chunk["metadata"]
            })
            print(f"  {case['id']}: pages={len(case['pages'])}, chunks={len(case['chunks'])}, "
                  f"quality={case['qualityReport']['status']}, chapters={chapters}")
        if args.check:
            print("Reviewed v1 snapshot matches. Known limitations are NOT quality improvements.")
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Chunking baseline failed: {error}")
        return 1


if __name__ == "__main__":
    # Windows terminals/pipes may default to a legacy code page. Reports contain
    # original Vietnamese text, so the executable entry point always uses UTF-8.
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())

# Flow: validate synthetic pages -> pin config/strategy -> real clean/chunk path
# -> whitelist source/citation metadata -> summary/JSON/new report/v1 snapshot.
# Purpose: compare boundary/provenance behavior without provider, DB or reindex;
# --strategy v2 never changes the running application's default configuration.
