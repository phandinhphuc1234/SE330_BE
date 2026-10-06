package com.vn.book.service;

import com.vn.book.dto.request.CreateCategoryRequest;
import com.vn.book.dto.request.UpdateCategoryRequest;
import com.vn.book.dto.response.CategoryResponse;

import java.util.List;

public interface CategoryService {

    List<CategoryResponse> getCategories();

    CategoryResponse createCategory(CreateCategoryRequest request);

    CategoryResponse updateCategory(Long categoryId, UpdateCategoryRequest request);
}

