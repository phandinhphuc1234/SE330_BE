package com.vn.mapper;

import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import com.vn.entity.Author;
import com.vn.entity.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BookMapper {

    private final AuthorMapper authorMapper;
    private final CategoryMapper categoryMapper;

    public BookSummaryResponse toBookSummaryResponse(Book book) {
        return new BookSummaryResponse(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPublishedDate(),
                book.getLanguage(),
                book.getEdition(),
                book.getTotalCopies(),
                book.getAvailableCopies(),
                categoryMapper.toCategoryResponse(book.getCategory()),
                toAuthorResponses(book),
                book.getEbookUrl()
        );
    }

    public BookDetailResponse toBookDetailResponse(Book book) {
        return new BookDetailResponse(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPublishedDate(),
                book.getLanguage(),
                book.getEdition(),
                book.getTotalCopies(),
                book.getAvailableCopies(),
                categoryMapper.toCategoryResponse(book.getCategory()),
                toAuthorResponses(book),
                book.getCreatedAt(),
                book.getUpdatedAt(),
                book.getEbookUrl()
        );
    }

    private List<AuthorResponse> toAuthorResponses(Book book) {
        return book.getAuthors().stream()
                .sorted(Comparator.comparing(Author::getName))
                .map(authorMapper::toAuthorResponse)
                .toList();
    }
}

