package com.vn.dto.ebook.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BorrowEbookRequest {

    @NotNull(message = "bookId không được để trống")
    private Long bookId;
}
