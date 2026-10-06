"""Regressions for heading formats found by the real-PDF benchmark.

Source labels and the frozen v1 baseline remain unchanged. These tests use
synthetic prose; the reviewed manifest supplies only the real heading formats.
"""

from copy import deepcopy

from llama_index.core import Document
from llama_index.core.schema import MetadataMode, NodeRelationship
import pytest

from app.evaluation.chunking_comparison import load_dataset
from app.ingestion.chunking.chapter_aware_strategy import ChapterAwareChunkingStrategy
from app.ingestion.chunking.chapter_boundaries import find_chapter_boundaries
from scripts.benchmark_book_chunking import DEFAULT_DATASET


def page(text: str, number: int, **metadata) -> Document:
    return Document(text=text, metadata={
        "document_id": 7, "documentId": "heading-regression", "bookId": 101,
        "ebookId": 55, "page_number": number, **metadata,
    })


def split(documents):
    return ChapterAwareChunkingStrategy().build_nodes(documents, chunk_size=512, chunk_overlap=64)


@pytest.mark.parametrize("heading,title,number", [
    ("CHAPTER I.", "CHAPTER I.", "I"),
    ("##### **CHAPTER I.**", "CHAPTER I.", "I"),
    ("##### **CHAPTER XI.**", "CHAPTER XI.", "XI"),
    ("## **Chapter 2.** ##", "Chapter 2.", "2"),
    ("**Chapter One.**", "Chapter One.", "One"),
    ("Chương 2.", "Chương 2.", "2"),
    ("   ##### **CHAPTER III.**", "CHAPTER III.", "III"),
    ("PREFACE.", "PREFACE.", "PREFACE"),
    ("APPENDIX.", "APPENDIX.", "APPENDIX"),
    ("Appendix", "Appendix", "Appendix"),
    ("## **Appendix.**", "Appendix.", "Appendix"),
    ("Appendix: Notes", "Appendix: Notes", "Appendix"),
])
def test_real_heading_formats_are_detected_without_rewriting_source(heading, title, number):
    prefix = "Earlier source paragraph.\n\n"
    boundaries, fence = find_chapter_boundaries(prefix + heading + "\n\nNew source paragraph.")
    assert fence is None
    assert len(boundaries) == 1
    assert (boundaries[0].offset, boundaries[0].title, boundaries[0].number) == (len(prefix), title, number)


@pytest.mark.parametrize("line", [
    "Chapter I..", "APPENDIX..", "CHAPTER I. .... 19", "APPENDIX. .... 119",
    "##### **CHAPTER I. .... 19**", "## Appendix…119", "Appendix explains the sources.",
    "Appendixness", "Chapter IVory.", "Chapter 1st.", "Chapter I?", "Appendix!",
    "| APPENDIX. | 119 |", "> APPENDIX.", "- CHAPTER I.", "    APPENDIX.",
    '"CHAPTER I."', "“APPENDIX.”",
])
def test_punctuation_support_does_not_accept_toc_prose_or_nonheading_structures(line):
    assert find_chapter_boundaries(line)[0] == []


def test_all_independently_reviewed_douglass_headings_match_without_relabeling():
    dataset = load_dataset(DEFAULT_DATASET)
    book = next(book for book in dataset.books if book.id == "douglass-narrative")
    assert len(book.chapters) == 12
    for label in book.chapters:
        boundaries, _ = find_chapter_boundaries(label.heading)
        assert len(boundaries) == 1
        assert boundaries[0].title == label.title


