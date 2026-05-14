package com.vn.service;

import com.vn.dto.catalog.request.CreateAuthorRequest;
import com.vn.dto.catalog.request.UpdateAuthorRequest;
import com.vn.dto.catalog.response.AuthorResponse;

import java.util.List;

public interface AuthorService {

    List<AuthorResponse> getAuthors();

    AuthorResponse createAuthor(CreateAuthorRequest request);

    AuthorResponse updateAuthor(Long authorId, UpdateAuthorRequest request);
}

