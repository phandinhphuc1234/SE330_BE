package com.vn.controller;

import com.vn.controller.docs.CategoryApiDocs;
import com.vn.dto.catalog.request.CreateCategoryRequest;
import com.vn.dto.catalog.request.UpdateCategoryRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.CategoryResponse;
import com.vn.service.CategoryService;
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
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController implements CategoryApiDocs {

    private final CategoryService categoryService;
    // Lấy danh sách thể loại thành công
    @GetMapping
    @Override
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách thể loại thành công",
                categoryService.getCategories()
        ));
    }
    // Tạo thể loại mới thành công
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse category = categoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo thể loại thành công", category));
    }
    // Cập nhật thông tin của thể loại thành công
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{categoryId}")
    @Override
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật thể loại thành công",
                categoryService.updateCategory(categoryId, request)
        ));
    }
}

