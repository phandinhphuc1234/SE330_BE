# PDF Parsing, Cleaning, Markdown, And Asset Strategy

## Context

Tài liệu này ghi lại hướng nghiên cứu từ repo `arjungowdal4601/doc_processing` và cách áp dụng có chọn lọc vào RAG platform hiện tại.

Nguồn tham khảo chính:

- Repo: <https://github.com/arjungowdal4601/doc_processing>
- Commit đã kiểm tra: `b83e29ad82d9f6b2217bdcae8dbbc525597e819e`
- File chính đã đọc: `README.md`, `requirements.txt`, `doc_processor.py`, `doc_assets/processed_doc.md`

Mục tiêu không phải copy nguyên repo đó vào hệ thống của mình. Mục tiêu là học pattern tốt của họ rồi thiết kế lại cho đúng kiến trúc hiện tại:

```text
Spring Boot owns source PDF
  -> library-private
RAG owns derived artifacts
  -> rag-artifacts
RAG worker uses local temp only during task execution
```

## Repo `doc_processing` đang làm gì?

Repo đó không chỉ làm PDF-to-text. Nó làm một pipeline kiểu document understanding:

```text
PDF
  -> process từng page
  -> Docling parse layout/content
  -> render page image
  -> crop image/table/formula assets
  -> dùng vision model mô tả figure/table/formula
  -> export Markdown từng page
  -> thay placeholder bằng image link + description
  -> stitch thành processed_doc.md
```

Các output chính:

```text
doc_assets/
  processed_doc.md
  page_images/
  image_png_images/
  table_images/
  formula_images/
  pages_md/
```

Ý tưởng đáng học nhất là:

```text
Không chỉ extract text.
Phải giữ page boundary, markdown structure, hình, bảng, công thức, và mô tả retrieval-friendly.
```

## Phương pháp kỹ thuật của repo đó

### 1. Page-wise processing

Repo xử lý từng page một thay vì convert nguyên PDF một lần.

Lợi ích:

- Dễ debug: page nào lỗi nhìn được page đó.
- Ít tốn RAM hơn.
- Dễ retry từng page sau này.
- Dễ giữ citation theo page.
- Dễ tạo `pages_md/page_0001.md`, `page_0002.md`.

Điểm này rất hợp với hệ thống thư viện của mình vì RAG cần citation:

```text
answer
  -> source book
  -> ebook
  -> pageStart/pageEnd
```

### 2. Markdown làm representation trung gian

Repo tạo Markdown theo page, sau đó ghép thành `processed_doc.md`.

Markdown tốt hơn plain text vì giữ được:

- heading
- list
- table placeholder/description
- formula block
- image reference
- page separator

Với RAG, Markdown thường chunk tốt hơn raw plain text vì nó giữ cấu trúc tài liệu.

### 3. Visual assets không bị vứt bỏ

Repo crop và lưu:

```text
page_images/
image_png_images/
table_images/
formula_images/
```

Sau đó chèn vào Markdown:

```md
![Figure](image_png_images/sample-picture-1.png)

The figure is a block diagram...
```

Điểm hay là retriever có text description để search, còn UI/citation vẫn có ảnh gốc để hiển thị.

### 4. Vision model để mô tả non-text content

Repo dùng OpenAI vision thông qua `langchain-openai` để mô tả:

- figure
- chart
- diagram
- table image
- formula image

Đây là hướng mạnh cho sách kỹ thuật vì nhiều nội dung quan trọng nằm trong hình/bảng/công thức. Nếu chỉ dùng `page.extract_text()` thì mất rất nhiều nghĩa.

### 5. Placeholder replacement

Repo export Markdown bằng Docling với placeholder, rồi thay placeholder bằng block đã enrich:

```text
[[DOC_IMAGE]]
[[DOC_TABLE]]
[[DOC_FORMULA]]
```

Sau đó render thành:

```md
![Table](table_images/...)

Description...
```

Đây là pattern hay, nhưng khi đưa vào hệ thống của mình nên làm chuẩn hơn bằng manifest thay vì chỉ dựa vào thứ tự placeholder.

## Có áp dụng được vào hệ thống hiện tại không?

Có, nhưng nên áp dụng theo phase.

Hiện tại hệ thống của mình đang dùng:

```text
PyMuPDF4LLM
  -> extract markdown/text per page
clean_text()
  -> normalize whitespace
RecursiveChunker
  -> split text
PostgreSQL
  -> document_chunks
```

Và đã có validation trước parse:

```text
HEAD S3
download temp
checksum
magic bytes
pypdf structure/encrypted/page count
text layer detection
```

Vì vậy hướng phù hợp là:

```text
Không thay toàn bộ bằng Docling ngay.
Trước mắt học cách tổ chức output artifacts và page-wise Markdown.
Sau đó mới thêm Docling/vision như advanced parser mode.
```

