# Phân tích hướng triển khai RAG và GraphRAG cho hệ thống quản lý thư viện

Tài liệu này phân tích nên dùng RAG vào việc gì trong project `QuanLyThuVien`,
và nếu muốn nâng lên GraphRAG thì nên làm theo hướng nào để vừa thực tế, vừa
đủ ấn tượng khi demo.

Mục tiêu không phải nhồi AI vào mọi chức năng. RAG chỉ nên dùng ở những nơi hệ
thống cần hiểu nội dung tài liệu, tìm kiếm theo ngữ nghĩa, hoặc trả lời có dẫn
nguồn. Các thao tác CRUD như thêm sách, sửa tác giả, mượn trả, thanh toán vẫn
nên để Spring Boot xử lý theo nghiệp vụ rõ ràng.

## 1. Kết luận nhanh

Use case nên ưu tiên nhất:

```text
Member/Staff hỏi đáp trên nội dung ebook PDF đã upload
 -> RAG tìm các đoạn liên quan trong PDF
 -> LLM trả lời
 -> luôn trả kèm citation: sách, trang, đoạn/chunk
```

Đây là use case phù hợp nhất với hệ thống hiện tại vì project đã có:

- Ebook PDF upload vào SeaweedFS/S3.
- Metadata ebook trong `book_ebooks`.
- RAG ingestion status: `QUEUED`, `PROCESSING`, `CHUNKED`, `INDEXED`, `FAILED`.
- RAG service tách riêng khỏi Library Spring Boot.
- Qdrant đã được định hướng cho vector search.

Sau đó mới mở rộng sang:

- Semantic search toàn thư viện.
- Gợi ý sách liên quan.
- Trợ lý học tập khi đọc ebook.
- Hỗ trợ thủ thư import/cataloging sách.
- GraphRAG dựa trên quan hệ sách, tác giả, thể loại, chủ đề, entity, review.

Không nên bắt đầu bằng full GraphRAG ngay. Hướng tốt hơn là:

```text
Phase 1: RAG thường, hỏi đáp theo từng ebook có citation.
Phase 2: Semantic search trên catalog và ebook chunks.
Phase 3: Graph-enhanced RAG dùng graph từ database thư viện.
Phase 4: Entity/topic extraction từ PDF để mở rộng graph.
Phase 5: Full GraphRAG cho truy vấn đa sách, recommendation và learning path.
```

## 2. RAG nên phục vụ việc gì trong hệ thống thư viện?

### 2.1. Hỏi đáp nội dung ebook

Đây là chức năng đáng làm nhất.

Ví dụ người dùng hỏi:

```text
Trong sách Clean Code, tác giả nói gì về đặt tên biến?
```

Hoặc:

```text
Tóm tắt chương nói về transaction trong cuốn này.
```

Flow:

```text
User đang có quyền đọc ebook
 -> hỏi câu hỏi trong reader
 -> Library kiểm tra quyền loan/payment/session
 -> Library gọi RAG query API
 -> RAG search trong chunks của ebook đó
 -> LLM trả lời kèm citation
```

Giá trị:

- Gắn trực tiếp với ebook PDF đã upload.
- Dễ demo.
- Có ranh giới bảo mật rõ: chỉ hỏi được ebook người dùng có quyền đọc.
- Có thể chứng minh hệ thống không chỉ lưu file, mà còn hiểu nội dung file.

Response nên có:

```json
{
  "answer": "Nội dung trả lời...",
  "citations": [
    {
      "bookId": 101,
      "ebookId": 55,
      "pageStart": 12,
      "pageEnd": 13,
      "chunkId": "chunk_abc",
      "score": 0.83
    }
  ]
}
```

Điểm bắt buộc: không trả lời kiểu chung chung mà không có nguồn. Nếu không tìm
được đoạn đủ liên quan, nên trả:

```text
Không tìm thấy nội dung đủ liên quan trong tài liệu đã cấp quyền.
```

### 2.2. Semantic search toàn thư viện

Search hiện tại của hệ thống thường dựa trên keyword như title, author,
category. RAG/vector search giúp tìm theo ý nghĩa.

