# Book Images and Cloudinary Response Plan

## Mục tiêu

Nâng cấp luồng ảnh bìa sách theo hướng production hơn. Frontend hiện tại chưa
render ảnh sách và chưa phụ thuộc `imageUrl`, nên có thể thiết kế contract mới
sạch hơn ngay từ đầu.

Trước đây API `GET /api/books` và `GET /api/books/{id}` từng trả `imageUrl`. Sau khi
thêm bảng `book_images`, dữ liệu ảnh thật không còn nằm trực tiếp trong
`books`, mà nên nằm ở bảng ảnh riêng:

```text
books
book_images
```

Trong đó:

- `books`: thông tin catalog của sách.
- `book_images`: metadata ảnh, provider, publicId, secureUrl, altText, primary flag.
- Cloudinary: lưu file ảnh thật.

Mục tiêu tiếp theo là trả response ảnh giàu dữ liệu hơn, với `coverImage` là
field chính frontend nên dùng:

```json
{
  "coverImage": {
    "originalUrl": "https://res.cloudinary.com/.../9780465050659.png",
    "thumbnailUrl": "https://res.cloudinary.com/.../c_fit,w_320,h_480,q_auto,f_auto/9780465050659.png",
    "detailUrl": "https://res.cloudinary.com/.../c_fit,w_800,h_1200,q_auto,f_auto/9780465050659.png",
    "altText": "Book cover for The Design of Everyday Things"
  }
}
```

`imageUrl` không còn là response field và cũng không còn là input trong
create/update book. Staff/Admin quản lý ảnh bìa bằng API multipart riêng.

## Trạng Thái Hiện Tại Trong Project

Đã có:

- `BookImage` entity.
- `ImageProvider` enum.
- `BookImageType` enum.
- `BookImageRepository`.
- Migration `V24__create_book_images_table.sql`.
- Migration `V25__seed_cloudinary_book_images_from_isbn.sql`.
- Migration `V26__make_cloudinary_book_image_urls_versionless.sql`.
- `BookSummaryResponse.coverImage`.
- `BookDetailResponse.coverImage`.
- `BookServiceImpl` đã batch load ảnh primary cho `GET /api/books`, tránh N+1.
- `BookServiceImpl` đã lấy ảnh primary cho `GET /api/books/{id}`.

Chưa có:

- `BookCoverImageResponse`.
- `BookImageMapper`.
- `BookImageUrlResolver`.
- `BookImageUrlResolverFactory`.
- `CloudinaryBookImageUrlResolver`.
- `CloudinaryImageUrlBuilder`.
- Response `coverImage`.
- API quản trị ảnh riêng.

## Nguyên Tắc Thiết Kế

### Không proxy ảnh qua Spring Boot

Frontend nên tải ảnh trực tiếp từ Cloudinary CDN.

Không nên làm:

```text
Frontend -> Spring Boot -> Cloudinary image bytes
```

Nên làm:

```text
Frontend -> Cloudinary CDN
Backend -> chỉ trả metadata URL
```

Lý do:

- giảm tải backend;
- tận dụng cache/CDN của Cloudinary;
- response API nhẹ hơn;
- dễ scale hơn.

### Không hardcode Cloudinary version

Các ảnh Cloudinary có thể có version khác nhau:

```text
v1780497401
v1780497662
...
```

Vì file ảnh đang đặt tên theo ISBN, URL nên dùng dạng versionless:

```text
https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,w_320,h_480,q_auto,f_auto/9780465050659.png
```

Không dùng dạng hardcode version:

```text
https://res.cloudinary.com/dwgv3yx7e/image/upload/v1780497401/9780465050659.png
```

### Quyết định API khi frontend chưa phụ thuộc `imageUrl`

Vì frontend chưa render ảnh và chưa dùng `imageUrl`, có thể chọn contract sạch
hơn:

```text
Frontend dùng coverImage.thumbnailUrl cho list/card
Frontend dùng coverImage.detailUrl cho detail page
```

Quyết định cho project hiện tại:

```text
Bỏ imageUrl khỏi backend response
Chỉ dùng coverImage ở frontend
Không giữ imageUrl trong create/update request; ảnh bìa đi qua API upload riêng
```

