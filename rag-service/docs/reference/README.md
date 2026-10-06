# Reference Documentation

Folder này chứa tài liệu tham chiếu kỹ thuật chung, dùng để tra cứu khi cần
biết contract/schema/metadata hiện tại.

---

## Files

### API reference

```text
api.md
```

Ghi chú API surface của RAG service.

Bao gồm ingestion/status và scoped evidence retrieval hiện tại. Endpoint
retrieval chưa tạo câu trả lời cuối cho người dùng.

### Database schema

```text
schema.md
```

Ghi chú schema hiện tại.

### Metadata structure

```text
metadata-structure-reference.md
```

Tổng hợp metadata xuyên suốt pipeline:

- parsed document metadata;
- cleaned page metadata;
- chunk metadata;
- artifact metadata;
- Qdrant payload metadata.

Đây là file nên đọc khi muốn hiểu một field metadata đến từ đâu và dùng để làm gì.
