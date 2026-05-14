package com.vn.controller;

import com.vn.controller.docs.AuthorApiDocs;
import com.vn.dto.catalog.request.CreateAuthorRequest;
import com.vn.dto.catalog.request.UpdateAuthorRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.service.AuthorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/authors")
@RequiredArgsConstructor
public class AuthorController implements AuthorApiDocs {

    private final AuthorService authorService;
    // Trả về danh sách tác giả
    @GetMapping
    @Override
    public ResponseEntity<ApiResponse<List<AuthorResponse>>> getAuthors() {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách tác giả thành công",
                authorService.getAuthors()
        ));
    }
    // Tạo thông tin tác giả mới trong hệ thống
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<AuthorResponse>> createAuthor(@Valid @RequestBody CreateAuthorRequest request) {
        AuthorResponse author = authorService.createAuthor(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo tác giả thành công", author));
    }
    // Cập nhật thông tin tác giả
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PatchMapping("/{authorId}")
    @Override
    public ResponseEntity<ApiResponse<AuthorResponse>> updateAuthor(
            @PathVariable Long authorId,
            @Valid @RequestBody UpdateAuthorRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật tác giả thành công",
                authorService.updateAuthor(authorId, request)
        ));
    }
}

