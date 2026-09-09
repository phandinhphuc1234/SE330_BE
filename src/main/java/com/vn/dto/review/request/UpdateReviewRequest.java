package com.vn.dto.review.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateReviewRequest(
        @NotNull(message = "Rating là bắt buộc")
        @Min(value = 1, message = "Rating phải từ 1 đến 5")
        @Max(value = 5, message = "Rating phải từ 1 đến 5")
        Integer rating,

        @Size(max = 2000, message = "Nội dung đánh giá không được vượt quá 2000 ký tự")
        String content
) {
}
