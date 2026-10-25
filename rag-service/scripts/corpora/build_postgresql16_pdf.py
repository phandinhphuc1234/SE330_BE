"""Typeset the pinned, licensed PostgreSQL 16 chapter snapshot without network calls.

Authoring-only dependencies: lxml + reportlab (not RAG runtime requirements).
Run from any directory; all paths resolve relative to this repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from xml.sax.saxutils import escape

from lxml import html

REPO = Path(__file__).resolve().parents[3]
FIXTURE = REPO / "rag-service/tests/fixtures/corpora/postgresql16_concurrency_v1"
PAGES = (
    "mvcc", "mvcc-intro", "transaction-iso", "explicit-locking",
    "applevel-consistency", "mvcc-serialization-failure-handling",
    "mvcc-caveats", "locking-indexes", "legalnotice",
)
BASE_URL = "https://www.postgresql.org/docs/16/"
SNAPSHOT_DATE = "2026-10-25"


def normalize(text: str, *, code: bool = False) -> str:
    text = text.replace("\u00a0", " ").translate(str.maketrans({
        "\u2010": "-", "\u2011": "-", "\u2012": "-", "\u2013": "-", "\u2014": " - ",
        "\u2018": "'", "\u2019": "'", "\u201c": '"', "\u201d": '"',
    }))
    return text.strip("\n") if code else re.sub(r"\s+", " ", text).strip()


def extract_blocks(source_dir: Path = FIXTURE / "source") -> list[dict]:
    """Keep prose, definitions, lists, SQL and tables; drop web navigation only."""
    blocks = []
    for name in PAGES:
        tree = html.fromstring((source_dir / f"{name}.html").read_bytes())
        containers = tree.xpath("//div[@class='chapter' or @class='sect1' or @class='legalnotice']")
        if len(containers) != 1:
            raise ValueError(f"Unexpected source structure: {name}")
        container = containers[0]
        for link in container.xpath(".//a[@class='id_link']"):
            link.drop_tree()
        ordinal = 0

        def add(kind, element, **extra):
            nonlocal ordinal
            ordinal += 1
            text = normalize(element.text_content(), code=kind == "code")
            block = {"id": f"{name}-{ordinal:03}", "source": BASE_URL + name + ".html",
                     "kind": kind, "text": text, **extra}
            if text or kind == "table":
                blocks.append(block)

        def walk(element):
            tag = element.tag
            if not isinstance(tag, str) or "toc" in element.get("class", "").split():
                return
            if tag in ("h1", "h2", "h3", "h4", "h5"):
                add("heading", element, level=int(tag[1]))
            elif tag == "table":
                rows = []
                for tr in element.xpath("./thead/tr | ./tbody/tr | ./tfoot/tr | ./tr"):
                    rows.append([{ "text": normalize(cell.text_content()),
                                   "colspan": int(cell.get("colspan", "1")),
                                   "rowspan": int(cell.get("rowspan", "1"))}
                                 for cell in tr if cell.tag in ("th", "td")])
                header_rows = len(element.xpath("./thead/tr"))
                if header_rows == 2 and rows[0][0]["rowspan"] == 2:
                    # A single explicit column-header row avoids ambiguous merged
                    # header extraction while preserving every conflict cell.
                    rows = [[{**rows[0][0], "rowspan": 1}, *rows[1]], *rows[2:]]
                    header_rows = 1
                add("table", element, rows=rows, headerRows=header_rows)
            elif tag == "pre":
                add("code", element)
            elif tag == "p":
                add("paragraph", element)
            elif tag == "dt":
                add("definition", element)
            elif tag == "li":
                for child in element:
                    walk(child)
                if not len(element):
                    add("paragraph", element)
            else:
                for child in element:
                    walk(child)

        walk(container)
    return blocks


def build(output: Path, manifest_path: Path, *, fonts: Path):
    # Imported here so extraction tests do not require the authoring dependency.
    from reportlab.lib import colors
    from reportlab.lib.enums import TA_LEFT
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.platypus import (
        BaseDocTemplate, Frame, PageBreak, PageTemplate, Paragraph,
        Preformatted, Spacer, Table, TableStyle,
    )

    for name, file in (("Body", "arial.ttf"), ("BodyBold", "arialbd.ttf"),
                       ("Mono", "consola.ttf")):
        pdfmetrics.registerFont(TTFont(name, str(fonts / file)))
    blocks = extract_blocks()
    page_map = {}
    ink, muted = colors.HexColor("#14273b"), colors.HexColor("#536272")
    body = ParagraphStyle("Body", fontName="Body", fontSize=10.2, leading=15.2,
                          spaceAfter=9, textColor=ink, splitLongWords=False)
    small = ParagraphStyle("Small", parent=body, fontSize=8, leading=11, textColor=muted)
    heading = ParagraphStyle("Heading", parent=body, fontName="BodyBold", fontSize=17,
                             leading=22, spaceBefore=13, spaceAfter=9, keepWithNext=True)
    subheading = ParagraphStyle("Subheading", parent=heading, fontSize=13, leading=18)
    definition = ParagraphStyle("Definition", parent=body, fontName="BodyBold",
                                spaceBefore=6, spaceAfter=4, keepWithNext=True)
    cell_style = ParagraphStyle("Cell", parent=body, fontSize=7.2, leading=9.7,
                               spaceAfter=0, alignment=TA_LEFT, splitLongWords=False)
    code_style = ParagraphStyle("Code", fontName="Mono", fontSize=8, leading=11.3,
                               leftIndent=9, rightIndent=9, spaceBefore=5, spaceAfter=11,
                               textColor=ink, backColor=colors.HexColor("#f0f4f7"))
    width, height = A4
    frame_width = width - 100

    class TrackingParagraph(Paragraph):
        def split(self, w, h):
            result = super().split(w, h)
            for part in result:
                part.block_id = getattr(self, "block_id", None)
            return result

    class TrackingTable(Table):
        def split(self, w, h):
            result = super().split(w, h)
            for part in result:
                part.block_id = getattr(self, "block_id", None)
            return result

    class Document(BaseDocTemplate):
        def afterFlowable(self, flowable):
            block_id = getattr(flowable, "block_id", None)
            if block_id:
                pages = page_map.setdefault(block_id, [])
                if self.page not in pages:
                    pages.append(self.page)
                if isinstance(flowable, Paragraph) and flowable.style.name in ("Heading", "Subheading"):
                    self.canv.bookmarkPage(block_id)
                    self.canv.addOutlineEntry(flowable.getPlainText(), block_id, level=0, closed=False)

    def decorate(canvas, doc):
        canvas.saveState()
        canvas.setStrokeColor(colors.HexColor("#d5dde4"))
        canvas.line(50, height - 41, width - 50, height - 41)
        canvas.setFont("Body", 8)
        canvas.setFillColor(muted)
        canvas.drawString(50, height - 31, "POSTGRESQL 16  /  CONCURRENCY CONTROL")
        canvas.line(50, 40, width - 50, 40)
        canvas.drawString(50, 27, "Official documentation excerpt - re-typeset snapshot, 2026-10-25")
        canvas.drawRightString(width - 50, 27, str(doc.page))
        canvas.restoreState()

    output.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    doc = Document(str(output), pagesize=A4, leftMargin=50, rightMargin=50,
                   topMargin=57, bottomMargin=55, invariant=1,
                   title="PostgreSQL 16 - Chapter 13: Concurrency Control",
                   author="PostgreSQL Global Development Group; re-typeset for Athenaeum RAG")
    doc.addPageTemplates(PageTemplate(id="main", frames=[Frame(50, 55, frame_width, height-112,
                          leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)], onPage=decorate))
    story = [Spacer(1, 55), Paragraph("TECHNICAL RAG DEMONSTRATION CORPUS", small), Spacer(1, 17),
             Paragraph("PostgreSQL 16<br/>Concurrency Control", ParagraphStyle("Cover", parent=heading,
                       fontSize=31, leading=39)), Spacer(1, 14),
             Paragraph("Chapter 13 of the official PostgreSQL documentation", body),
             Paragraph("Sections 13.1-13.7, including SQL examples and Tables 13.1-13.3.", body),
             Spacer(1, 25),
             Paragraph("Snapshot and citation contract", subheading),
             Paragraph("Source snapshot retrieved on 2026-10-25. This is a re-typeset excerpt, not an official PostgreSQL PDF. "
                       "Printed page numbers equal the PDF's one-based page positions. External cross-references retain their "
                       "original labels; material outside Chapter 13 is not included.", body),
             Paragraph("Wording, SQL examples and table contents come from the official documentation. Web navigation and "
                       "duplicate section menus are omitted; typographic quotes/dashes and nonbreaking spaces are normalized. "
                       "The complete PostgreSQL Legal Notice is reproduced at the end.", body),
             Paragraph(f'<link href="{BASE_URL}mvcc.html">{BASE_URL}mvcc.html</link>', small), PageBreak()]
    last_source = None
    for block in blocks:
        if block["source"] != last_source:
            if last_source is not None:
                story.append(PageBreak())
            last_source = block["source"]
            story.append(Paragraph(escape(block["source"]), small))
            story.append(Spacer(1, 6))
        kind, text = block["kind"], block["text"]
        if kind == "table":
            grid, spans, occupied = [], [], set()
            for r, row in enumerate(block["rows"]):
                while len(grid) <= r:
                    grid.append([])
                c = 0
                for cell in row:
                    while (r, c) in occupied:
                        c += 1
                    end_r, end_c = r + cell["rowspan"] - 1, c + cell["colspan"] - 1
                    while len(grid) <= end_r:
                        grid.append([])
                    for rr in range(r, end_r + 1):
                        while len(grid[rr]) <= end_c:
                            grid[rr].append("")
                        for cc in range(c, end_c + 1):
                            occupied.add((rr, cc))
                    grid[r][c] = Paragraph(escape(cell["text"]), cell_style)
                    if end_r != r or end_c != c:
                        spans.append(("SPAN", (c, r), (end_c, end_r)))
                    c = end_c + 1
            columns = max(map(len, grid))
            grid = [row + [""] * (columns - len(row)) for row in grid]
            widths = [frame_width * .23] + [frame_width * .77 / (columns-1)] * (columns-1)
            flowable = TrackingTable(grid, colWidths=widths, repeatRows=block["headerRows"], hAlign="LEFT")
            flowable.setStyle(TableStyle([
                ("VALIGN", (0,0), (-1,-1), "TOP"), ("GRID", (0,0),(-1,-1), .4, colors.HexColor("#c9d2db")),
                ("BACKGROUND", (0,0),(-1,block["headerRows"]-1),colors.HexColor("#e6edf3")),
                ("TOPPADDING", (0,0),(-1,-1),6), ("BOTTOMPADDING", (0,0),(-1,-1),6), *spans]))
        elif kind == "code":
            if any(pdfmetrics.stringWidth(line, "Mono", 8) > frame_width - 18 for line in text.splitlines()):
                raise ValueError(f"Code line exceeds the printable width: {block['id']}")
            flowable = Preformatted(text, code_style)
        else:
            style = (heading if block.get("level", 9) <= 2 else subheading) if kind == "heading" else (
                definition if kind == "definition" else body)
            flowable = TrackingParagraph(escape(text), style)
        flowable.block_id = block["id"]
        story.append(flowable)
        if kind == "table":
            story.append(Spacer(1, 12))
    doc.build(story)
    payload = {"corpus": "postgresql16_concurrency_v1", "snapshotDate": SNAPSHOT_DATE,
               "sourceVersion": "PostgreSQL 16", "pdfSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
               "pageCount": doc.page, "sizeBytes": output.stat().st_size,
               "sources": [{"url": BASE_URL + name + ".html", "file": "source/"+name+".html",
                            "sha256": hashlib.sha256((FIXTURE / "source" / (name+".html")).read_bytes()).hexdigest()}
                           for name in PAGES],
               "blocks": [{**block, "pages": page_map.get(block["id"], [])} for block in blocks]}
    manifest_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: payload[key] for key in ("pdfSha256", "pageCount", "sizeBytes")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=REPO / "output/pdf/postgresql-16-concurrency-control-v1.pdf")
    parser.add_argument("--manifest", type=Path, default=FIXTURE / "source-manifest.json")
    parser.add_argument("--fonts", type=Path, default=Path("C:/Windows/Fonts"))
    args = parser.parse_args()
    build(args.output, args.manifest, fonts=args.fonts)
