from copy import deepcopy

import pytest

from app.evaluation.dataset import load_evaluation_dataset
from app.evaluation.production_verification import CHUNK_SHA256, SOURCE_SHA256, bind_reference, latency_summary
from app.evaluation.run_eval import DEFAULT_DATASET


def fixture_document():
    return {"documentId": 99, "bookId": 12, "ebookId": 34, "sourceSha256": SOURCE_SHA256,
            "status": "INDEXED", "chunks": [
                {"index": i, "vectorId": f"remote-{i}", "sha256": digest, "chunkingVersion": "v1"}
                for i, digest in enumerate(CHUNK_SHA256)]}


def test_binding_changes_only_identifiers_and_keeps_all_golden_labels():
    original = load_evaluation_dataset(DEFAULT_DATASET)
    bound = bind_reference(original, fixture_document())
    assert len(bound.items) == 20
    assert all(item.ebook_id == 34 and item.book_id == 12 and item.document_id == 99 for item in bound.items)
    for source, item in zip(original.items, bound.items, strict=True):
        assert (source.question, source.answerable, source.expected_pages, source.expected_answer_points) == (
            item.question, item.answerable, item.expected_pages, item.expected_answer_points)
        assert len(item.expected_chunk_ids) == len(source.expected_chunk_ids)
    assert original.items[0].ebook_id == 10


@pytest.mark.parametrize('mutation', ['pdf', 'status', 'count', 'content', 'version', 'vector', 'scope', 'index'])
def test_binding_refuses_mismatched_or_incomplete_reference(mutation):
    doc = deepcopy(fixture_document())
    if mutation == 'pdf': doc['sourceSha256'] = 'wrong'
    elif mutation == 'status': doc['status'] = 'FAILED'
    elif mutation == 'count': doc['chunks'].pop()
    elif mutation == 'content': doc['chunks'][0]['sha256'] = 'wrong'
    elif mutation == 'version': doc['chunks'][0]['chunkingVersion'] = 'v2'
    elif mutation == 'vector': doc['chunks'][0]['vectorId'] = None
    elif mutation == 'scope': doc['ebookId'] = None
    elif mutation == 'index': doc['chunks'][0]['index'] = 6
    with pytest.raises(ValueError):
        bind_reference(load_evaluation_dataset(DEFAULT_DATASET), doc)


def test_nearest_rank_latency_summary_is_not_a_load_claim():
    assert latency_summary([None, float('nan')])['count'] == 0
    assert latency_summary(range(1, 21)) == {'count': 20, 'p50Ms': 10, 'p95Ms': 19}
