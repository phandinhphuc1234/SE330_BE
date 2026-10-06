# Parser Architecture

## Goal

Parser layer is responsible for turning a local validated file into parsed text units.

It does not:

```text
validate PDF
clean text
chunk text
embed vectors
write Qdrant
```

Those are separate stages.

## Current default

The default PDF parser is:

```text
PyMuPDF4LLMParser
```

It produces page-wise Markdown-like text:

```text
PDF file
  -> ParsedDocument(page_number=1)
  -> ParsedDocument(page_number=2)
  -> ...
```

## Code structure

```text
app/ingestion/parsers/
  base.py                         # DocumentParser, ParsedDocument, ParserRegistry
  factory.py                      # reads settings and builds parser registry
  simple_file_parsers.py          # Markdown/HTML/DOCX parsers
  pdf/
    pymupdf4llm_parser.py         # default PDF parser
```

Legacy loader imports still exist under:

```text
app/ingestion/loaders/
```

but new ingestion code should use:

```python
from app.ingestion.parsers import build_default_parser_registry
```

## How pipeline uses parsers

`IngestionPipeline` depends on `ParserRegistry`, not on PyMuPDF4LLM directly.

```text
pipeline
  -> parser_registry.get_parser(file_path)
  -> parser.parse(file_path)
```

This means replacing the PDF parser should not require changing pipeline orchestration.

## Config

Default:

```env
PDF_PARSER=pymupdf4llm
```

Supported now:

```text
pymupdf4llm
pymupdf_markdown
default
```

They all resolve to `PyMuPDF4LLMParser`.

## Adding a future parser

Example: remote Docling parser.

Add:

```text
app/ingestion/parsers/pdf/remote_docling_parser.py
```

Implement:

```python
class RemoteDoclingParser(DocumentParser):
    name = "remote_docling"
    supported_extensions = {".pdf"}

    def parse(self, file_path: Path) -> list[ParsedDocument]:
        ...
```

Register it in:

```text
app/ingestion/parsers/factory.py
```

Example:

```python
if normalized in {"remote_docling", "docling"}:
    return RemoteDoclingParser(...)
```

Then switch config:

```env
PDF_PARSER=remote_docling
```

No `IngestionPipeline` rewrite should be needed.

## Why this is SOLID-friendly

- Single Responsibility: each parser only parses one format/mode.
- Open/Closed: add parser classes without changing pipeline logic.
- Liskov Substitution: every parser returns `list[ParsedDocument]`.
- Interface Segregation: parser interface only exposes `parse()` and `supports()`.
- Dependency Inversion: pipeline depends on `DocumentParser` abstraction via `ParserRegistry`.