Ví dụ:

```text
Tìm sách nhập môn về thiết kế hệ thống backend.
```

Hoặc:

```text
Tôi muốn tài liệu về quản lý giao dịch và concurrency trong Java.
```

Kết quả không chỉ dựa vào title chứa chữ `Java` hay `concurrency`, mà dựa trên:

- Tóm tắt sách.
- Nội dung ebook đã được index.
- Category.
- Author.
- Review.
- Tags/chủ đề trích xuất.

Search này có thể chia thành 2 chế độ:

```text
Public semantic catalog search:
  dùng metadata công khai: title, description, author, category, review summary.

Protected ebook content search:
  dùng nội dung PDF, chỉ trả kết quả nếu user có quyền truy cập ebook đó.
```

### 2.3. Reader assistant trong màn hình đọc ebook

Khi member đang đọc ebook, có thể thêm panel trợ lý:

- Hỏi nội dung trong cuốn đang đọc.
- Tóm tắt đoạn/chương hiện tại.
- Giải thích thuật ngữ.
- Tạo câu hỏi ôn tập.
- Gợi ý phần liên quan trong cùng ebook.

Use case này đẹp vì nó nằm đúng ngữ cảnh: người dùng đang đọc PDF, nên RAG có
thể giới hạn retrieval theo `bookId`, `ebookId`, `pageStart`, `pageEnd`.

Ví dụ:

```text
Giải thích đoạn này dễ hiểu hơn.
```

Frontend gửi thêm context:

```json
{
  "bookId": 101,
  "ebookId": 55,
  "currentPage": 48,
  "question": "Giải thích đoạn này dễ hiểu hơn"
}
```

RAG có thể ưu tiên chunks quanh trang 48 trước, sau đó mới mở rộng ra toàn ebook.

### 2.4. Hỗ trợ thủ thư cataloging và import

Khi upload PDF hoặc import metadata sách, RAG có thể hỗ trợ thủ thư:

- Trích xuất mục lục.
- Gợi ý mô tả sách.
- Gợi ý category.
- Gợi ý keywords/tags.
- Phát hiện sách trùng hoặc gần trùng.
- Tóm tắt nội dung cho trang catalog.

Ví dụ sau khi ebook được `INDEXED`, hệ thống có thể có nút:

```text
Generate catalog suggestions
```

Response:

```json
{
  "suggestedDescription": "...",
  "suggestedCategories": ["Software Engineering", "Programming"],
  "suggestedTags": ["clean code", "refactoring", "naming"],
  "confidence": 0.78
}
```

Lưu ý: không nên để AI tự động ghi đè catalog. Nên để thủ thư review rồi bấm
confirm. Đây là điểm rất thực tế trong hệ thống quản lý thư viện.

### 2.5. Gợi ý sách liên quan

RAG có thể giúp tạo recommendation tốt hơn keyword search.

Ví dụ:

```text
Nếu thích sách này, nên đọc tiếp sách nào?
```

Nguồn tín hiệu:

- Nội dung ebook giống nhau theo embedding.
- Cùng author.
- Cùng category.
- Cùng chủ đề trích xuất.
- Review của member.
- Lịch sử mượn, nếu dùng thì phải ẩn danh hoặc xử lý cẩn thận về privacy.

Recommendation nên là hybrid:

```text
metadata score + graph score + vector similarity + popularity/availability
```

Không nên dùng LLM để tự bịa recommendation. LLM chỉ nên giải thích vì sao sách
được gợi ý dựa trên dữ liệu đã retrieve.

## 3. GraphRAG là gì trong bối cảnh project này?

Trong project này, nên hiểu GraphRAG là:

```text
RAG có thêm bước truy vấn graph quan hệ trước hoặc trong lúc vector retrieval.
```

RAG thường:

```text
Question
 -> embed question
 -> vector search chunks
 -> LLM answer
```

Graph-enhanced RAG:

```text
Question
 -> nhận diện entity/chủ đề/sách/tác giả
 -> truy vấn graph để lấy vùng dữ liệu liên quan
 -> dùng graph để filter/rerank vector search
 -> LLM answer có citation và lý do liên kết
```

