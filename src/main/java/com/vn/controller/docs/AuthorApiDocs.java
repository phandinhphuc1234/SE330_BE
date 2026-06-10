package com.vn.controller.docs;

import com.vn.dto.catalog.request.CreateAuthorRequest;
import com.vn.dto.catalog.request.UpdateAuthorRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.catalog.response.AuthorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Authors", description = "APIs for viewing and managing authors")
public interface AuthorApiDocs {

    @SecurityRequirements
    @Operation(
            summary = "Get authors",
            description = """
                    Public API for getting authors sorted by name with pagination.
                    Default page size is 6 records.
                    Optional filters q/name search by author name case-insensitively.
                    """
    )
    ResponseEntity<ApiResponse<List<AuthorResponse>>> getAuthors(
            @Parameter(description = "Quick author name search") String q,
            @Parameter(description = "Author name search. Takes precedence over q when both are provided") String name,
            @Parameter(description = "Page number, starts from 0") int page,
            @Parameter(description = "Page size. Default is 6") int size
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Create author",
            description = "Create a new author. Author name must be unique. Librarian and Admin can access this API."
    )
    ResponseEntity<ApiResponse<AuthorResponse>> createAuthor(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Author information")
            CreateAuthorRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update author",
            description = "Partially update author name or bio. Librarian and Admin can access this API."
    )
    ResponseEntity<ApiResponse<AuthorResponse>> updateAuthor(
            @Parameter(description = "Author ID", required = true) Long authorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Author fields to update")
            UpdateAuthorRequest request
    );
}

