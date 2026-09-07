package com.vn.service;

import com.vn.dto.catalog.request.CreateAuthorRequest;
import com.vn.dto.catalog.request.UpdateAuthorRequest;
import com.vn.dto.catalog.response.AuthorResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

public interface AuthorService {

    Page<AuthorResponse> getAuthors(String q, String name, int page, int size);

    AuthorResponse createAuthor(CreateAuthorRequest request);

    AuthorResponse updateAuthor(Long authorId, UpdateAuthorRequest request);

    AuthorResponse uploadAuthorImage(Long authorId, MultipartFile file);

    AuthorResponse updateAuthorImage(Long authorId, MultipartFile file);
}

