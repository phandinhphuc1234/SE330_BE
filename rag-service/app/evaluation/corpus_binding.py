"""Bind a source-labelled corpus to indexed DB/vector identifiers, fail-closed.

No providers, databases, uploads or retrieval calls. Unlike the historical Magi
verifier, this uses a corpus-specific reviewed offline manifest supplied by the
operator. This is integrity checking, not an attestation of label correctness.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Literal

from app.evaluation.dataset import EvaluationDataset, EvaluationItem


def _positive_id(value):
    if type(value) is not int or value <= 0:
        raise ValueError("Indexed corpus needs positive Library/DB identifiers.")
    return value


def _digest(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        raise ValueError("Invalid source or chunk SHA-256.")
    return value


def bind_corpus(reference: dict, document: dict, *, split: Literal["dev", "heldout"]):
    if split not in ("dev", "heldout"):
        raise ValueError("Choose dev or heldout explicitly.")
    if reference.get("mode") != "offline-parse-clean-chunk-only" or reference.get("anchorFailures") != []:
        raise ValueError("Reference must have a passing offline anchor check.")
    if document.get("status") != "INDEXED" or document.get("sourceSha256") != _digest(reference.get("sourceSha256")):
        raise ValueError("PDF checksum or indexed status mismatch.")
    scope = {key: _positive_id(document.get(key)) for key in ("bookId", "ebookId", "documentId")}
    expected = reference["chunks"]
    actual = sorted(document["chunks"], key=lambda c: c["index"])
    count = len(expected)
    if not count or reference.get("chunkCount") != count or len(actual) != count:
        raise ValueError("Chunk count mismatch.")
    identifiers = {}
    for index, (ref, chunk) in enumerate(zip(expected, actual, strict=True)):
        if ref.get("index") != index or chunk.get("index") != index:
            raise ValueError("Chunks must have contiguous unique indices.")
        if chunk.get("sha256") != _digest(ref.get("sha256")):
            raise ValueError("Ordered chunk content mismatch.")
        if ref.get("chunkingVersion") != reference.get("chunkingVersion") or chunk.get("chunkingVersion") != reference.get("chunkingVersion"):
            raise ValueError("Chunking version mismatch.")
        vector_id = chunk.get("vectorId")
        if not isinstance(vector_id, str) or not vector_id.strip() or vector_id in identifiers.values():
            raise ValueError("Vector IDs must be nonempty and unique.")
        identifiers[index] = vector_id
    items, groups = [], {}
    label_ids = [label["id"] for label in reference["labels"]]
    if len(set(label_ids)) != len(label_ids):
        raise ValueError("Reference question IDs must be unique.")
    for label in reference["labels"]:
        if label["split"] != split:
            continue
        evidence_groups = []
        indices = set()
        for evidence in label["evidence"]:
            alternatives = []
            for chunk in evidence["chunks"]:
                index = chunk["index"]
                if type(index) is not int or index not in identifiers or chunk["sha256"] != expected[index]["sha256"]:
                    raise ValueError("Evidence refers to a different chunk revision.")
                alternatives.append(identifiers[index])
                indices.add(index)
            if not alternatives:
                raise ValueError("Answerable evidence group has no matching chunks.")
            evidence_groups.append(sorted(set(alternatives)))
        if sorted(indices) != label["expectedChunkIndices"]:
            raise ValueError("Evidence and expected chunk indices disagree.")
        pages = label["expectedPages"]
        if any(type(p) is not int or not 1 <= p <= reference["pageCount"] for p in pages):
            raise ValueError("Evidence page is outside this PDF.")
        answerable = label["kind"] != "unanswerable"
        if not answerable and (evidence_groups or pages):
            raise ValueError("Unanswerable question must not have evidence.")
        items.append(EvaluationItem(
            id=label["id"], question=label["question"], **scope, answerable=answerable,
            expectedChunkIds=[identifiers[i] for i in sorted(indices)],
            expectedPages=pages, expectedAnswerPoints=label["points"],
            tags=[label["kind"], split, reference["corpus"]],
        ))
        groups[label["id"]] = evidence_groups
    dataset = EvaluationDataset(name=f"{reference['corpus']}-{split}", version=reference["version"],
                                description="Checksum-bound source-labelled technical corpus. Chunk recall counts the union of relevant chunks; see sidecar for alternative evidence groups.", items=items)
    sidecar = {"corpus": reference["corpus"], "split": split, "sourceSha256": reference["sourceSha256"],
               "questionSpecSha256": reference["questionSpecSha256"], "scope": scope,
               "evidenceGroups": groups, "status": "bound-not-evaluated"}
    return dataset, sidecar


def evidence_group_coverage(groups: list[list[str]], retrieved_ids: list[str]) -> float | None:
    """Coverage of required excerpts; either overlapping chunk can satisfy one group.

    Empty/unanswerable groups are excluded, never assigned a perfect score.
    This helper is NOT silently substituted for the existing chunk-recall metric.
    """
    if not groups:
        return None
    if any(not group for group in groups):
        raise ValueError("Evidence alternatives must not be empty.")
    retrieved = set(retrieved_ids)
    return sum(bool(retrieved.intersection(group)) for group in groups) / len(groups)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--document-id", type=int, required=True)
    parser.add_argument("--split", choices=["dev", "heldout"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    payload = json.loads(args.inventory.read_text(encoding="utf-8"))
    documents = payload["documents"] if isinstance(payload, dict) else payload
    matches = [row for row in documents if row.get("documentId") == args.document_id]
    if len(matches) != 1:
        raise ValueError("Inventory must contain exactly one selected document.")
    reference = json.loads(args.reference.read_text(encoding="utf-8"))
    dataset, sidecar = bind_corpus(reference, matches[0], split=args.split)
    sidecar_path = args.output.with_suffix(".evidence-groups.json")
    # Refuse accidental replacement of a previous dataset or measurement binding.
    if args.output.exists() or sidecar_path.exists():
        raise ValueError("Choose fresh output paths; existing evidence is preserved.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(dataset.model_dump_json(by_alias=True, indent=2) + "\n", encoding="utf-8")
    sidecar_path.write_text(json.dumps(sidecar, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"split": args.split, "cases": len(dataset.items), "status": "bound-not-evaluated"}))


if __name__ == "__main__":
    main()