Graph ở đây không nhất thiết phải là Neo4j ngay từ đầu. Project có thể bắt đầu
bằng graph lưu trong PostgreSQL, vì hệ thống đã có sẵn quan hệ rất rõ:

- Book thuộc Category.
- Book có Author.
- Book có Ebook.
- Ebook có Document/Chunks trong RAG.
- Chunk mention Topic/Entity.
- Member mượn Book/Ebook.
- Member review Book.
- Book tương tự Book theo vector hoặc graph.

## 4. Graph nên chứa những node và edge nào?

### 4.1. Node đề xuất

```text
BOOK
AUTHOR
CATEGORY
EBOOK
DOCUMENT
CHUNK
TOPIC
ENTITY
MEMBER_PROFILE
REVIEW
```

Không nhất thiết làm hết trong MVP. Nên ưu tiên:

```text
BOOK
AUTHOR
CATEGORY
EBOOK
DOCUMENT
CHUNK
TOPIC
ENTITY
```

`MEMBER_PROFILE` và lịch sử mượn nên để sau vì liên quan privacy.

### 4.2. Edge đề xuất

```text
AUTHOR -WROTE-> BOOK
BOOK -BELONGS_TO-> CATEGORY
BOOK -HAS_EBOOK-> EBOOK
EBOOK -HAS_DOCUMENT-> DOCUMENT
DOCUMENT -HAS_CHUNK-> CHUNK
CHUNK -MENTIONS-> ENTITY
CHUNK -ABOUT-> TOPIC
BOOK -SIMILAR_TO-> BOOK
BOOK -RECOMMENDED_AFTER-> BOOK
MEMBER_PROFILE -BORROWED-> BOOK
MEMBER_PROFILE -REVIEWED-> BOOK
```

Trong MVP, nhiều edge có thể lấy trực tiếp từ database Library:

- `AUTHOR -WROTE-> BOOK`: từ bảng quan hệ book-author.
- `BOOK -BELONGS_TO-> CATEGORY`: từ `books.category_id`.
- `BOOK -HAS_EBOOK-> EBOOK`: từ `book_ebooks`.
- `DOCUMENT -HAS_CHUNK-> CHUNK`: từ RAG ingestion.

Sau này mới cần LLM/NLP để sinh:

- `CHUNK -MENTIONS-> ENTITY`
- `CHUNK -ABOUT-> TOPIC`
- `BOOK -SIMILAR_TO-> BOOK`

## 5. Nên dùng graph database không?

Không cần ở giai đoạn đầu.

Với đồ án hiện tại, hướng thực tế hơn:

```text
PostgreSQL Library:
  source of truth cho sách, tác giả, category, loan, review.

PostgreSQL RAG:
  document, chunk, ingestion job, entity/topic/edge đơn giản.

Qdrant:
  vector index cho chunks và có thể cả book summary.
```

Chỉ nên thêm Neo4j khi:

- Cần demo graph query trực quan.
- Cần nhiều truy vấn multi-hop phức tạp.
- Cần visualize mạng lưới sách, tác giả, chủ đề.
- Có đủ thời gian vận hành thêm một service nữa.

Nếu thêm Neo4j quá sớm, hệ thống sẽ nặng hơn nhưng chưa chắc chất lượng RAG tốt
hơn. Nhà tuyển dụng thường đánh giá cao kiến trúc có trade-off rõ hơn là thêm
nhiều công nghệ nhưng không giải quyết đúng bài toán.

## 6. Kiến trúc đề xuất

### 6.1. Ranh giới trách nhiệm

Library Spring Boot:

- Quản lý user, auth, role.
- Quản lý catalog, author, category, book copy.
- Quản lý ebook loan/payment/reader session.
- Upload PDF vào SeaweedFS/S3.
- Lưu metadata ebook.
- Kiểm tra quyền truy cập trước khi gọi RAG.
- Không parse PDF, không chunk, không embed, không upsert Qdrant.

RAG service:

