package com.vn.mapper;

import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import com.vn.entity.Author;
import com.vn.entity.Book;
import com.vn.entity.BookImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BookMapper {

    private final AuthorMapper authorMapper;
    private final CategoryMapper categoryMapper;
    private final BookImageMapper bookImageMapper;

    // Ảnh chính nằm ở book_images; response chỉ expose coverImage cho frontend.
    public BookSummaryResponse toBookSummaryResponse(Book book, BookImage primaryImage) {
        BookCoverImageResponse coverImage = bookImageMapper.toCoverImageResponse(primaryImage);
        return new BookSummaryResponse(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPublishedDate(),
                book.getLanguage(),
                book.getEdition(),
                coverImage,
                book.getTotalCopies(),
                book.getAvailableCopies(),
                categoryMapper.toCategoryResponse(book.getCategory()),
                toAuthorResponses(book)
        );
    }

    // Frontend dùng coverImage.detailUrl cho detail page.
    public BookDetailResponse toBookDetailResponse(Book book, BookImage primaryImage) {
        BookCoverImageResponse coverImage = bookImageMapper.toCoverImageResponse(primaryImage);
        return new BookDetailResponse(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPublishedDate(),
                book.getLanguage(),
                book.getEdition(),
                coverImage,
                book.getTotalCopies(),
                book.getAvailableCopies(),
                categoryMapper.toCategoryResponse(book.getCategory()),
                toAuthorResponses(book),
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }

    private List<AuthorResponse> toAuthorResponses(Book book) {
        return book.getAuthors().stream()
                .sorted(Comparator.comparing(Author::getName))
                .map(authorMapper::toAuthorResponse)
                .toList();
    }
}

