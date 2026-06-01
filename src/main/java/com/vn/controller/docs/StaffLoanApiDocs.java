package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.staff.loan.response.StaffLoanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Staff Loans", description = "Staff APIs for loan-centric circulation search")
@SecurityRequirement(name = "Bearer Authentication")
public interface StaffLoanApiDocs {

    @Operation(
            summary = "Search staff loans",
            description = "Librarian/Admin searches all borrow records by member, title, ISBN or barcode."
    )
    ResponseEntity<ApiResponse<List<StaffLoanResponse>>> searchLoans(
            @Parameter(description = "Search by barcode, book title, ISBN, member name, member email or phone") String q,
            @Parameter(description = "Borrow status") String status,
            @Parameter(description = "When true, returns BORROWED/OVERDUE/LOST loans only") Boolean openOnly,
            @Parameter(description = "When true, returns overdue loans only") Boolean overdue,
            @Parameter(description = "Due date lower bound, ISO instant or yyyy-MM-dd") String dueFrom,
            @Parameter(description = "Due date upper bound, ISO instant or yyyy-MM-dd") String dueTo,
            int page,
            int size
    );
}
