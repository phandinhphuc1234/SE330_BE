package com.vn.controller.docs;

import com.vn.dto.catalog.request.UpdateBookCopyRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Book Copies", description = "APIs for managing individual physical book copies")
public interface BookCopyApiDocs {

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update book copy",
            description = """
                    Update physical copy information such as condition and location.
                    Status is not updated here; BORROWED, RESERVED and AVAILABLE transitions belong to borrow/return/reservation flows.
                    """
    )
    ResponseEntity<ApiResponse<BookCopyResponse>> updateBookCopy(
            @Parameter(description = "Book copy ID", required = true) Long copyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Copy fields to update")
            UpdateBookCopyRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Delete book copy",
            description = """
                    Soft delete a physical copy.
                    The copy cannot be deleted if it is BORROWED or RESERVED.
                    Only Admin can access this API.
                    """
    )
    ResponseEntity<ApiResponse<Void>> deleteBookCopy(
            @Parameter(description = "Book copy ID", required = true) Long copyId,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );
}