- Đọc PDF từ SeaweedFS/S3.
- Parse, clean, chunk.
- Embed chunks.
- Upsert Qdrant.
- Lưu chunks/citations/index status.
- Extract entity/topic nếu có.
- Query retrieval.
- Gọi LLM để tạo answer.

### 6.2. Ingestion flow

```text
Staff upload PDF
 -> Library validate PDF
 -> Library upload object:
      bucket: library-private
      key: ebooks/{bookId}/{ebookId}/original.pdf
 -> Library lưu metadata vào book_ebooks
 -> Library gọi RAG /internal/ingestions
 -> RAG tạo job QUEUED
 -> Worker parse PDF
 -> Worker chunk text kèm page numbers
 -> Worker embed chunks
 -> Worker upsert Qdrant
 -> Worker build graph metadata cơ bản
 -> Job chuyển INDEXED
```

Status nên hiểu như sau:

```text
CHUNKED:
  đã parse và chia đoạn xong, nhưng chưa chắc search vector được.

INDEXED:
  đã embed và upsert Qdrant xong, có thể query RAG.
```

### 6.3. Query flow cho hỏi đáp ebook

```text
Member hỏi trong ebook reader
 -> Frontend gọi Library API
 -> Library kiểm tra member có ebook loan/session hợp lệ
 -> Library gọi RAG query internal API với scope:
      allowedBookIds
      allowedEbookIds
      query
      currentPage nếu có
 -> RAG chỉ retrieve trong scope được cấp
 -> RAG trả answer + citations
 -> Library trả về frontend
```

Không nên để frontend gọi thẳng RAG service, vì RAG không nên tự xử lý toàn bộ
auth/business rule của Library.

### 6.4. Query flow cho semantic catalog search

```text
User search catalog
 -> Library nhận query
 -> Library gọi RAG semantic search với mode PUBLIC_CATALOG
 -> RAG search trên book summary/title/category/public metadata
 -> Library load lại book detail từ DB
 -> Frontend hiển thị kết quả như search bình thường
```

Với nội dung PDF protected, chỉ đưa vào search nếu user có quyền.

## 7. API gợi ý

### 7.1. Hỏi đáp trong một ebook

```http
POST /api/ebooks/{bookId}/rag/ask
Authorization: Bearer <token>
Content-Type: application/json
```

Payload:

```json
{
  "ebookId": 55,
  "question": "Tác giả giải thích dependency inversion như thế nào?",
  "currentPage": 120
}
```

Response:

```json
{
  "answer": "...",
  "citations": [
    {
      "bookId": 101,
      "ebookId": 55,
      "pageStart": 120,
      "pageEnd": 122,
      "chunkId": "doc_ebook_55_chunk_88",
      "snippet": "..."
    }
  ],
  "status": "ANSWERED"
}
```

### 7.2. Semantic catalog search

```http
POST /api/books/semantic-search
Authorization: Bearer <token optional>
Content-Type: application/json
```

Payload:

```json
{
  "query": "sách nhập môn về thiết kế backend có ví dụ Java",
  "page": 0,
  "size": 10
}
```

Response nên trả về book IDs và lý do match:

```json
{
  "items": [
    {
      "bookId": 101,
      "score": 0.84,
      "matchedReason": "Nội dung có nhiều đoạn về backend architecture và Java."
    }
  ]
}
```

### 7.3. Gợi ý sách liên quan

```http
GET /api/books/{bookId}/related?mode=semantic
```

Có thể trả:

```json
{
  "items": [
    {
      "bookId": 205,
      "reason": "Cùng chủ đề refactoring và có nhiều chunk tương tự về code smell."
    }
  ]
}
```

## 8. Thiết kế dữ liệu bên RAG

Nếu chưa dùng Neo4j, có thể lưu graph đơn giản trong RAG PostgreSQL.

### 8.1. Bảng document và chunk

```text
rag_documents
  id
  document_id
  book_id
  ebook_id
  bucket
  object_key
  checksum_sha256
  status
  created_at
  updated_at

rag_chunks
  id
  document_id
  chunk_index
  page_start
  page_end
  text
  cleaned_text
  token_count
  qdrant_point_id
  created_at
```

### 8.2. Bảng entity/topic

