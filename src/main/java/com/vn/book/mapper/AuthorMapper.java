package com.vn.book.mapper;

import com.vn.book.dto.response.AuthorResponse;
import com.vn.book.entity.Author;
import org.springframework.stereotype.Component;

@Component
public class AuthorMapper {

    public AuthorResponse toAuthorResponse(Author author) {
        if (author == null) {
            return null;
        }

        return new AuthorResponse(
                author.getId(),
                author.getName(),
                author.getBio(),
                author.getImageUrl(),
                author.getImageProvider() == null ? null : author.getImageProvider().name(),
                author.getCreatedAt(),
                author.getUpdatedAt()
        );
    }
}

