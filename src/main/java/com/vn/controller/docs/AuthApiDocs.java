package com.vn.controller.docs;

import com.vn.dto.auth.request.LoginRequest;
import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.auth.request.ResendVerificationRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.auth.response.AuthResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

@Tag(name = "Authentication", description = "APIs for registration, login, email verification, token refresh and logout")
public interface AuthApiDocs {

    @SecurityRequirements
    @Operation(
            summary = "Register account",
            description = """
                    Create a new member account with PENDING_VERIFICATION status.
                    The system sends a verification email to the registered address.
                    """
    )
    ResponseEntity<ApiResponse<Void>> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Registration information")
            RegistrationRequest request
    );

    @SecurityRequirements
    @Operation(
            summary = "Verify email",
            description = "Verify an account using the token sent by email. A successful verification activates the account."
    )
    ResponseEntity<ApiResponse<Void>> verifyEmail(
            @Parameter(description = "Email verification token", required = true) String token
    );

    @SecurityRequirements
    @Operation(
            summary = "Login",
            description = """
                    Authenticate by email and password.
                    Access token is returned in the response body and refresh token is set in an HttpOnly cookie.
                    """
    )
    ResponseEntity<ApiResponse<AuthResponse>> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Login credentials")
            LoginRequest request,
            @Parameter(hidden = true) HttpServletResponse response
    );

    @SecurityRequirements
    @Operation(
            summary = "Refresh access token",
            description = "Use refresh token from HttpOnly cookie to get a new access token. The refresh token is rotated."
    )
    ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Parameter(hidden = true) String refreshToken,
            @Parameter(hidden = true) HttpServletResponse response
    );

    @SecurityRequirements
    @Operation(
            summary = "Resend verification email",
            description = "Resend verification email for an inactive account. Cooldown and daily limits are applied."
    )
    ResponseEntity<ApiResponse<Void>> resendVerification(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Email to resend verification to")
            ResendVerificationRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Logout",
            description = "Blacklist the current access token, remove refresh token from Redis and clear refresh-token cookie."
    )
    ResponseEntity<ApiResponse<Void>> logout(
            @Parameter(description = "Authorization header in Bearer token format", required = true) String authHeader,
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(hidden = true) HttpServletResponse response
    );
}