def test_preface_carry_stops_at_real_main_headings_and_appendix_with_exact_spans():
    documents = [
        page("PREFACE.\n\nAn introductory paragraph.", 1),
        page("The final preface paragraph.\n\n##### **CHAPTER I.**\n\nThe first main chapter.", 2),
        page("The first chapter continues here.", 3),
        page("##### **CHAPTER II.**\n\nThe second main chapter.\n\nAPPENDIX.\n\nThe appendix begins.", 4),
        page("The appendix continues here.", 5),
    ]
    originals = deepcopy([document.model_dump() for document in documents])
    nodes = split(documents)
    assert [node.metadata["chapter_title"] for node in nodes] == [
        "PREFACE.", "CHAPTER I.", "CHAPTER II.", "APPENDIX.",
    ]
    assert [node.metadata["chapter_source_page"] for node in nodes] == [1, 2, 4, 4]
    assert [(node.metadata["pageStart"], node.metadata["pageEnd"]) for node in nodes] == [
        (1, 2), (2, 3), (4, 4), (4, 5),
    ]
    assert "final preface" in nodes[0].get_content(metadata_mode=MetadataMode.NONE)
    assert "final preface" not in nodes[1].get_content(metadata_mode=MetadataMode.NONE)
    covered = {document.metadata["page_number"]: set() for document in documents}
    by_id = {node.id_: node for node in nodes}
    for node in nodes:
        text = node.get_content(metadata_mode=MetadataMode.NONE)
        for span in node.metadata["source_spans"]:
            source = documents[span["pageNumber"] - 1].text
            assert source[span["sourceCharStart"]:span["sourceCharEnd"]] == text[
                span["chunkCharStart"]:span["chunkCharEnd"]]
            covered[span["pageNumber"]].update(range(span["sourceCharStart"], span["sourceCharEnd"]))
        for direction in (NodeRelationship.PREVIOUS, NodeRelationship.NEXT):
            related = node.relationships.get(direction)
            if related is not None:
                assert by_id[related.node_id].metadata["chapter_title"] == node.metadata["chapter_title"]
    for document in documents:
        assert all(character.isspace() or index in covered[document.metadata["page_number"]]
                   for index, character in enumerate(document.text))
    assert [document.model_dump() for document in documents] == originals


def test_real_headings_in_cross_page_code_fence_do_not_reset_the_chapter():
    nodes = split([
        page("##### **CHAPTER I.**\n\nActual chapter text.\n\n```text\ncode starts", 1),
        page("##### **CHAPTER II.**\nAPPENDIX.\n```\n\nActual chapter continuation.", 2),
        page("APPENDIX.\n\nActual appendix text.", 3),
    ])
    assert [node.metadata["chapter_title"] for node in nodes] == ["CHAPTER I.", "APPENDIX."]
    assert "CHAPTER II." in nodes[0].get_content(metadata_mode=MetadataMode.NONE)
    assert nodes[0].metadata["pageEnd"] == 2
    assert nodes[1].metadata["pageStart"] == 3


@pytest.mark.parametrize("front_matter", ["PREFACE", "Foreword", "Introduction"])
def test_title_block_before_main_text_does_not_inherit_front_matter(front_matter):
    title_block = "##### **NARRATIVE**\n\n###### **OF THE**\n\n#### **A TEST BOOK.**"
    documents = [page(front_matter + "\n\nAn introductory passage.", 1),
                 page(title_block + "\n\n##### **CHAPTER I.**\n\nThe main story starts.", 2)]
    nodes = split(documents)
    assert len(nodes) == 3
    assert nodes[0].metadata["chapter_title"] == front_matter
    assert nodes[1].metadata["chapter_detected"] is False
    assert "chapter_title" not in nodes[1].metadata
    assert nodes[1].get_content(metadata_mode=MetadataMode.NONE) == title_block
    assert nodes[1].metadata["pageStart"] == nodes[1].metadata["pageEnd"] == 2
    assert nodes[2].metadata["chapter_title"] == "CHAPTER I."


def test_ordinary_prose_before_main_heading_keeps_its_front_matter_label():
    nodes = split([page("PREFACE\n\nAn introductory passage.", 1),
                   page("The preface ends here.\n\n##### **CHAPTER I.**\n\nThe story starts.", 2)])
    assert len(nodes) == 2
    assert nodes[0].metadata["chapter_title"] == "PREFACE"
    assert "preface ends" in nodes[0].get_content(metadata_mode=MetadataMode.NONE)
    assert nodes[1].metadata["chapter_title"] == "CHAPTER I."
