package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.payment.request.CreatePaymentRequest;
import com.vn.dto.payment.request.VnpayReturnConfirmRequest;
import com.vn.dto.payment.response.CreatePaymentResponse;
import com.vn.dto.payment.response.PaymentIpnResponse;
import com.vn.dto.payment.response.PaymentResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@Tag(name = "Payments", description = "APIs for creating and tracking payment transactions")
public interface PaymentApiDocs {

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Create payment",
            description = """
                    Create a PENDING payment transaction and return the provider payment URL.
                    Frontend does not send amount; backend calculates it from the payable target.
                    Requires Idempotency-Key. This API does not create an ebook loan.
                    """
    )
    ResponseEntity<ApiResponse<CreatePaymentResponse>> createPayment(
            @Parameter(description = "Idempotency key", required = true) String idempotencyKey,
            @Parameter(hidden = true) MemberUserDetails userDetails,
            CreatePaymentRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest
    );

    @Operation(
            summary = "Handle VNPAY IPN",
            description = """
                    Public server-to-server callback used by VNPAY to confirm payment status.
                    The endpoint verifies provider signature, updates payment state, and grants ebook loan on success.
                    """
    )
    ResponseEntity<PaymentIpnResponse> handleVnpayIpn(
            @Parameter(hidden = true) Map<String, String> params,
            @Parameter(hidden = true) HttpHeaders headers
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Confirm VNPAY return",
            description = """
                    Authenticated fallback for local/sandbox VNPAY return redirects.
                    The backend verifies VNPAY signature, checks member ownership and amount,
                    then updates payment state if the transaction is still pending.
                    IPN remains the production source of truth.
                    """
    )
    ResponseEntity<ApiResponse<PaymentResponse>> confirmVnpayReturn(
            @Parameter(hidden = true) MemberUserDetails userDetails,
            VnpayReturnConfirmRequest request,
            @Parameter(hidden = true) HttpHeaders headers
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get payment by ID",
            description = "Return the current payment status for the authenticated member."
    )
    ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @Parameter(description = "Payment ID", required = true) Long paymentId,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get payment by code",
            description = "Return the current payment status by paymentCode for the authenticated member."
    )
    ResponseEntity<ApiResponse<PaymentResponse>> getPaymentByCode(
            @Parameter(description = "Payment code", required = true) String paymentCode,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );
}
