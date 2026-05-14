package com.vn.service;

import com.vn.dto.catalog.request.CreateCategoryRequest;
import com.vn.dto.catalog.request.UpdateCategoryRequest;
import com.vn.dto.catalog.response.CategoryResponse;

import java.util.List;

public interface CategoryService {

    List<CategoryResponse> getCategories();

    CategoryResponse createCategory(CreateCategoryRequest request);

    CategoryResponse updateCategory(Long categoryId, UpdateCategoryRequest request);
}