## Có nên lưu `doc_assets` trong project folder không?

Không nên lưu runtime `doc_assets` thật vào project folder trong production.

Lý do:

- Docker container có thể bị recreate, mất file.
- File assets có thể lớn.
- Một ebook có thể sinh rất nhiều ảnh page/table/formula.
- Nhiều worker chạy song song dễ đụng folder.
- Backup/permission/lifecycle nên do object storage quản lý.
- Source code repo không nên chứa data runtime.

Trong production, assets nên lưu vào:

```text
rag-artifacts
```

Local folder chỉ nên dùng cho:

```text
/tmp/rag/...
```

hoặc dev-only:

```text
data/rag-artifacts-dev/
```

và phải nằm trong `.gitignore`.

## Nên tổ chức artifacts trong S3 như thế nào?

Đề xuất object key trong bucket `rag-artifacts`:

```text
rag-artifacts/
  documents/
    doc_ebook_{ebookId}/
      source/
        original_pointer.json
      versions/
        v{checksumPrefix}/
          manifest.json
          processed_doc.md
          pages_md/
            page_0001.md
            page_0002.md
          parsed/
            page_0001.raw.md
            page_0002.raw.md
          cleaned/
            page_0001.clean.md
            page_0002.clean.md
          chunks/
            chunks.jsonl
          page_images/
            page_0001.png
            page_0002.png
          figures/
            figure_0001.png
          tables/
            table_0001.png
          formulas/
            formula_0001.png
          logs/
            parse.log
            failed_parse.log
```

Ví dụ thật:

```text
s3://rag-artifacts/documents/doc_ebook_55/versions/v9f3a2a7e/processed_doc.md
s3://rag-artifacts/documents/doc_ebook_55/versions/v9f3a2a7e/pages_md/page_0001.md
s3://rag-artifacts/documents/doc_ebook_55/versions/v9f3a2a7e/chunks/chunks.jsonl
s3://rag-artifacts/documents/doc_ebook_55/versions/v9f3a2a7e/figures/figure_0001.png
```

Trong Markdown nên dùng relative path nội bộ:

```md
![Figure](figures/figure_0001.png)
```

Khi UI cần hiển thị, backend/RAG có thể resolve relative path thành presigned URL.

## Manifest nên chứa gì?

Nên có `manifest.json` để không phụ thuộc vào việc scan folder.

Ví dụ:

```json
{
  "documentId": "doc_ebook_55",
  "bookId": 101,
  "ebookId": 55,
  "source": {
    "bucket": "library-private",
    "objectKey": "ebooks/101/55/original.pdf",
    "checksumSha256": "..."
  },
  "parser": {
    "name": "pymupdf4llm",
    "version": "1.28.0",
    "mode": "page_markdown"
  },
  "artifacts": {
    "processedMarkdown": "processed_doc.md",
    "chunks": "chunks/chunks.jsonl",
    "pagesMarkdownPrefix": "pages_md/",
    "pageImagesPrefix": "page_images/",
    "figuresPrefix": "figures/",
    "tablesPrefix": "tables/",
    "formulasPrefix": "formulas/"
  },
  "pages": [
    {
      "pageNumber": 1,
      "markdown": "pages_md/page_0001.md",
      "rawMarkdown": "parsed/page_0001.raw.md",
      "cleanMarkdown": "cleaned/page_0001.clean.md",
      "pageImage": "page_images/page_0001.png",
      "assets": [
        {
          "type": "figure",
          "assetKey": "figures/figure_0001.png",
          "description": "..."
        }
      ]
    }
  ]
}
```

Manifest giúp:

- retry an toàn
- audit parser version
- debug từng page
- chunk lại mà không cần parse PDF lại
- re-embed khi đổi embedding model
- UI biết asset nào thuộc page nào

## Nên có folder local không?

Có, nhưng chỉ là temp/staging trong worker.

Trong task worker:

```text
/tmp/rag/
  jobs/
    ingestion_job_{jobId}/
      original.pdf
      pages_md/
      processed_doc.md
      figures/
      tables/
      formulas/
```

Sau khi task xong:

```text
upload artifacts to rag-artifacts
cleanup /tmp/rag/jobs/ingestion_job_{jobId}
```

Local folder trong project chỉ nên dành cho dev:

```text
data/
  rag-artifacts-dev/
    documents/
      doc_ebook_55/
```

Nhưng production không nên phụ thuộc vào folder này.

## Ứng dụng vào pipeline hiện tại

Pipeline hiện tại:

```text
validate PDF
  -> PyMuPDF4LLM parse pages
  -> clean_text per page
  -> chunk per page
  -> persist chunks
```

Đề xuất nâng cấp:

