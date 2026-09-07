package com.vn.mapper;

import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.entity.Author;
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

