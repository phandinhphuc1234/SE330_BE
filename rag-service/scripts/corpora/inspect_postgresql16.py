"""Offline production-code PDF -> parse -> clean -> v1 chunks, without embedding or I/O services."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

RAG_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(RAG_ROOT))
FIXTURE = RAG_ROOT / "tests/fixtures/corpora/postgresql16_concurrency_v1"


def folded(text):
    return re.sub(r"[^a-z0-9]", "", text.lower())


def inspect(pdf, output):
    from app.core.config import Settings
    from app.ingestion.parsers.pdf.pymupdf4llm_parser import PyMuPDF4LLMParser
    from app.ingestion.pipeline import IngestionPipeline

    source = json.loads((FIXTURE / "source-manifest.json").read_text(encoding="utf-8"))
    actual_hash = hashlib.sha256(pdf.read_bytes()).hexdigest()
    if actual_hash != source["pdfSha256"]:
        raise ValueError("PDF differs from the source manifest. Rebuild and review labels first.")
    # Deliberately not DB identifiers. This script never creates bound EvaluationItems.
    document = SimpleNamespace(id="OFFLINE-PG16", external_document_id="OFFLINE-PG16",
                               source_type="ebook", source_id="OFFLINE-PG16", book_id=None,
                               ebook_id=None, filename=pdf.name)
    settings = Settings(_env_file=None, chunk_size=512, chunk_overlap=64, chunking_strategy_version="v1")
    pipeline = IngestionPipeline()
    with patch("app.ingestion.pipeline.get_settings", return_value=settings):
        pipeline._validate_input_file(pdf)
        pages = PyMuPDF4LLMParser().parse(pdf)
        built = pipeline._build_chunk_result(pages, document, source_checksum_sha256=actual_hash)
    chunks = [{"index": index, "sha256": hashlib.sha256(chunk.text.encode()).hexdigest(),
               "text": chunk.text, "pageStart": chunk.metadata["pageStart"],
               "pageEnd": chunk.metadata["pageEnd"],
               "chunkingVersion": chunk.metadata["chunking_strategy_version"],
               "tokenCount": chunk.metadata["token_count"]}
              for index, chunk in enumerate(built.chunks)]
    blocks = {block["id"]: block for block in source["blocks"]}
    questions = json.loads((FIXTURE / "questions.json").read_text(encoding="utf-8"))
    labels, failures, alternatives = [], [], []
    for item in questions["items"]:
        evidence = []
        for ref in item["evidence"]:
            block = blocks[ref["block"]]
            if folded(ref["quote"]) not in folded(block["text"]):
                raise ValueError(f"Quote is not in its declared source block: {item['id']}")
            matches = [chunk for chunk in chunks
                       if chunk["pageStart"] in block["pages"]
                       and folded(ref["quote"]) in folded(chunk["text"])]
            if not matches:
                failures.append({"id": item["id"], "block": ref["block"],
                                 "reason": "missing-anchor"})
            elif len(matches) > 1:
                alternatives.append({"id": item["id"], "block": ref["block"],
                                     "indices": [c["index"] for c in matches]})
            evidence.append({**ref, "blockPages": block["pages"],
                             "pages": sorted({c["pageStart"] for c in matches}),
                             "chunks": [{"index": c["index"], "sha256": c["sha256"]} for c in matches]})
        labels.append({**item, "evidence": evidence,
                       "expectedPages": sorted({page for ref in evidence for page in ref["pages"]}),
                       "expectedChunkIndices": sorted({chunk["index"] for ref in evidence for chunk in ref["chunks"]})})
    result = {"corpus": source["corpus"], "version": questions["version"],
              "questionSpecSha256": hashlib.sha256((FIXTURE / "questions.json").read_bytes()).hexdigest(),
              "sourceSha256": actual_hash,
              "mode": "offline-parse-clean-chunk-only", "embeddingCalled": False,
              "databaseWritten": False, "retrievalMeasured": False, "generationMeasured": False,
              "parser": "pymupdf4llm", "chunkingVersion": "v1", "chunkSize": 512, "chunkOverlap": 64,
              "pageCount": len(pages), "textPageCount": sum(bool(p.text.strip()) for p in pages),
              "chunkCount": len(chunks), "quality": built.chunk_quality_report.to_metadata(),
              "sourceBlockKinds": dict(Counter(b["kind"] for b in source["blocks"])),
              "splitCounts": dict(Counter(q["split"] for q in labels)),
              "anchorFailures": failures, "anchorAlternatives": alternatives,
              "relevancePolicy": "Each excerpt is an evidence group; overlapping chunks containing that excerpt are alternatives. Existing runner chunk recall uses their union, not group coverage.",
              "chunks": chunks, "labels": labels}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("pageCount", "chunkCount", "splitCounts", "anchorFailures", "quality")}))
    return int(bool(failures))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, default=RAG_ROOT.parent / "output/pdf/postgresql-16-concurrency-control-v1.pdf")
    parser.add_argument("--output", type=Path, default=FIXTURE / "ingestion-v1.json")
    args = parser.parse_args()
    raise SystemExit(inspect(args.pdf, args.output))
