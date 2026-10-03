# Parsing & Cleaning Documentation

Folder này gom các tài liệu về giai đoạn:

```text
PDF từ S3
  -> parser
  -> raw pages / markdown pages
  -> cleaner
  -> cleaned pages
  -> quality report
  -> chuyển sang chunking
```

Đây là phần đứng trước `docs/chunking/`.

---

## Thứ tự đọc đề xuất

### 1. Kiến trúc parser

```text
parser-architecture.md
```

Giải thích cách tổ chức parser theo hướng dễ thay đổi:

- PyMuPDF4LLM hiện tại;
- Docling server sau này;
- parser interface;
- parser registry;
- parsed page metadata.

### 2. Strategy lưu asset/artifact khi xử lý PDF

```text
pdf-processing-assets-strategy.md
```

Giải thích nên lưu gì vào object storage:

- parsed pages;
- cleaned pages;
- processed markdown;
- quality report;
- failed logs;
- asset sinh ra trong quá trình xử lý.

### 3. Tổng quan cấu trúc clean

```text
pdf-cleaning-structure-overview.md
```

Giải thích phần clean làm gì ở mức dễ hình dung:

- normalize unicode;
- normalize whitespace;
- remove page number;
- detect/remove repeated header/footer;
- fix hyphenation;
- fix paragraph line break;
- classify line metadata.

### 4. Danh sách ưu tiên công việc clean

```text
pdf-cleaning-priority-work-items.md
```

Ghi rõ việc nào nên làm trước, việc nào để sau.

### 5. Plan implement từng bước

```text
pdf-cleaning-stepwise-implementation-plan.md
```

Ghi theo step để implement từng phần nhỏ, tránh làm ồ ạt.

---

## Quan hệ với chunking

Parsing/cleaning tạo ra:

```text
Cleaned LlamaIndex Documents
```

Chunking nhận đầu vào đó và tạo:

```text
LlamaIndex Nodes
  -> internal Chunks
```

Vì vậy sau khi đọc folder này, đọc tiếp:

```text
docs/chunking/README.md
```