Lý do:

- frontend chưa phụ thuộc `imageUrl`, nên không có breaking change thực tế;
- response không bị duplicate dữ liệu;
- contract rõ ràng hơn: dữ liệu ảnh trả về nằm trong `coverImage`.

### Không thêm status quá sớm

Hiện tại chưa có API quản trị ảnh để xóa mềm ảnh hoặc gallery ảnh. Vì vậy chưa
cần thêm:

```text
BookImageStatus ACTIVE, DELETED
```

Khi làm API quản trị ảnh, lúc đó thêm `status` vào `book_images` sẽ hợp lý hơn.

## Kiến Trúc Đề Xuất

Luồng response ảnh:

```text
BookServiceImpl
-> BookMapper
-> BookImageMapper
-> BookImageUrlResolverFactory
-> CloudinaryBookImageUrlResolver
-> CloudinaryImageUrlBuilder
```

Ý nghĩa:

- `BookServiceImpl`: xử lý use case tìm sách và load ảnh primary theo batch.
- `BookMapper`: map `Book + BookImage` sang response.
- `BookImageMapper`: map `BookImage` sang `BookCoverImageResponse`.
- `BookImageUrlResolverFactory`: chọn resolver theo `ImageProvider`.
- `CloudinaryBookImageUrlResolver`: resolve ảnh Cloudinary cho `BookImage`.
- `CloudinaryImageUrlBuilder`: build URL Cloudinary theo size/transformation.

## Cấu Trúc File Đề Xuất

```text
src/main/java/com/vn/dto/catalog/response/
  BookCoverImageResponse.java

src/main/java/com/vn/mapper/
  BookImageMapper.java
  BookMapper.java

src/main/java/com/vn/service/image/
  BookImageUrlResolver.java
  BookImageUrlResolverFactory.java
  CloudinaryBookImageUrlResolver.java
  CloudinaryImageUrlBuilder.java
```

Không cần thêm package quá sâu. Project hiện tại đang có `mapper`, `service`,
`service.impl`, nên cấu trúc trên đủ rõ mà không làm codebase bị phân mảnh.

## Phase 1: Thêm DTO Ảnh Bìa

Tạo:

```text
src/main/java/com/vn/dto/catalog/response/BookCoverImageResponse.java
```

Nội dung đề xuất:

```java
package com.vn.dto.catalog.response;

public record BookCoverImageResponse(
        String originalUrl,
        String thumbnailUrl,
        String detailUrl,
        String altText
) {
}
```

Ý nghĩa field:

- `originalUrl`: URL gốc hoặc URL hiện đang lưu trong DB.
- `thumbnailUrl`: URL tối ưu cho book card/list.
- `detailUrl`: URL lớn hơn cho trang detail.
- `altText`: text mô tả ảnh cho accessibility.

## Phase 2: Cập Nhật Book Response

Cập nhật:

```text
BookSummaryResponse
BookDetailResponse
```

Thêm field:

```java
BookCoverImageResponse coverImage
```

Response list nên có:

```java
public record BookSummaryResponse(
        Long id,
        String title,
        String isbn,
        LocalDate publishedDate,
        String language,
        String edition,
        BookCoverImageResponse coverImage,
        Integer totalCopies,
        Integer availableCopies,
        CategoryResponse category,
        List<AuthorResponse> authors
) {
}
```

Response detail tương tự.

Create/update book không nhận `imageUrl`. Cover được quản lý bằng
`POST /api/books/{bookId}/cover` và `PUT /api/books/{bookId}/cover`.

## Phase 3: Thêm BookImageUrlResolver Interface

Tạo:

```text
src/main/java/com/vn/service/image/BookImageUrlResolver.java
```

Nội dung:

```java
package com.vn.service.image;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;

public interface BookImageUrlResolver {

    ImageProvider supports();

    BookCoverImageResponse resolve(BookImage image);
}
```

Lý do có interface:

- mapper/service không phụ thuộc trực tiếp vào Cloudinary;
- sau này thêm S3/MinIO/ImageKit chỉ cần thêm resolver mới;
- phù hợp Open/Closed Principle.

