package com.vn.controller;

import com.vn.controller.docs.PaymentApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.payment.request.CreatePaymentRequest;
import com.vn.dto.payment.request.VnpayReturnConfirmRequest;
import com.vn.dto.payment.response.CreatePaymentResponse;
import com.vn.dto.payment.response.PaymentIpnResponse;
import com.vn.dto.payment.response.PaymentResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.PaymentCallbackService;
import com.vn.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController implements PaymentApiDocs {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final PaymentService paymentService;
    private final PaymentCallbackService paymentCallbackService;

    // Tạo transaction PENDING và trả provider paymentUrl; chưa cấp loan/quyền đọc ebook.
    @PostMapping
    @Override
    public ResponseEntity<ApiResponse<CreatePaymentResponse>> createPayment(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {
        CreatePaymentResponse payment = paymentService.createPayment(
                getCurrentMemberId(userDetails),
                idempotencyKey,
                resolveClientIp(httpRequest),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo giao dịch thanh toán thành công", payment));
    }

    // VNPAY gọi server-to-server, response phải là JSON RspCode/Message đúng contract provider.
    @GetMapping("/ipn/vnpay")
    @Override
    public ResponseEntity<PaymentIpnResponse> handleVnpayIpn(
            @RequestParam Map<String, String> params,
            @RequestHeader HttpHeaders headers) {
        log.info("Received VNPAY IPN params={}", params);
        return ResponseEntity.ok(paymentCallbackService.handleVnpayIpn(params, headers.toSingleValueMap()));
    }

    // Fallback cho local/sandbox khi IPN không về được qua tunnel; vẫn verify chữ ký VNPAY trước khi update DB.
    @PostMapping("/return/vnpay/confirm")
    @Override
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmVnpayReturn(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody VnpayReturnConfirmRequest request,
            @RequestHeader HttpHeaders headers) {
        PaymentResponse payment = paymentCallbackService.confirmVnpayReturn(
                getCurrentMemberId(userDetails),
                request.params(),
                headers.toSingleValueMap()
        );
        return ResponseEntity.ok(ApiResponse.success("Xác nhận kết quả thanh toán thành công", payment));
    }

    // Frontend gọi sau khi quay về từ provider để poll trạng thái payment theo id.
    @GetMapping("/{paymentId}")
    @Override
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @PathVariable Long paymentId,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy trạng thái thanh toán thành công",
                paymentService.getPayment(getCurrentMemberId(userDetails), paymentId)
        ));
    }

    // paymentCode ổn định hơn cho redirect/polling vì nó chính là provider order id.
    @GetMapping("/by-code/{paymentCode}")
    @Override
    public ResponseEntity<ApiResponse<PaymentResponse>> getPaymentByCode(
            @PathVariable String paymentCode,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy trạng thái thanh toán thành công",
                paymentService.getPaymentByCode(getCurrentMemberId(userDetails), paymentCode)
        ));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }

    // VNPAY cần IP client; ưu tiên X-Forwarded-For khi app đi qua proxy/ngrok.
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
