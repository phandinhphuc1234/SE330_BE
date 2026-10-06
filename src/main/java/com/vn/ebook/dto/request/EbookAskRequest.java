package com.vn.ebook.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record EbookAskRequest(
        @NotBlank(message = "Câu hỏi không được để trống")
        @Size(max = 4096, message = "Câu hỏi tối đa 4096 ký tự")
        String question,

        @Min(value = 1, message = "topK phải lớn hơn 0")
        @Max(value = 20, message = "topK tối đa là 20")
        Integer topK,

        @DecimalMin(value = "0.0", message = "scoreThreshold không được nhỏ hơn 0")
        @DecimalMax(value = "1.0", message = "scoreThreshold không được lớn hơn 1")
        BigDecimal scoreThreshold
) {
}
