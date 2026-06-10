# Book Cover URL Handling

## Mục Tiêu

Tài liệu này giải thích cách backend xử lý URL ảnh bìa sách.

Ý tưởng chính:

```text
Cloudinary lưu file ảnh thật.
Database lưu metadata ảnh.
Backend dùng Cloudinary Java SDK để upload/delete và build URL hiển thị.
Frontend chỉ render URL backend trả về.
```

Frontend không cần biết cách ghép URL Cloudinary, transformation, publicId hay version.

## Vì Sao Không Lưu Mỗi `imageUrl`

Cách đơn giản là lưu thẳng một URL:

```text
https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,w_320,h_480,q_auto,f_auto/9780132350884.png
```

Cách này chạy được nhưng không tốt vì URL trên đã gắn với một kiểu hiển thị cụ thể:

```text
c_fit,w_320,h_480,q_auto,f_auto
```

Nghĩa là URL đó phù hợp cho thumbnail, nhưng không chắc phù hợp cho trang detail, mobile, admin page hoặc ảnh lớn.

Vì vậy backend không xem URL transform là dữ liệu chính.

Dữ liệu chính nên là metadata:

```text
publicId = book-covers/book-3000/cover-550e8400...
format = png
secureUrl = URL gốc Cloudinary trả về
width
height
sizeBytes
version
status
```

Từ metadata này, backend có thể build nhiều URL khác nhau.

## Dữ Liệu Lưu Trong Database

Ảnh bìa sách được lưu trong bảng:

```text
book_images
```

Các field quan trọng:

```text
book_id      -> ảnh thuộc sách nào
provider     -> CLOUDINARY
public_id    -> định danh asset trên Cloudinary
secure_url   -> URL HTTPS Cloudinary trả về sau upload
format       -> png/jpg/webp
width        -> chiều rộng ảnh
height       -> chiều cao ảnh
size_bytes   -> dung lượng file
is_primary   -> có phải ảnh bìa chính không
status       -> ACTIVE, DELETE_PENDING, PURGED...
```

Với frontend, field quan trọng nhất là ảnh `ACTIVE` và `is_primary = true`.

## Luồng Upload Cover

API:

```http
POST /api/books/{bookId}/cover
PUT /api/books/{bookId}/cover
```

Luồng xử lý:

```text
1. Staff/Admin gửi file ảnh lên backend.
2. BookImageService validate file:
   - không rỗng
   - JPG/PNG/WEBP
   - tối đa 5MB
3. BookImageService sinh publicId.
4. MediaStorageService gọi Cloudinary Java SDK để upload file lên Cloudinary.
5. Cloudinary trả metadata.
6. Backend lưu metadata vào book_images.
7. Backend trả response có URL đã build sẵn cho frontend.
```

Ví dụ publicId được sinh:

```text
book-covers/book-3000/cover-550e8400-...
```

Không dùng lại publicId cũ khi thay ảnh, vì CDN có thể cache ảnh cũ.

## Luồng Update Cover An Toàn

Khi thay ảnh bìa, backend không xóa ảnh cũ trước.

Luồng đúng:

```text
1. Upload ảnh mới lên Cloudinary.
2. Nếu upload thành công, mở transaction DB.
3. Đánh dấu ảnh cũ:
   is_primary = false
   status = DELETE_PENDING
4. Lưu ảnh mới:
   is_primary = true
   status = ACTIVE
5. Commit DB.
6. Sau commit mới thử xóa ảnh cũ khỏi Cloudinary.
7. Nếu xóa thất bại, giữ DELETE_PENDING để cleanup job retry sau.
```

Lý do:

```text
Nếu xóa ảnh cũ trước rồi upload ảnh mới thất bại,
sách sẽ mất ảnh bìa.
```

## Backend Build URL Như Thế Nào

Frontend nhận field:

```json
{
  "coverImage": {
    "originalUrl": "...",
    "thumbnailUrl": "...",
    "detailUrl": "...",
    "altText": "Book cover for Clean Code"
  }
}
```