Hiện tại chỉ có `CLOUDINARY`, nhưng interface vẫn có ích vì code xử lý URL không
bị rải trong service.

## Phase 4: Thêm CloudinaryImageUrlBuilder

Tạo:

```text
src/main/java/com/vn/service/image/CloudinaryImageUrlBuilder.java
```

Nội dung đề xuất:

```java
package com.vn.service.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CloudinaryImageUrlBuilder {

    private static final String DEFAULT_FORMAT = "png";
    private static final String THUMBNAIL_TRANSFORMATION = "c_fit,w_320,h_480,q_auto,f_auto";
    private static final String DETAIL_TRANSFORMATION = "c_fit,w_800,h_1200,q_auto,f_auto";

    private final String cloudName;

    public CloudinaryImageUrlBuilder(@Value("${cloudinary.cloud-name}") String cloudName) {
        this.cloudName = cloudName;
    }

    public String thumbnailUrl(String publicId, String format) {
        return buildImageUrl(publicId, format, THUMBNAIL_TRANSFORMATION);
    }

    public String detailUrl(String publicId, String format) {
        return buildImageUrl(publicId, format, DETAIL_TRANSFORMATION);
    }

    private String buildImageUrl(String publicId, String format, String transformation) {
        if (!StringUtils.hasText(publicId)) {
            return null;
        }

        String safeFormat = StringUtils.hasText(format) ? format : DEFAULT_FORMAT;

        return "https://res.cloudinary.com/" + cloudName
                + "/image/upload/"
                + transformation + "/"
                + publicId + "." + safeFormat;
    }
}
```

Không có `VERSION`.

Lý do:

- mỗi asset có thể có version khác nhau;
- ISBN là publicId ổn định;
- Cloudinary vẫn resolve versionless URL.

## Phase 5: Thêm CloudinaryBookImageUrlResolver

Tạo:

```text
src/main/java/com/vn/service/image/CloudinaryBookImageUrlResolver.java
```

Nội dung:

```java
package com.vn.service.image;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CloudinaryBookImageUrlResolver implements BookImageUrlResolver {

    private final CloudinaryImageUrlBuilder cloudinaryImageUrlBuilder;

    @Override
    public ImageProvider supports() {
        return ImageProvider.CLOUDINARY;
    }

    @Override
    public BookCoverImageResponse resolve(BookImage image) {
        if (image == null) {
            return null;
        }

        return new BookCoverImageResponse(
                image.getSecureUrl(),
                cloudinaryImageUrlBuilder.thumbnailUrl(image.getPublicId(), image.getFormat()),
                cloudinaryImageUrlBuilder.detailUrl(image.getPublicId(), image.getFormat()),
                image.getAltText()
        );
    }
}
```

Nếu `secureUrl` trong DB đã là thumbnail URL, có hai lựa chọn:

1. Giữ `originalUrl = image.getSecureUrl()`.
2. Đổi `originalUrl` thành URL không transformation.

Khuyến nghị hiện tại: giữ `originalUrl = image.getSecureUrl()` để không phải
thêm logic mới. Khi cần rõ hơn, có thể đổi tên field thành `storedUrl`.

## Phase 6: Thêm BookImageUrlResolverFactory

Tạo:

```text
src/main/java/com/vn/service/image/BookImageUrlResolverFactory.java
```

Nội dung:

```java
package com.vn.service.image;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BookImageUrlResolverFactory {

    private final Map<ImageProvider, BookImageUrlResolver> resolverMap;

    public BookImageUrlResolverFactory(List<BookImageUrlResolver> resolvers) {
        this.resolverMap = resolvers.stream()
                .collect(Collectors.toMap(
                        BookImageUrlResolver::supports,
                        resolver -> resolver,
                        (existing, replacement) -> existing,
                        () -> new EnumMap<>(ImageProvider.class)
                ));
    }

    public BookCoverImageResponse resolve(BookImage image) {
        if (image == null) {
            return null;
        }

        BookImageUrlResolver resolver = resolverMap.get(image.getProvider());
        if (resolver == null) {
            throw new IllegalStateException("No image URL resolver found for provider: " + image.getProvider());
        }

        return resolver.resolve(image);
    }
}
```

