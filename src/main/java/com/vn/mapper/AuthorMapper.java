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
                author.getCreatedAt(),
                author.getUpdatedAt()
        );
    }
}

