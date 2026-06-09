package com.vn.dto.ebook.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EbookLoanResponse {

    private Long loanId;
    private Long bookId;
    private String bookTitle;
    private String isbn;
    private String status;       // ACTIVE | EXPIRED | RETURNED

    private Instant borrowedAt;
    private Instant expiresAt;
    private Instant returnedAt;

    private Integer renewCount;
    private Integer maxRenewals;
    private boolean canRenew;    // renewCount < maxRenewals && status == ACTIVE

    /**
     * Chỉ trả về khi status == ACTIVE — URL để đọc ebook.
     * Không bao giờ expose khi đã EXPIRED / RETURNED.
     */
    private String ebookReadUrl;
}