Các URL này được build từ:

```text
publicId
format
cloudinary.cloud-name
```

Class chịu trách nhiệm:

```text
BookImageMapper
-> BookImageUrlResolverFactory
-> CloudinaryBookImageUrlResolver
-> CloudinaryImageUrlBuilder
```

Vai trò từng class:

```text
BookImageMapper
Map BookImage entity sang response.

BookImageUrlResolverFactory
Chọn resolver theo provider. Hiện tại provider là CLOUDINARY.

CloudinaryBookImageUrlResolver
Biết cách biến BookImage thành BookCoverImageResponse.

CloudinaryImageUrlBuilder
Gọi Cloudinary Java SDK để build URL Cloudinary và transformation.
```

## Các Loại URL Trả Về

### `originalUrl`

URL gốc, không transformation:

```text
https://res.cloudinary.com/dwgv3yx7e/image/upload/book-covers/book-3000/cover-abc.png
```

Dùng khi cần ảnh gốc hoặc fallback.

### `thumbnailUrl`

URL tối ưu cho danh sách/card:

```text
https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,f_auto,h_480,q_auto,w_320/book-covers/book-3000/cover-abc.png
```

Dùng cho:

```text
GET /api/books
Book card
Search result
```

### `detailUrl`

URL lớn hơn cho trang chi tiết:

```text
https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,f_auto,h_1200,q_auto,w_800/book-covers/book-3000/cover-abc.png
```

Dùng cho:

```text
GET /api/books/{bookId}
Book detail page
Admin detail page
```

## Vì Sao URL Không Có Version

Cloudinary URL có thể có version:

```text
/image/upload/v1780497401/publicId.png
```

Nhưng trong hệ thống này backend build URL versionless:

```text
/image/upload/publicId.png
```

Lý do:

```text
Mỗi asset có thể có version khác nhau.
Backend đã tạo publicId mới mỗi lần upload cover mới.
Database quyết định ảnh nào là ACTIVE.
Không cần phụ thuộc version để chọn ảnh đang dùng.
```

Khi thay ảnh, hệ thống không upload đè cùng publicId, mà tạo publicId mới:

```text
book-covers/book-3000/cover-a
book-covers/book-3000/cover-b
book-covers/book-3000/cover-c
```

DB chọn ảnh mới nhất là:

```text
is_primary = true
status = ACTIVE
```

## Frontend Nên Dùng Field Nào

Trang list/card:

```ts
book.coverImage?.thumbnailUrl
```

Trang detail:

```ts
book.coverImage?.detailUrl
```

Fallback nếu chưa có ảnh:

```ts
book.coverImage?.thumbnailUrl || "/images/book-placeholder.png"
```

Alt text:

```ts
book.coverImage?.altText || book.title
```

Frontend không nên tự ghép Cloudinary URL.

## Ghi Chú Về Cloudinary SDK

Backend hiện dùng Cloudinary Java SDK cho hai việc:

```text
CloudinaryStorageService
-> cloudinary.uploader().upload(...)
-> cloudinary.uploader().destroy(...)

CloudinaryImageUrlBuilder
-> cloudinary.url().transformation(...).generate(...)
```

Vì SDK tự chuẩn hóa thứ tự transformation, URL có thể là:

```text
c_fit,f_auto,h_480,q_auto,w_320
```

thay vì:

```text
c_fit,w_320,h_480,q_auto,f_auto
```

Hai URL này cùng ý nghĩa. Frontend không nên parse chuỗi transformation; chỉ cần dùng nguyên URL backend trả về.

## Tóm Tắt

```text
Không lưu URL transform làm dữ liệu chính.
Lưu metadata vào book_images.
Backend dùng Cloudinary Java SDK build original/thumbnail/detail URL từ publicId.
Frontend chỉ render coverImage.thumbnailUrl hoặc coverImage.detailUrl.
Khi update cover, upload ảnh mới trước rồi mới xử lý ảnh cũ.
```