Không throw `AppException` ở đây vì thiếu resolver là lỗi cấu hình hệ thống, không
phải lỗi request của client.

## Phase 7: Thêm BookImageMapper

Tạo:

```text
src/main/java/com/vn/mapper/BookImageMapper.java
```

Nội dung:

```java
package com.vn.mapper;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.service.image.BookImageUrlResolverFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookImageMapper {

    private final BookImageUrlResolverFactory bookImageUrlResolverFactory;

    public BookCoverImageResponse toCoverImageResponse(BookImage image) {
        return bookImageUrlResolverFactory.resolve(image);
    }
}
```

`BookImageMapper` không biết Cloudinary. Nó chỉ biết mapper ảnh qua factory.

## Phase 8: Sửa BookImageRepository

Hiện tại repository đã có:

```java
List<BookImage> findPrimaryImagesByBookIds(Collection<Long> bookIds);
Optional<BookImage> findFirstByBookIdAndPrimaryImageTrueAndStatusOrderBySortOrderAscIdAsc(
        Long bookId,
        BookImageStatus status
);
```

Nên thêm hoặc đổi theo hướng trả entity cho detail:

```java
Optional<BookImage> findFirstByBookIdAndPrimaryImageTrueAndStatusOrderBySortOrderAscIdAsc(
        Long bookId,
        BookImageStatus status
);
```

Cái này hiện đã có.

Nên giữ:

```java
List<BookImage> findPrimaryImagesByBookIds(Collection<Long> bookIds);
```

Vì API list cần batch load ảnh.

Chưa thêm status ở phase này.

## Phase 9: Sửa BookMapper

Hiện tại mapper đang nhận:

```java
toBookSummaryResponse(Book book, String imageUrl)
toBookDetailResponse(Book book, String imageUrl)
```

Nên đổi thành:

```java
toBookSummaryResponse(Book book, BookImage primaryImage)
toBookDetailResponse(Book book, BookImage primaryImage)
```

Trong mapper:

```java
BookCoverImageResponse coverImage = bookImageMapper.toCoverImageResponse(primaryImage);
```

Response chỉ trả:

```java
coverImage
```

Lợi ích:

- service không phải biết thumbnail/detail URL;
- mapper chịu trách nhiệm shape của response;
- URL generation nằm ở image resolver.

## Phase 10: Sửa BookServiceImpl

### Search books

Hiện tại:

```java
Map<Long, String> primaryImageUrlsByBookId = loadPrimaryImageUrls(books.getContent());
```

Đổi thành:

```java
Map<Long, BookImage> primaryImagesByBookId = loadPrimaryImages(books.getContent());
```

Mapping:

```java
return books.map(book -> bookMapper.toBookSummaryResponse(
        book,
        primaryImagesByBookId.get(book.getId())
));
```

### Detail book

Hiện tại detail chỉ lấy URL:

```java
getPrimaryImageUrl(book.getId())
```

Đổi thành:

```java
BookImage primaryImage = bookImageRepository
        .findFirstByBookIdAndPrimaryImageTrueOrderBySortOrderAscIdAsc(book.getId())
        .orElse(null);
```

Mapping:

```java
return bookMapper.toBookDetailResponse(book, primaryImage);
```

### Create/update book

`syncPrimaryBookImage(...)` đã được bỏ. Create/update book chỉ xử lý catalog
metadata; upload/update cover nằm trong `BookImageService`.

## Phase 11: Swagger

Cập nhật `BookApiDocs`:

```text
GET /api/books
Each book item includes:
- coverImage: structured cover image URLs for list/detail rendering
```

Create/update:

```text
POST /api/books/{bookId}/cover accepts multipart file upload.
PUT /api/books/{bookId}/cover replaces the active primary cover.
```

API docs quản trị cover cần khai báo riêng cho `POST /cover` và `PUT /cover`.

## Phase 12: Test

Nên có test tối thiểu cho:

```text
GET /api/books
GET /api/books/{id}
```

Kỳ vọng response:

```json
{
  "coverImage": {
    "originalUrl": "...",
    "thumbnailUrl": "...w_320...",
    "detailUrl": "...w_800...",
    "altText": "Book cover for ..."
  }
}
```

Test service nên kiểm:

- list API không N+1 về mặt code path: service dùng batch `findPrimaryImagesByBookIds`.
- sách không có ảnh thì `coverImage = null`.
- ảnh Cloudinary thiếu format thì default format là `png`.
- `cloudinary.cloud-name` lấy từ config.

## Phase 13: Frontend

Frontend list/card dùng:

```tsx
<img
  src={book.coverImage?.thumbnailUrl || "/images/book-placeholder.png"}
  alt={book.coverImage?.altText || book.title}
  loading="lazy"
  decoding="async"
/>
```

Frontend detail dùng:

```tsx
<img
  src={book.coverImage?.detailUrl || "/images/book-placeholder.png"}
  alt={book.coverImage?.altText || book.title}
/>
```

CSS nên dùng khung ổn định:

```css
.book-cover {
  aspect-ratio: 2 / 3;
  object-fit: contain;
}
```

Không dùng `object-fit: cover` cho bìa sách nếu không muốn cắt mất chữ.

## Phase 14: API Quản Trị Ảnh Sau Này

Chỉ làm sau khi response đọc ảnh ổn.

API đề xuất:

```http
GET    /api/books/{bookId}/images
POST   /api/books/{bookId}/images
PATCH  /api/books/{bookId}/images/{imageId}/primary
DELETE /api/books/{bookId}/images/{imageId}
```

Phân quyền:

```text
LIBRARIAN, ADMIN
```

Lúc đó nên thêm:

```text
BookImageStatus ACTIVE, DELETED
book_images.status
```

Và query primary nên lọc:

```text
is_primary = true
status = ACTIVE
```

Không nên thêm ngay trong phase hiện tại để tránh schema phình khi chưa có use
case xóa mềm ảnh.

## Những Việc Không Làm Ngay

Không làm ngay:

- Không để frontend mới phụ thuộc `imageUrl`.
- Không thêm `BookImageStatus` khi chưa có API quản trị ảnh.
- Không thêm S3/MinIO resolver khi chưa dùng.
- Không tạo nhiều DTO gần giống nhau như `BookImageResponse`, `BookCoverResponse`,
  `BookThumbnailResponse` nếu chưa có use case rõ.
- Không để frontend gọi backend để stream ảnh.
- Không hardcode Cloudinary version.

## Checklist Triển Khai

1. Tạo `BookCoverImageResponse`.
2. Thêm `coverImage` vào `BookSummaryResponse`.
3. Thêm `coverImage` vào `BookDetailResponse`.
4. Tạo `BookImageUrlResolver`.
5. Tạo `BookImageUrlResolverFactory`.
6. Tạo `CloudinaryImageUrlBuilder`.
7. Tạo `CloudinaryBookImageUrlResolver`.
8. Tạo `BookImageMapper`.
9. Sửa `BookMapper` nhận `BookImage`.
10. Sửa `BookServiceImpl` load `Map<Long, BookImage>` thay vì `Map<Long, String>`.
11. Sửa detail lấy `BookImage` primary thay vì chỉ lấy URL.
12. Bỏ `syncPrimaryBookImage`; thêm `BookImageService` cho upload/update cover.
13. Update Swagger.
14. Bỏ `imageUrl` khỏi response backend.
15. Viết test cho list/detail response.
16. Chạy `mvnw -DskipTests compile`.
17. Chạy app và kiểm tra Swagger/Postman.

## Kết Luận

Hướng phù hợp nhất với project hiện tại là:

```text
Thêm coverImage làm contract chính
Bỏ imageUrl khỏi response
Không giữ imageUrl ở create/update request
Dùng BookImage làm source metadata
Dùng BookImageUrlResolver để tách Cloudinary khỏi mapper/service
Thêm API quản trị cover sau khi read flow ổn
```

Cách này production hơn, phù hợp vì frontend chưa render ảnh, và tránh
DTO/API inflation quá sớm.
