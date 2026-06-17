package com.vn.service.impl;

import com.vn.dto.payment.provider.ProviderPaymentCreateRequest;
import com.vn.dto.payment.provider.ProviderPaymentCreateResult;
import com.vn.dto.payment.request.CreatePaymentRequest;
import com.vn.dto.payment.response.CreatePaymentResponse;
import com.vn.dto.payment.response.PaymentResponse;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentStatus;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.PaymentTransactionRepository;
import com.vn.service.PaymentService;
import com.vn.service.payment.business.PayableTarget;
import com.vn.service.payment.business.PaymentBusinessApplier;
import com.vn.service.payment.business.PaymentBusinessApplierFactory;
import com.vn.service.payment.idempotency.PaymentIdempotencyService;
import com.vn.service.payment.provider.PaymentProviderClient;
import com.vn.service.payment.provider.PaymentProviderClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final Duration PAYMENT_EXPIRATION = Duration.ofMinutes(15);
    private static final String VND = "VND";
    private static final Set<String> VNPAY_BANK_CODES = Set.of("VNPAYQR", "VNBANK", "INTCARD");
    private static final Set<String> VNPAY_LOCALES = Set.of("vn", "en");
    private static final DateTimeFormatter PAYMENT_CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final PaymentIdempotencyService paymentIdempotencyService;
    private final PaymentBusinessApplierFactory businessApplierFactory;
    private final PaymentProviderClientFactory providerClientFactory;
    private final PaymentTransactionRepository paymentTransactionRepository;

    // Entry point có idempotency riêng cho payment theo spec.
    @Override
    public CreatePaymentResponse createPayment(Long memberId,
                                               String idempotencyKey,
                                               String clientIp,
                                               CreatePaymentRequest request) {
        PaymentProvider provider = request.provider();
        PaymentPurpose purpose = request.purpose();
        return paymentIdempotencyService.execute(
                provider,
                purpose,
                memberId,
                idempotencyKey,
                request,
                CreatePaymentResponse.class,
                () -> createPaymentInternal(memberId, idempotencyKey.trim(), normalizeClientIp(clientIp), request)
        );
    }

    @Override
    public PaymentResponse getPayment(Long memberId, Long paymentId) {
        // Scope query theo member để user không thể đoán id và đọc payment của người khác.
        PaymentTransaction transaction = paymentTransactionRepository.findByIdAndMemberId(paymentId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        return toPaymentResponse(transaction);
    }

    @Override
    public PaymentResponse getPaymentByCode(Long memberId, String paymentCode) {
        // paymentCode đến từ redirect/polling nhưng vẫn phải bind với member hiện tại.
        PaymentTransaction transaction = paymentTransactionRepository.findByPaymentCodeAndMemberId(paymentCode, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        return toPaymentResponse(transaction);
    }

    private CreatePaymentResponse createPaymentInternal(Long memberId,
                                                        String idempotencyKey,
                                                        String clientIp,
                                                        CreatePaymentRequest request) {
        // Frontend không gửi amount; business applier tự validate target và tính amount/currency.
        String locale = normalizeLocale(request);
        String bankCode = normalizeBankCode(request);

        PaymentBusinessApplier applier = businessApplierFactory.get(request.purpose(), request.targetType());
        PayableTarget target = applier.validatePayableTarget(memberId, request.targetId());
        validateDuplicatePayment(memberId, target.purpose(), target.targetType(), target.targetId());
        validateProviderCurrency(request.provider(), target.currency());

        Instant expiredAt = Instant.now().plus(PAYMENT_EXPIRATION);
        String paymentCode = generatePaymentCode();

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setPaymentCode(paymentCode);
        transaction.setMemberId(memberId);
        transaction.setProvider(request.provider());
        transaction.setPurpose(target.purpose());
        transaction.setTargetType(target.targetType());
        transaction.setTargetId(target.targetId());
        transaction.setAmount(target.amount());
        transaction.setCurrency(target.currency());
        transaction.setStatus(PaymentStatus.PENDING);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setExpiredAt(expiredAt);
        PaymentTransaction savedTransaction = paymentTransactionRepository.saveAndFlush(transaction);

        // PaymentService chỉ gọi provider qua interface/factory, không phụ thuộc VNPAY cụ thể.
        ProviderPaymentCreateResult providerResult = createProviderPayment(
                request.provider(),
                paymentCode,
                target,
                clientIp,
                bankCode,
                locale,
                expiredAt
        );

        savedTransaction.setProviderOrderId(providerResult.providerOrderId());
        savedTransaction.setPaymentUrl(providerResult.paymentUrl());
        savedTransaction.setExpiredAt(providerResult.expiredAt());
        savedTransaction.setProviderMetadata(providerResult.providerMetadata());

        return toResponse(paymentTransactionRepository.save(savedTransaction));
    }

    // Provider layer chịu trách nhiệm build URL/ký request; service chỉ truyền contract generic.
    private ProviderPaymentCreateResult createProviderPayment(PaymentProvider provider,
                                                              String paymentCode,
                                                              PayableTarget target,
                                                              String clientIp,
                                                              String bankCode,
                                                              String locale,
                                                              Instant expiredAt) {
        PaymentProviderClient client = providerClientFactory.get(provider);
        try {
            return client.createPayment(new ProviderPaymentCreateRequest(
                    paymentCode,
                    target.amount(),
                    target.currency(),
                    buildPaymentDescription(target, paymentCode),
                    clientIp,
                    null,
                    null,
                    bankCode,
                    locale,
                    expiredAt
            ));
        } catch (AppException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AppException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }

    private String buildPaymentDescription(PayableTarget target, String paymentCode) {
        String prefix = (target.purpose() == PaymentPurpose.OVERDUE_FINE)
                ? "Nop phat "
                : "Thanh toan ebook ";
        return prefix + target.targetId() + " ma " + paymentCode;
    }

    // Check sớm để trả lỗi rõ ràng; partial unique indexes trong DB vẫn là lớp bảo vệ cuối khi concurrent.
    private void validateDuplicatePayment(Long memberId,
                                          PaymentPurpose purpose,
                                          PaymentTargetType targetType,
                                          Long targetId) {
        if (paymentTransactionRepository.existsByMemberIdAndPurposeAndTargetTypeAndTargetIdAndStatus(
                memberId, purpose, targetType, targetId, PaymentStatus.SUCCESS)) {
            throw new AppException(ErrorCode.PAYMENT_ALREADY_SUCCESS);
        }
        if (paymentTransactionRepository.existsByMemberIdAndPurposeAndTargetTypeAndTargetIdAndStatus(
                memberId, purpose, targetType, targetId, PaymentStatus.PENDING)) {
            throw new AppException(ErrorCode.PAYMENT_ALREADY_PENDING);
        }
    }

    // MVP VNPAY chỉ nhận VND.
    private void validateProviderCurrency(PaymentProvider provider, String currency) {
        if (provider == PaymentProvider.VNPAY && !VND.equalsIgnoreCase(currency)) {
            throw new AppException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }

    // Locale VNPAY mặc định là vn; chỉ cho vn/en để tránh provider reject request.
    private String normalizeLocale(CreatePaymentRequest request) {
        if (request.provider() != PaymentProvider.VNPAY) {
            return request.locale();
        }

        String locale = StringUtils.hasText(request.locale())
                ? request.locale().trim().toLowerCase(Locale.ROOT)
                : "vn";
        if (!VNPAY_LOCALES.contains(locale)) {
            throw new AppException(ErrorCode.INVALID_PAYMENT_LOCALE);
        }
        return locale;
    }

    // Bank code optional. Null nghĩa là cho user tự chọn phương thức trên trang VNPAY.
    private String normalizeBankCode(CreatePaymentRequest request) {
        if (!StringUtils.hasText(request.bankCode())) {
            return null;
        }

        String bankCode = request.bankCode().trim().toUpperCase(Locale.ROOT);
        if (request.provider() == PaymentProvider.VNPAY && !VNPAY_BANK_CODES.contains(bankCode)) {
            throw new AppException(ErrorCode.INVALID_PAYMENT_BANK_CODE);
        }
        return bankCode;
    }

    private String normalizeClientIp(String clientIp) {
        if (!StringUtils.hasText(clientIp)) {
            return "127.0.0.1";
        }
        return clientIp.trim();
    }

    // paymentCode dùng làm provider order id/vnp_TxnRef nên phải unique.
    private String generatePaymentCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String paymentCode = "PAY"
                    + PAYMENT_CODE_DATE.format(Instant.now())
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
            if (!paymentTransactionRepository.existsByPaymentCode(paymentCode)) {
                return paymentCode;
            }
        }
        throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // Response cho frontend redirect, không trả provider metadata nội bộ.
    private CreatePaymentResponse toResponse(PaymentTransaction transaction) {
        return new CreatePaymentResponse(
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
                transaction.getExpiredAt()
        );
    }

    private PaymentResponse toPaymentResponse(PaymentTransaction transaction) {
        // Response public không expose provider raw payload/metadata vì chúng là dữ liệu tích hợp nội bộ.
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
