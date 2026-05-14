package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import java.util.Map;

public interface HomeApiDocs {

    @SecurityRequirements
    @Operation(summary = "API health landing", description = "Public landing endpoint that confirms the API is running.")
    ApiResponse<Map<String, String>> home();
}