```text
rag_entities
  id
  type
  name
  normalized_name
  source
  created_at

rag_chunk_entities
  chunk_id
  entity_id
  confidence
  extraction_method
```

Entity type ví dụ:

```text
PERSON
ORGANIZATION
TECHNOLOGY
CONCEPT
PLACE
TIME_PERIOD
```

Topic ví dụ:

```text
software architecture
database transaction
clean code
library management
machine learning
```

### 8.3. Bảng graph edge tổng quát

```text
rag_graph_edges
  id
  from_node_type
  from_node_id
  to_node_type
  to_node_id
  edge_type
  weight
  source
  created_at
```

Ví dụ:

```text
BOOK:101 -HAS_TOPIC-> TOPIC:clean_code
CHUNK:8801 -MENTIONS-> ENTITY:dependency_inversion
BOOK:101 -SIMILAR_TO-> BOOK:205
AUTHOR:12 -WROTE-> BOOK:101
```

## 9. GraphRAG retrieval strategy

### 9.1. Với câu hỏi trong một ebook

Không cần graph phức tạp.

```text
Filter theo bookId/ebookId
 -> vector search chunks
 -> rerank
 -> answer with citations
```

Graph chỉ dùng thêm nếu user hỏi về entity/chủ đề:

```text
"Trong sách này các khái niệm liên quan đến transaction là gì?"
```

Khi đó:

```text
Extract topic "transaction"
 -> lấy chunks ABOUT/MENTIONS transaction
 -> vector search trong nhóm chunks đó
 -> answer
```

### 9.2. Với search toàn thư viện

```text
Query
 -> detect author/category/topic nếu có
 -> graph expand candidate books
 -> vector search trong candidate books
 -> rerank theo graph + vector + availability
 -> trả book list
```

Ví dụ query:

```text
sách của Martin Fowler về refactoring và kiến trúc phần mềm
```

Graph giúp nhận ra:

- `Martin Fowler` là author/entity.
- `refactoring` là topic.
- Candidate books nên ưu tiên sách có author hoặc topic liên quan.

Vector search giúp tìm nội dung đúng ngữ nghĩa, kể cả title không chứa đầy đủ
từ khóa.

### 9.3. Với recommendation

```text
Book hiện tại
 -> lấy author/category/topic/entity
 -> lấy books lân cận trong graph
 -> tính vector similarity giữa book summaries/chunks
 -> rerank theo rating/availability/access type
 -> trả related books
```

Điểm nên có khi demo:

```text
Vì sao gợi ý sách này?
```

Ví dụ:

```text
Sách này được gợi ý vì cùng chủ đề "refactoring", có nhiều đoạn nội dung gần
với chương về code smell, và thuộc cùng nhóm Software Engineering.
```

## 10. Bảo mật và phân quyền

Đây là phần rất quan trọng.

Không được để RAG trả nội dung PDF protected cho user chưa có quyền đọc.

Nguyên tắc:

```text
Library kiểm tra quyền trước.
RAG chỉ nhận scope đã được Library cấp.
RAG retrieval luôn filter theo scope đó.
```

Scope ví dụ:

```json
{
  "memberId": 501,
  "role": "MEMBER",
  "allowedBookIds": [101],
  "allowedEbookIds": [55],
  "mode": "EBOOK_QA"
}
```

Với staff/admin:

```json
{
  "role": "LIBRARIAN",
  "mode": "STAFF_CATALOG_ASSISTANT"
}
```

Không nên đưa dữ liệu nhạy cảm vào graph nếu chưa có lý do rõ:

- Lịch sử mượn cá nhân.
- Email/số điện thoại member.
- Payment data.
- Nội dung review riêng tư nếu sau này có private review.

Nếu dùng lịch sử mượn cho recommendation, nên aggregate hoặc ẩn danh.

## 11. Frontend nên thể hiện như thế nào?

### 11.1. Trong ebook reader

Thêm side panel:

```text
Ask this ebook
```

UI nên có:

- Ô nhập câu hỏi.
- Loading state.
- Answer.
- Citation list.
- Click citation để nhảy tới trang PDF tương ứng.