```text
validate PDF
  -> parse_pages_to_markdown
  -> write parsed/page_0001.raw.md
  -> clean_page_markdown
  -> write cleaned/page_0001.clean.md
  -> stitch processed_doc.md
  -> chunk cleaned markdown
  -> write chunks/chunks.jsonl
  -> persist chunks to PostgreSQL
  -> later embed/upsert Qdrant
```

Tức là thêm artifact layer giữa parse/clean/chunk:

```text
PDF -> parsed artifacts -> cleaned artifacts -> chunks artifacts -> DB/vector
```

Điểm này rất quan trọng: nếu sau này cleaner sai, mình chỉ cần chạy lại từ parsed artifacts, không cần parse PDF lại.

## Parser nên dùng gì?

### Hiện tại nên giữ PyMuPDF4LLM làm default

Lý do:

- Đã cài trong project.
- Nhẹ hơn Docling.
- Đã tích hợp vào `PdfLoader`.
- Phù hợp MVP text-heavy ebook.
- Ít dependency native hơn.

Default mode:

```text
parser = pymupdf4llm
```

### Docling nên là advanced mode

Docling phù hợp khi tài liệu có nhiều:

- layout phức tạp
- bảng
- công thức
- hình kỹ thuật
- multi-column
- scanned/OCR need

Advanced mode:

```text
parser = docling_vision
```

Không nên bật Docling + vision mặc định ngay vì:

- runtime nặng hơn
- dependency phức tạp hơn
- tốn tiền vision model
- chậm hơn
- cần queue riêng hoặc rate limit riêng

Hướng tốt:

```text
Nếu PDF text-heavy:
  dùng PyMuPDF4LLM

Nếu PDF nhiều table/image/formula hoặc user yêu cầu quality cao:
  dùng Docling + optional vision enrichment
```

## Xử lý ảnh trong sách

Sách kỹ thuật có thể chứa ảnh rất quan trọng. Nếu parser bỏ ảnh, RAG sẽ thiếu nghĩa.

Đề xuất:

1. Luôn giữ metadata image count/page.
2. Với advanced parser, crop ảnh và lưu:

```text
figures/figure_0001.png
```

3. Sinh mô tả text:

```md
![Figure](figures/figure_0001.png)

Figure description: ...
```

4. Khi chunk, chunk chứa description và metadata trỏ tới image asset:

```json
{
  "chunkType": "figure_description",
  "pageStart": 3,
  "pageEnd": 3,
  "assetKeys": ["figures/figure_0001.png"]
}
```

Với MVP hiện tại, chưa cần vision ngay. Nhưng nên thiết kế metadata/chunks để sau này gắn asset được.

## Xử lý bảng

Table nên được giữ theo 2 dạng nếu có thể:

```text
1. Markdown table hoặc HTML table
2. Table image + description
```

Nếu bảng parse ra Markdown ổn:

```md
| Layer Type | Complexity | Sequential Operations |
| --- | --- | --- |
| Self-Attention | O(n²d) | O(1) |
```

Nếu bảng phức tạp:

```md
![Table](tables/table_0001.png)

Table description: ...
```

Không nên chỉ lưu ảnh table mà không có description, vì vector search không tìm được nội dung trong ảnh.

## Xử lý công thức/ký tự đặc biệt

Sách kỹ thuật có nhiều:

- ký hiệu toán học
- Unicode
- Greek letters
- công thức
- code block
- superscript/subscript

Đề xuất:

### 1. Luôn dùng UTF-8

Mọi artifact text/markdown/jsonl nên write/read bằng:

```text
encoding="utf-8"
```

### 2. Normalize Unicode nhẹ

Cleaner nên có bước:

```text
unicodedata.normalize("NFKC", text)
```

Nhưng phải cẩn thận: normalize quá mạnh có thể làm hỏng công thức/code. Nên áp dụng theo mode hoặc test kỹ.

### 3. Giữ công thức dạng LaTeX nếu có

Nếu parser extract được:

```md
LaTeX: E = mc^2
```

thì chunk nên giữ luôn. Công thức nên có description đi kèm:

```md
LaTeX: E = mc^2

This formula expresses mass-energy equivalence...
```

### 4. Không clean quá tay code/math

Cleaner không nên xóa hết newline trong:

```text
```python
...
```
```

hoặc block formula. Vì cấu trúc đó có nghĩa.

## Processed document nên dùng như thế nào?

`processed_doc.md` là bản Markdown đã ghép toàn bộ document.

Nên dùng cho:

- debug nhanh toàn ebook
- re-chunk toàn ebook
- lưu evidence của parser
- human inspection

Nhưng chunking production nên ưu tiên `pages_md/` hoặc `cleaned/page_XXXX.clean.md`, vì page-level dễ giữ citation hơn.

Khuyến nghị:

```text
pages_md/page_0001.md
  -> source of truth for page-level chunking/debug

processed_doc.md
  -> stitched artifact for review/reprocessing

chunks/chunks.jsonl
  -> exact chunks sent to embedding
```

## Đề xuất module hóa trong codebase

Hiện tại:

```text
app/ingestion/loaders/pdf_loader.py
app/ingestion/cleaners/text_cleaner.py
app/ingestion/chunkers/recursive_chunker.py
app/ingestion/pipeline.py
```

Đề xuất nâng cấp:

```text
app/ingestion/parsers/
  base.py
  pymupdf_markdown_parser.py
  docling_markdown_parser.py        # future advanced mode

app/ingestion/artifacts/
  artifact_writer.py
  manifest.py
  markdown_writer.py

app/ingestion/cleaners/
  markdown_cleaner.py
  text_cleaner.py

app/ingestion/assets/
  asset_models.py
  asset_describer.py                # future vision mode
  asset_manifest.py

app/ingestion/chunkers/
  recursive_chunker.py
  markdown_aware_chunker.py         # future
```

`pipeline.py` vẫn là orchestrator:

```text
pipeline.py
  -> calls parser
  -> calls artifact writer
  -> calls cleaner
  -> calls chunker
  -> calls DB repository
```

Không nên nhét toàn bộ parse/asset/vision logic vào `pipeline.py`.

## Đề xuất implementation theo phase

### Phase 1: Artifact foundation với PyMuPDF4LLM

Mục tiêu:

- Giữ parser hiện tại.
- Ghi page markdown vào temp.
- Upload artifacts vào `rag-artifacts`.

Output:

```text
processed_doc.md
pages_md/page_0001.md
cleaned/page_0001.clean.md
chunks/chunks.jsonl
manifest.json
```

Chưa xử lý ảnh sâu.

### Phase 2: Cleaner tốt hơn cho Markdown

Mục tiêu:

- normalize Unicode nhẹ
- fix hyphen line breaks
- giữ heading/list/code block
- giảm header/footer lặp
- không phá formula/code

### Phase 3: Chunk theo Markdown/page

Mục tiêu:

- chunk giữ `pageStart/pageEnd`
- chunk giữ heading context
- chunk không cắt giữa code block/table/formula nếu có thể

### Phase 4: Visual asset extraction

Mục tiêu:

- page images
- figure/table/formula images
- manifest liên kết asset với page

Có thể dùng Docling ở phase này.

### Phase 5: Vision enrichment

Mục tiêu:

- mô tả figure/table/formula
- sinh description để embedding
- lưu description trong Markdown và chunk metadata

Nên có queue/rate limit riêng vì tốn tiền và chậm.

## Lựa chọn nên chốt bây giờ

Khuyến nghị chốt:

```text
1. Source PDF vẫn ở library-private.
2. Generated artifacts luôn ở rag-artifacts.
3. Local doc_assets chỉ là temp hoặc dev-only, không là durable storage.
4. processed_doc.md nên có.
5. pages_md/ nên có.
6. manifest.json bắt buộc nên có trước khi mở rộng asset/vision.
7. PyMuPDF4LLM là default parser.
8. Docling + vision là advanced parser mode, không bật mặc định ngay.
```

## Proposed final flow

```text
Spring Boot uploads original PDF
  -> library-private/ebooks/{bookId}/{ebookId}/original.pdf

Spring Boot calls RAG /internal/ingestions
  -> RAG creates document/job/artifact
  -> Celery worker starts

Worker validates PDF
  -> HEAD
  -> download temp
  -> checksum
  -> magic bytes
  -> pypdf structure
  -> text layer

Worker parses PDF
  -> pages_md/page_0001.md
  -> pages_md/page_0002.md

Worker cleans Markdown
  -> cleaned/page_0001.clean.md
  -> cleaned/page_0002.clean.md

Worker stitches full document
  -> processed_doc.md

Worker chunks cleaned pages
  -> chunks/chunks.jsonl
  -> document_chunks in PostgreSQL

Worker uploads artifacts
  -> rag-artifacts/documents/doc_ebook_{ebookId}/versions/v{checksumPrefix}/...

Later
  -> embed chunks
  -> upsert Qdrant
  -> status INDEXED
```

## Why this fits the library system

Hệ thống thư viện cần:

- quản lý ebook gốc an toàn
- citation theo page
- search/QA tốt với sách kỹ thuật
- retry/reprocess khi parser/cleaner/chunker thay đổi
- không mất artifact khi container restart
- có thể debug từng trang khi RAG trả lời sai

Vì vậy, pattern `doc_assets` của repo tham khảo rất đáng học, nhưng phải chuyển từ local folder cố định sang S3 artifact layout.

Tóm lại:

```text
doc_processing repo gives us the shape.
rag-artifacts bucket gives us the production storage.
pipeline.py gives us orchestration.
manifest.json gives us traceability.
```
