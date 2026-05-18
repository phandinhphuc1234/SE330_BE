# Flow 1 - Search Catalogue Và Availability

## Mục tiêu nghiệp vụ

Bạn đọc cần tìm sách và biết ngay:

- Sách có trong thư viện không.
- Còn bản copy nào `AVAILABLE` không.
- Nếu còn, vị trí kệ ở đâu.
- Nếu hết, có được đặt hold không.

Theo rule của project:

```text
Còn ít nhất 1 copy AVAILABLE -> không cho hold online.
Không còn copy AVAILABLE -> MEMBER được đặt hold.
```

## Code hiện tại đã có gì

Hiện tại đã có:

- `GET /api/books`
- `GET /api/books/{bookId}`
- `BookServiceImpl.searchBooks(...)`
- `BookRepository extends JpaSpecificationExecutor<Book>`
- `BookSummaryResponse.totalCopies`
- `BookSummaryResponse.availableCopies`
- `BookCopy.location`

Search đang dùng `Specification`, phù hợp với docs Spring Data JPA vì `Specification` giúp compose nhiều predicate mà không phải viết một method repository cho từng tổ hợp filter.

Điểm còn thiếu:

- Response chưa có `canPlaceHold`.
- Response chưa có `queueLength`.
- Detail response chưa trả danh sách copy public-safe.
- Book detail hiện không include `copies`, dù `BookCopyController` có API lấy copies cho staff.

## API đề xuất

Giữ endpoint hiện tại:

```http
GET /api/books?q=clean&availableOnly=true&page=0&size=10
GET /api/books/{bookId}
```

Không cần tạo endpoint search mới.

Nên bổ sung field vào response:

```java
public record BookAvailabilityResponse(
        Integer totalCopies,
        Integer availableCopies,
        Boolean canPlaceHold,
        Integer queueLength,
        String message
) {
}
```

Sau đó `BookSummaryResponse` có thể thêm:

```java
BookAvailabilityResponse availability
```

Hoặc để đơn giản hơn:

```java
Boolean canPlaceHold
Integer queueLength
String availabilityMessage
```

## Logic service

Rule:

```java
boolean canPlaceHold = book.getAvailableCopies() == 0 && book.getTotalCopies() > 0;
```

Nếu muốn chính xác hơn khi counter có thể lệch, có thể query:

```java
long availableCopies = bookCopyRepository.countByBookIdAndStatusAndDeletedAtIsNull(
        bookId,
        BookCopyStatus.AVAILABLE
);
```

Nhưng với hệ thống hiện tại, `books.available_copies` đã được maintain khi tạo/xóa copy. Khi implement borrow/return, phải tiếp tục update counter bằng delta để tránh count toàn bảng liên tục.

## Vấn đề hiệu năng

Không nên với mỗi book trong page lại query:

```text
count copies by bookId
count holds by bookId
```

Vì page 20 books có thể thành 40 query phụ.

Hướng vừa sức:

1. Giai đoạn đầu: dùng counter `books.total_copies`, `books.available_copies`.
2. Khi có hold: thêm query batch đếm queue cho nhiều bookId.
3. Sau này nếu cần: projection query riêng cho search.

Ví dụ batch queue:

```java
@Query("""
       select r.book.id, count(r.id)
       from Reservation r
       where r.book.id in :bookIds
         and r.status in :activeStatuses
       group by r.book.id
       """)
List<Object[]> countActiveHoldsByBookIds(Collection<Long> bookIds, Collection<ReservationStatus> activeStatuses);
```

## DB/index cần quan tâm

Hiện DB đã có:

- `idx_books_fulltext`
- `idx_books_active`
- `idx_book_copies_book`
- `idx_book_copies_status`
- `idx_reservation_active`

Với PostgreSQL, partial index hợp lý cho dữ liệu active vì nó chỉ index một phần bảng. Docs PostgreSQL cũng nói partial index hữu ích khi query chỉ quan tâm một subset nhất định.

Nên có thêm nếu implement hold/search theo active records:

```sql
CREATE INDEX idx_book_copies_available
    ON book_copies(book_id)
    WHERE status = 'AVAILABLE' AND deleted_at IS NULL;
```

## Bảo mật

Search catalogue là public read:

```text
GET /api/books
GET /api/books/{bookId}
```

Nhưng copy detail public không nên lộ quá nhiều thông tin vận hành. Có thể hiển thị:

- `status`
- `location`

Không nhất thiết lộ internal `copyId` nếu frontend public không cần.

Staff vẫn có:

```http
GET /api/books/{bookId}/copies
```

## Test nên có

- Search `q` theo title trả đúng book.
- Search `availableOnly=true` chỉ trả sách có `availableCopies > 0`.
- Book có `availableCopies = 0` thì `canPlaceHold = true`.
- Book có `availableCopies > 0` thì `canPlaceHold = false`.
- Page size vượt 100 bị clamp như hiện tại.

## Nguồn kỹ thuật

- Spring Data JPA Specifications: https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html
- PostgreSQL partial indexes: https://www.postgresql.org/docs/current/indexes-partial.html