Không nên chỉ hiện một đoạn chat chung chung. Citation là thứ làm tính năng này
trông đáng tin.

### 11.2. Trong catalog search

Thêm tab hoặc toggle:

```text
Keyword | Semantic
```

Khi dùng semantic search, mỗi kết quả nên có:

- Book title.
- Author.
- Category.
- Matched reason.
- Nếu có quyền, hiện đoạn trích từ ebook.
- Nếu không có quyền, chỉ hiện metadata công khai.

### 11.3. Trong màn hình staff quản lý ebook

Hiện rõ indexing status:

```text
Upload completed
RAG: QUEUED / PROCESSING / CHUNKED / INDEXED / FAILED
```

Nếu `FAILED`, cho staff:

- Xem lỗi.
- Retry ingestion.
- Upload lại file.

### 11.4. Trong trang detail sách

Có thể thêm section:

```text
Related books
Topics in this book
Ask about this ebook
```

Nhưng chỉ bật `Ask about this ebook` khi:

- Ebook đã `INDEXED`.
- User có quyền đọc.

## 12. Lộ trình triển khai đề xuất

### Phase 0: Hoàn tất ingestion đến INDEXED

Mục tiêu:

```text
Upload PDF -> parse -> chunk -> embed -> upsert Qdrant -> status INDEXED
```

Done khi:

- RAG không dừng ở `CHUNKED`.
- Qdrant có point cho từng chunk.
- Mỗi chunk có `bookId`, `ebookId`, `pageStart`, `pageEnd`, `chunkIndex`.
- Có thể test vector search nội bộ.

### Phase 1: Ebook-scoped RAG QA

Mục tiêu:

```text
Member hỏi trên một ebook đã mượn và nhận answer có citation.
```

API:

```text
POST /api/ebooks/{bookId}/rag/ask
```

Done khi:

- Library kiểm tra quyền loan/session.
- RAG chỉ retrieve trong ebook được cấp quyền.
- Response có citation.
- Nếu không có citation đủ tốt, không bịa câu trả lời.

Đây là phase nên ưu tiên nhất để demo.

### Phase 2: Semantic catalog search

Mục tiêu:

```text
Search sách theo ý nghĩa, không chỉ keyword.
```

Done khi:

- Search được theo mô tả tự nhiên.
- Kết quả trả về book list từ Library DB.
- Có matched reason.
- Nội dung PDF protected vẫn được filter theo quyền.

### Phase 3: Graph từ database Library

Mục tiêu:

```text
Tạo graph cơ bản từ dữ liệu đáng tin trong Library DB.
```

Nguồn graph:

- Books.
- Authors.
- Categories.
- Book ebooks.
- Reviews nếu public.

Done khi:

- Có edge author-book/category-book/book-ebook.
- Semantic search có thể dùng graph để filter/rerank.
- Related books giải thích được dựa trên graph.

### Phase 4: Entity/topic extraction từ PDF

Mục tiêu:

```text
RAG tự rút ra topic/entity từ chunks để làm graph giàu hơn.
```

Done khi:

- Mỗi chunk có topic/entity.
- Có thể hỏi các câu như:

```text
Trong thư viện có những sách nào nói về outbox pattern?
```

- Có thể hiện topic cloud trong trang detail sách.

### Phase 5: Full GraphRAG

Mục tiêu:

```text
Trả lời câu hỏi đa sách, đa chủ đề, có reasoning qua graph và citation.
```

Ví dụ:

```text
So sánh cách 3 cuốn sách trong thư viện giải thích dependency inversion.
```

Hoặc:

```text
Tạo lộ trình học Spring Security từ cơ bản đến nâng cao dựa trên sách hiện có.
```

Done khi:

- Query planner biết tách câu hỏi thành entity/topic/book constraints.
- Graph retrieval lấy candidate books/chunks.
- Vector retrieval lấy đoạn cụ thể.
- Answer có citations từ nhiều sách.
- Có guardrail không dùng sách user không có quyền đọc.

## 13. Message queue có cần ngay không?

Chưa cần trước khi RAG ổn định.

Thứ tự hợp lý:

