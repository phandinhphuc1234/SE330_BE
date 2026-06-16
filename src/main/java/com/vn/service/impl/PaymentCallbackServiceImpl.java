package com.vn.service.impl;

import com.vn.dto.payment.provider.ProviderCallbackRequest;
import com.vn.dto.payment.provider.ProviderCallbackVerificationResult;
import com.vn.dto.payment.response.PaymentIpnResponse;
import com.vn.dto.payment.response.PaymentResponse;
import com.vn.entity.PaymentEvent;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.PaymentEventProcessingStatus;
import com.vn.enums.PaymentEventType;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.PaymentEventRepository;
import com.vn.repository.PaymentTransactionRepository;
import com.vn.service.PaymentCallbackService;
import com.vn.service.payment.business.PaymentBusinessApplier;
import com.vn.service.payment.business.PaymentBusinessApplierFactory;
import com.vn.service.payment.provider.PaymentProviderClient;
import com.vn.service.payment.provider.PaymentProviderClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCallbackServiceImpl implements PaymentCallbackService {

    // Hiện tại MVP chỉ hỗ trợ VNPAY với tiền VND whole amount.
    private static final String VND = "VND";

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentProviderClientFactory providerClientFactory;
    private final PaymentBusinessApplierFactory businessApplierFactory;

    // Entry point cho VNPAY IPN. Toàn bộ xử lý nằm trong transaction để payment, event và loan nhất quán.
    @Override
    @Transactional
    public PaymentIpnResponse handleVnpayIpn(Map<String, String> params, Map<String, String> headers) {
        // Lưu event RECEIVED trước khi verify để audit được cả callback sai chữ ký/retry.
        PaymentEvent event = createReceivedEvent(PaymentProvider.VNPAY, PaymentEventType.VNPAY_IPN, params, headers);
        try {
            return processVnpayIpn(event, params, headers);
        } catch (RuntimeException ex) {
            // Không throw ra ngoài vì VNPAY cần RspCode 99 để quyết định retry.
            markFailed(event, "Unknown error: " + ex.getClass().getSimpleName());
            log.error("VNPAY IPN failed with unknown error params={}", params, ex);
            return PaymentIpnResponse.unknownError();
        }
    }

    @Override
    @Transactional
    public PaymentResponse confirmVnpayReturn(Long memberId, Map<String, String> params, Map<String, String> headers) {
        PaymentEvent event = createReceivedEvent(PaymentProvider.VNPAY, PaymentEventType.VNPAY_RETURN, params, headers);
        ProviderCallbackVerificationResult verification = verifyVnpayPayload(params, headers);
        applyVerificationToEvent(event, verification);

        if (!verification.signatureValid()) {
            markFailed(event, "Invalid signature");
            log.warn("VNPAY return rejected: invalid signature providerOrderId={} providerTransactionId={} params={}",
                    verification.providerOrderId(),
                    verification.providerTransactionId(),
                    params);
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        PaymentTransaction payment = findLockedPayment(verification.providerOrderId());
        if (payment == null || !Objects.equals(payment.getMemberId(), memberId)) {
            markFailed(event, "Order not found");
            log.warn("VNPAY return rejected: order not found or member mismatch providerOrderId={} memberId={}",
                    verification.providerOrderId(),
                    memberId);
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        event.setPaymentTransaction(payment);
        if (!amountMatches(payment, verification)) {
            markFailed(event, "Invalid amount");
            log.warn("VNPAY return rejected: invalid amount paymentCode={} expectedAmount={} expectedCurrency={} actualAmount={} actualCurrency={}",
                    payment.getPaymentCode(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    verification.amount(),
                    verification.currency());
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            markIgnored(event, "Order already terminal");
            log.info("VNPAY return ignored: payment already terminal paymentCode={} status={}",
                    payment.getPaymentCode(),
                    payment.getStatus());
            return toPaymentResponse(payment);
        }

        applyProviderResult(payment, verification);
        markProcessed(event);
        return toPaymentResponse(payment);
    }

    // Flow xử lý chính theo spec: verify chữ ký, lock payment, check amount, cập nhật trạng thái và cấp loan.
    private PaymentIpnResponse processVnpayIpn(PaymentEvent event,
                                               Map<String, String> params,
                                               Map<String, String> headers) {
        ProviderCallbackVerificationResult verification = verifyVnpayPayload(params, headers);
        // Gắn kết quả parse/verify vào event để phục vụ đối soát hoặc debug retry.
        applyVerificationToEvent(event, verification);

        // Sai chữ ký thì không được tìm payment/update payment để tránh dữ liệu giả mạo tác động DB.
        if (!verification.signatureValid()) {
            markFailed(event, "Invalid signature");
            log.warn("VNPAY IPN rejected: invalid signature providerOrderId={} providerTransactionId={} params={}",
                    verification.providerOrderId(),
                    verification.providerTransactionId(),
                    params);
            return PaymentIpnResponse.invalidSignature();
        }

        // providerOrderId chính là vnp_TxnRef/paymentCode đã gửi sang VNPAY lúc tạo payment.
        PaymentTransaction payment = findLockedPayment(verification.providerOrderId());
        if (payment == null) {
            markFailed(event, "Order not found");
            log.warn("VNPAY IPN rejected: order not found providerOrderId={} providerTransactionId={}",
                    verification.providerOrderId(),
                    verification.providerTransactionId());
            return PaymentIpnResponse.orderNotFound();
        }

        event.setPaymentTransaction(payment);
        // Amount phải khớp tuyệt đối; nếu sai thì có thể là callback lệch đơn hoặc payload không hợp lệ.
        if (!amountMatches(payment, verification)) {
            markFailed(event, "Invalid amount");
            log.warn("VNPAY IPN rejected: invalid amount paymentCode={} expectedAmount={} expectedCurrency={} actualAmount={} actualCurrency={}",
                    payment.getPaymentCode(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    verification.amount(),
                    verification.currency());
            return PaymentIpnResponse.invalidAmount();
        }

        // VNPAY có thể retry IPN. Payment terminal thì chỉ ghi event IGNORED và trả code duplicate.
        if (payment.getStatus() != PaymentStatus.PENDING) {
            markIgnored(event, "Order already confirmed");
            log.info("VNPAY IPN ignored: payment already terminal paymentCode={} status={}",
                    payment.getPaymentCode(),
                    payment.getStatus());
            return PaymentIpnResponse.alreadyConfirmed();
        }

        applyProviderResult(payment, verification);
        markProcessed(event);
        log.info("VNPAY IPN processed paymentCode={} status={} providerTransactionId={} paidAt={}",
                payment.getPaymentCode(),
                payment.getStatus(),
                payment.getProviderTransactionId(),
                payment.getPaidAt());
        return PaymentIpnResponse.confirmSuccess();
    }

    private ProviderCallbackVerificationResult verifyVnpayPayload(Map<String, String> params, Map<String, String> headers) {
        // PaymentCallbackService chỉ dùng provider interface, không đọc trực tiếp logic ký của VNPAY.
        PaymentProviderClient providerClient = providerClientFactory.get(PaymentProvider.VNPAY);
        return providerClient.verifyCallback(new ProviderCallbackRequest(
                PaymentProvider.VNPAY,
                normalizeStringMap(params),
                normalizeStringMap(headers),
                ""
        ));
    }

    private void applyProviderResult(PaymentTransaction payment, ProviderCallbackVerificationResult verification) {
        // Lưu các field provider để frontend/admin đối soát trạng thái thanh toán.
        applyProviderFields(payment, verification);
        if (!verification.paymentSuccess()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureCode(verification.responseCode());
            payment.setFailureMessage("Provider transaction failed");
            log.info("VNPAY provider failure processed paymentCode={} responseCode={} transactionStatus={}",
                    payment.getPaymentCode(),
                    verification.responseCode(),
                    verification.transactionStatus());
            return;
        }

        // Chỉ khi cả responseCode và transactionStatus thành công thì mới chuyển SUCCESS và apply nghiệp vụ.
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(verification.paidAt() == null ? Instant.now() : verification.paidAt());
        PaymentBusinessApplier applier = businessApplierFactory.get(payment.getPurpose(), payment.getTargetType());
        // Với ebook, side-effect là tạo ebook_loan ACTIVE; implementation phải idempotent theo payment_id.
        applier.applySuccess(payment);
    }

    // Lock payment row để hai IPN retry/cạnh tranh không cùng chuyển PENDING -> terminal hoặc tạo loan trùng.
    private PaymentTransaction findLockedPayment(String providerOrderId) {
        if (!StringUtils.hasText(providerOrderId)) {
            return null;
        }
        return paymentTransactionRepository
                .findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, providerOrderId)
                .orElse(null);
    }

    // VNPAY gửi amount đã parse về whole VND; payment chỉ hợp lệ khi amount và currency trùng transaction gốc.
    private boolean amountMatches(PaymentTransaction payment, ProviderCallbackVerificationResult verification) {
        return Objects.equals(payment.getAmount(), verification.amount())
                && VND.equalsIgnoreCase(payment.getCurrency())
                && VND.equalsIgnoreCase(verification.currency());
    }

    // Copy thông tin provider về payment transaction để frontend/admin đọc trạng thái và đối soát.
    private void applyProviderFields(PaymentTransaction payment, ProviderCallbackVerificationResult verification) {
        payment.setProviderTransactionId(verification.providerTransactionId());
        payment.setProviderResponseCode(verification.responseCode());
        payment.setProviderTransactionStatus(verification.transactionStatus());

        // Merge metadata mới vào metadata cũ để không làm mất dữ liệu lúc create payment như createDate/expireDate.
        Map<String, Object> metadata = payment.getProviderMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(payment.getProviderMetadata());
        if (verification.providerMetadata() != null) {
            metadata.putAll(verification.providerMetadata());
        }
        payment.setProviderMetadata(metadata);
    }

    // Tạo audit event ngay khi nhận callback, trước mọi validation nghiệp vụ.
    private PaymentEvent createReceivedEvent(PaymentProvider provider,
                                             PaymentEventType eventType,
                                             Map<String, String> params,
                                             Map<String, String> headers) {
        PaymentEvent event = new PaymentEvent();
        event.setProvider(provider);
        event.setEventType(eventType);
        event.setRawPayload(toRawVnpayPayload(params));
        event.setRawHeaders(toObjectMap(headers));
        event.setProcessingStatus(PaymentEventProcessingStatus.RECEIVED);
        return paymentEventRepository.save(event);
    }

    // Cập nhật event bằng dữ liệu provider đã chuẩn hóa sau verify.
    private void applyVerificationToEvent(PaymentEvent event, ProviderCallbackVerificationResult verification) {
        event.setSignatureValid(verification.signatureValid());
        event.setProviderOrderId(verification.providerOrderId());
        event.setProviderTransactionId(verification.providerTransactionId());
        // Ưu tiên rawPayload do provider client trả vì nó đã lọc/sắp xếp đúng các vnp_* params.
        if (verification.rawPayload() != null && !verification.rawPayload().isEmpty()) {
            event.setRawPayload(verification.rawPayload());
        }
    }

    // Event xử lý xong và payment đã được cập nhật theo kết quả provider.
    private void markProcessed(PaymentEvent event) {
        event.setProcessingStatus(PaymentEventProcessingStatus.PROCESSED);
        event.setProcessedAt(Instant.now());
        paymentEventRepository.save(event);
    }

    // Event không xử lý được vì lỗi nghiệp vụ như sai chữ ký, không thấy order hoặc sai amount.
    private void markFailed(PaymentEvent event, String errorMessage) {
        event.setProcessingStatus(PaymentEventProcessingStatus.FAILED);
        event.setErrorMessage(errorMessage);
        event.setProcessedAt(Instant.now());
        paymentEventRepository.save(event);
    }

    // Event hợp lệ nhưng không cần xử lý thêm, thường là IPN retry sau khi payment đã terminal.
    private void markIgnored(PaymentEvent event, String errorMessage) {
        event.setProcessingStatus(PaymentEventProcessingStatus.IGNORED);
        event.setErrorMessage(errorMessage);
        event.setProcessedAt(Instant.now());
        paymentEventRepository.save(event);
    }

    // Provider client không nhận null map để tránh null-check lặp lại ở từng implementation.
    private Map<String, String> normalizeStringMap(Map<String, String> values) {
        return values == null ? Map.of() : values;
    }

    // Chỉ lưu vnp_* params vào rawPayload vì đó là phần provider ký và dùng cho đối soát.
    private Map<String, Object> toRawVnpayPayload(Map<String, String> params) {
        Map<String, Object> rawPayload = new LinkedHashMap<>();
        if (params == null) {
            return rawPayload;
        }
        new TreeMap<>(params).forEach((key, value) -> {
            if (key != null && key.startsWith("vnp_")) {
                rawPayload.put(key, value);
            }
        });
        return rawPayload;
    }

    // Header cũng được sort ổn định để audit dễ đọc hơn khi debug callback.
    private Map<String, Object> toObjectMap(Map<String, String> values) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (values == null) {
            return map;
        }
        new TreeMap<>(values).forEach((key, value) -> {
            if (key != null) {
                map.put(key, value);
            }
        });
        return map;
    }

    private PaymentResponse toPaymentResponse(PaymentTransaction transaction) {
        return new PaymentResponse(
                transaction.getId(),
                transaction.getPaymentCode(),
                transaction.getProvider().name(),
                transaction.getPurpose().name(),
                transaction.getTargetType().name(),
                transaction.getTargetId(),
                transaction.getStatus().name(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getPaymentUrl(),
                transaction.getProviderResponseCode(),
                transaction.getProviderTransactionStatus(),
                transaction.getPaidAt(),
                transaction.getCancelledAt(),
                transaction.getExpiredAt(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }
}
