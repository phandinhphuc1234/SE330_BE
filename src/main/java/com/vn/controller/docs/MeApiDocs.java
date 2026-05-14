package com.vn.controller.docs;

import com.vn.dto.member.request.UpdateMyProfileRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.member.response.MyProfileResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "My Profile", description = "APIs for managing the currently authenticated member profile")
public interface MeApiDocs {

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get my profile",
            description = "Return profile information of the currently authenticated member."
    )
    ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Update my profile",
            description = """
                    Update profile information of the currently authenticated member.
                    Only fullName and phone can be updated here.
                    Email, password, role and status cannot be updated by this API.
                    """
    )
    ResponseEntity<ApiResponse<MyProfileResponse>> updateMyProfile(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Profile fields to update")
            UpdateMyProfileRequest request
    );
}