```text
1. Hoàn tất RAG ingestion + query.
2. Chứng minh flow upload PDF -> INDEXED -> ask được.
3. Sau đó thêm message queue/outbox để ingestion bền hơn.
```

Khi thêm queue, RabbitMQ là lựa chọn phù hợp cho đồ án Spring Boot:

- Dễ giải thích.
- Dễ demo producer/consumer.
- Phù hợp với background job như RAG ingestion, email, notification.

Event gợi ý:

```text
EBOOK_UPLOADED
EBOOK_INDEX_REQUESTED
EBOOK_INDEXED
EBOOK_INDEX_FAILED
CATALOG_UPDATED
```

Nhưng trong phase hiện tại, nếu RAG chưa query tốt thì queue chưa tạo nhiều giá
trị cho người dùng cuối.

## 14. Đánh giá chất lượng RAG

Nên có bộ test câu hỏi mẫu.

Ví dụ:

```text
bookId=101
question="Tác giả nói gì về đặt tên biến?"
expectedPages=[12,13,14]
```

Các tiêu chí:

- Có retrieve đúng đoạn không?
- Citation có đúng trang không?
- Answer có dựa trên citation không?
- Khi câu hỏi ngoài nội dung sách, có từ chối đúng không?
- User không có quyền có bị chặn không?

Metrics đơn giản:

```text
retrieval_hit_rate
answer_has_citation_rate
no_answer_when_context_missing_rate
access_control_pass_rate
average_latency_ms
```

Đây là phần rất đáng đưa vào báo cáo vì cho thấy hệ thống AI được kiểm chứng,
không chỉ gọi model rồi hi vọng đúng.

## 15. Những thứ không nên làm

Không nên:

- Cho frontend gọi trực tiếp RAG service.
- Cho RAG bỏ qua quyền truy cập ebook.
- Lưu full answer AI vào database như source of truth.
- Dùng LLM để quyết định nghiệp vụ mượn/trả/thanh toán.
- Làm Neo4j ngay nếu chưa có RAG cơ bản chạy tốt.
- Trả lời không citation.
- Dùng nội dung PDF protected cho public search khi user chưa có quyền.
- Nhồi lịch sử mượn cá nhân vào graph recommendation mà chưa xử lý privacy.

## 16. Đề xuất tên tính năng khi demo

Có thể đặt tên theo hướng:

```text
AI Library Research Assistant
```

Bao gồm:

- Ask this ebook.
- Semantic library search.
- Related books by topic.
- Staff catalog suggestions.
- Graph-based topic explorer.

Nếu muốn nhấn mạnh GraphRAG:

```text
GraphRAG-powered Library Assistant
```

Nhưng trong báo cáo nên ghi rõ:

```text
Hệ thống triển khai theo hướng graph-enhanced RAG từng bước. Graph ban đầu lấy
từ quan hệ đáng tin trong database thư viện, sau đó mới mở rộng bằng entity và
topic extraction từ nội dung PDF.
```

## 17. Roadmap ngắn gọn để làm trong đồ án

Nếu thời gian ít, nên làm:

```text
1. PDF upload S3.
2. RAG ingestion đến INDEXED.
3. Ebook Q&A có citation.
4. Semantic catalog search.
5. Related books dùng author/category + vector similarity.
```

Nếu còn thời gian, thêm:

```text
6. Extract topic/entity từ chunks.
7. Lưu graph edge trong RAG PostgreSQL.
8. GraphRAG query cho câu hỏi đa sách.
9. UI graph topic explorer.
10. RabbitMQ/outbox cho ingestion event.
```

Thứ gây ấn tượng nhất không phải là nói "có GraphRAG", mà là demo được một flow
hoàn chỉnh:

```text
Upload PDF
 -> hệ thống tự index
 -> member hỏi nội dung sách
 -> câu trả lời có trích dẫn đúng trang
 -> search/gợi ý sách liên quan dựa trên nội dung và quan hệ catalog
```

Đó là hướng vừa đúng nghiệp vụ thư viện, vừa cho thấy backend, storage, async
processing, vector search, access control và AI được ghép lại thành một hệ thống
có ý nghĩa.
