package com.vn.service.payment;

import com.vn.dto.payment.provider.ProviderPaymentCreateRequest;
import com.vn.dto.payment.provider.ProviderPaymentCreateResult;
import com.vn.dto.payment.request.CreatePaymentRequest;
import com.vn.dto.payment.response.CreatePaymentResponse;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentStatus;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.PaymentTransactionRepository;
import com.vn.service.impl.PaymentServiceImpl;
import com.vn.service.payment.business.PayableTarget;
import com.vn.service.payment.business.PaymentBusinessApplier;
import com.vn.service.payment.business.PaymentBusinessApplierFactory;
import com.vn.service.payment.idempotency.PaymentIdempotencyService;
import com.vn.service.payment.provider.PaymentProviderClient;
import com.vn.service.payment.provider.PaymentProviderClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentServiceImplTest {

    private PaymentIdempotencyService paymentIdempotencyService;
    private PaymentBusinessApplierFactory businessApplierFactory;
    private PaymentBusinessApplier businessApplier;
    private PaymentProviderClientFactory providerClientFactory;
    private PaymentProviderClient providerClient;
    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentIdempotencyService = mock(PaymentIdempotencyService.class);
        businessApplierFactory = mock(PaymentBusinessApplierFactory.class);
        businessApplier = mock(PaymentBusinessApplier.class);
        providerClientFactory = mock(PaymentProviderClientFactory.class);
        providerClient = mock(PaymentProviderClient.class);
        paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        paymentService = new PaymentServiceImpl(
                paymentIdempotencyService,
                businessApplierFactory,
                providerClientFactory,
                paymentTransactionRepository
        );

        when(paymentIdempotencyService.execute(
                any(PaymentProvider.class),
                any(PaymentPurpose.class),
                any(Long.class),
                any(String.class),
                any(),
                eq(CreatePaymentResponse.class),
                any()
        )).thenAnswer(invocation -> invocation.<Supplier<CreatePaymentResponse>>getArgument(6).get());
    }

    @Test
    void createPaymentShouldCallProviderThroughInterfaceAndReturnPendingPayment() {
        CreatePaymentRequest request = createRequest();
        when(businessApplierFactory.get(PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK))
                .thenReturn(businessApplier);
        when(businessApplier.validatePayableTarget(10L, 1001L))
                .thenReturn(new PayableTarget(PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK, 1001L, 25_000L, "VND"));
        when(paymentTransactionRepository.existsByPaymentCode(any())).thenReturn(false);
        when(paymentTransactionRepository.saveAndFlush(any(PaymentTransaction.class))).thenAnswer(invocation -> {
            PaymentTransaction transaction = invocation.getArgument(0);
            transaction.setId(9001L);
            return transaction;
        });
        when(paymentTransactionRepository.save(any(PaymentTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(providerClientFactory.get(PaymentProvider.VNPAY)).thenReturn(providerClient);
        when(providerClient.createPayment(any(ProviderPaymentCreateRequest.class)))
                .thenReturn(new ProviderPaymentCreateResult(
                        PaymentProvider.VNPAY,
                        "PAY202606130001",
                        "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?signed=true",
                        Instant.parse("2026-06-13T10:15:00Z"),
                        Map.of("locale", "vn")
                ));

        CreatePaymentResponse response = paymentService.createPayment(10L, "idem-1", "127.0.0.1", request);

        assertThat(response.paymentId()).isEqualTo(9001L);
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING.name());
        assertThat(response.amount()).isEqualTo(25_000L);
        assertThat(response.paymentUrl()).contains("sandbox.vnpayment.vn");

        ArgumentCaptor<ProviderPaymentCreateRequest> providerRequest = ArgumentCaptor.forClass(ProviderPaymentCreateRequest.class);
        org.mockito.Mockito.verify(providerClient).createPayment(providerRequest.capture());
        assertThat(providerRequest.getValue().amount()).isEqualTo(25_000L);
        assertThat(providerRequest.getValue().currency()).isEqualTo("VND");
        assertThat(providerRequest.getValue().bankCode()).isEqualTo("VNBANK");
        assertThat(providerRequest.getValue().locale()).isEqualTo("vn");
    }

    @Test
    void createPaymentShouldRejectWhenPendingPaymentAlreadyExists() {
        CreatePaymentRequest request = createRequest();
        when(businessApplierFactory.get(PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK))
                .thenReturn(businessApplier);
        when(businessApplier.validatePayableTarget(10L, 1001L))
                .thenReturn(new PayableTarget(PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK, 1001L, 25_000L, "VND"));
        when(paymentTransactionRepository.existsByMemberIdAndPurposeAndTargetTypeAndTargetIdAndStatus(
                10L, PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK, 1001L, PaymentStatus.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> paymentService.createPayment(10L, "idem-1", "127.0.0.1", request))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PAYMENT_ALREADY_PENDING.getCode());
    }

    private CreatePaymentRequest createRequest() {
        return new CreatePaymentRequest(
                PaymentPurpose.EBOOK_PAYMENT,
                PaymentTargetType.BOOK_EBOOK,
                1001L,
                PaymentProvider.VNPAY,
                "VNBANK",
                "vn"
        );
    }
}
