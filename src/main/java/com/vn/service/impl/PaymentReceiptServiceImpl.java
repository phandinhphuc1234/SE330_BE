package com.vn.service.impl;

import com.vn.dto.payment.response.AdminPaymentRowResponse;
import com.vn.dto.payment.response.PaymentDashboardSummaryResponse;
import com.vn.dto.payment.response.PaymentReceiptResponse;
import com.vn.enums.PaymentStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.PaymentTransactionRepository;
import com.vn.repository.projection.PaymentReceiptRowProjection;
import com.vn.service.PaymentReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PaymentReceiptServiceImpl implements PaymentReceiptService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String DEFAULT_CURRENCY = "VND";

    private final PaymentTransactionRepository paymentTransactionRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentReceiptResponse> getMemberReceipts(Long memberId, int page, int size) {
        return paymentTransactionRepository
                .findSuccessfulReceiptsByMember(memberId, buildPageable(page, size))
                .map(this::toReceiptResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentReceiptResponse getMemberReceipt(Long memberId, String paymentCode) {
        return paymentTransactionRepository
                .findSuccessfulReceiptByMemberAndCode(memberId, paymentCode)
                .map(this::toReceiptResponse)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminPaymentRowResponse> searchAdminPayments(String q,
                                                             String status,
                                                             String paidFrom,
                                                             String paidTo,
                                                             int page,
                                                             int size) {
        Instant parsedPaidFrom = parseBoundaryInstant(paidFrom, false);
        Instant parsedPaidTo = parseBoundaryInstant(paidTo, true);
        validateDateRange(parsedPaidFrom, parsedPaidTo);
        return paymentTransactionRepository
                .searchAdminPayments(
                        normalizeLikeQuery(q),
                        parseOptionalLong(q),
                        parsePaymentStatus(status),
                        parsedPaidFrom,
                        parsedPaidTo,
                        buildPageable(page, size)
                )
                .map(this::toAdminPaymentRowResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentReceiptResponse getAdminReceipt(String paymentCode) {
        return paymentTransactionRepository
                .findAdminPaymentByCode(paymentCode)
                .map(this::toReceiptResponse)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentDashboardSummaryResponse getDashboardSummary() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Instant todayStart = today.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        return new PaymentDashboardSummaryResponse(
                paymentTransactionRepository.count(),
                paymentTransactionRepository.countByStatus(PaymentStatus.SUCCESS),
                paymentTransactionRepository.countByStatus(PaymentStatus.PENDING),
                paymentTransactionRepository.countByStatus(PaymentStatus.FAILED),
                safeLong(paymentTransactionRepository.sumAmountByStatus(PaymentStatus.SUCCESS)),
                safeLong(paymentTransactionRepository.sumAmountByStatusAndPaidAtBetween(
                        PaymentStatus.SUCCESS,
                        todayStart,
                        tomorrowStart
                )),
                paymentTransactionRepository.countByStatusAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                        PaymentStatus.SUCCESS,
                        todayStart,
                        tomorrowStart
                ),
                DEFAULT_CURRENCY,
                now
        );
    }

    private PaymentReceiptResponse toReceiptResponse(PaymentReceiptRowProjection row) {
        return new PaymentReceiptResponse(
                row.getPaymentId(),
                row.getReceiptNumber(),
                row.getPaymentCode(),
                row.getMemberId(),
                row.getMemberName(),
                row.getMemberEmail(),
                row.getProvider(),
                row.getProviderTransactionId(),
                row.getProviderResponseCode(),
                row.getProviderTransactionStatus(),
                row.getPurpose(),
                row.getTargetType(),
                row.getTargetId(),
                row.getItemTitle(),
                row.getAmount(),
                row.getCurrency(),
                row.getStatus(),
                row.getPaidAt(),
                row.getCreatedAt()
        );
    }

    private AdminPaymentRowResponse toAdminPaymentRowResponse(PaymentReceiptRowProjection row) {
        return new AdminPaymentRowResponse(
                row.getPaymentId(),
                row.getPaymentCode(),
                row.getMemberId(),
                row.getMemberName(),
                row.getMemberEmail(),
                row.getProvider(),
                row.getProviderTransactionId(),
                row.getPurpose(),
                row.getTargetType(),
                row.getTargetId(),
                row.getItemTitle(),
                row.getAmount(),
                row.getCurrency(),
                row.getStatus(),
                row.getProviderResponseCode(),
                row.getProviderTransactionStatus(),
                row.getPaidAt(),
                row.getExpiredAt(),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private String parsePaymentStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        try {
            PaymentStatus.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private Instant parseBoundaryInstant(String rawValue, boolean endOfDayForDateOnly) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String value = rawValue.trim();
        try {
            if (!value.contains("T")) {
                LocalDate date = LocalDate.parse(value);
                if (endOfDayForDateOnly) {
                    return date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1);
                }
                return date.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private void validateDateRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private Pageable buildPageable(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.unsorted()
        );
    }

    private int normalizeSize(int size) {
        int requestedSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }

    private String normalizeLikeQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private Long parseOptionalLong(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(q.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }
}
