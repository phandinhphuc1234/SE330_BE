package com.vn.controller.docs;

import com.vn.dto.catalog.request.CreateCategoryRequest;
import com.vn.dto.catalog.request.UpdateCategoryRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.CategoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Categories", description = "APIs for viewing and managing book categories")
public interface CategoryApiDocs {

    @SecurityRequirements
    @Operation(
            summary = "Get categories",
            description = "Public API for getting all categories sorted by name. Used for book filters."
    )
    ResponseEntity<ApiResponse<List<CategoryResponse>>> getCategories();

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Create category",
            description = "Create a new book category. Only Admin can access this API."
    )
    ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Category information")
            CreateCategoryRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update category",
            description = "Partially update category name or description. Only Admin can access this API."
    )
    ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @Parameter(description = "Category ID", required = true) Long categoryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Category fields to update")
            UpdateCategoryRequest request
    );
}

