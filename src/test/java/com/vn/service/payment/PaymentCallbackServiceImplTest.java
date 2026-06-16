package com.vn.service.payment;

import com.vn.dto.payment.provider.ProviderCallbackVerificationResult;
import com.vn.dto.payment.response.PaymentIpnResponse;
import com.vn.dto.payment.response.PaymentResponse;
import com.vn.entity.PaymentEvent;
import com.vn.entity.PaymentTransaction;
import com.vn.enums.PaymentEventProcessingStatus;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentStatus;
import com.vn.enums.PaymentTargetType;
import com.vn.exception.AppException;
import com.vn.repository.PaymentEventRepository;
import com.vn.repository.PaymentTransactionRepository;
import com.vn.service.impl.PaymentCallbackServiceImpl;
import com.vn.service.payment.business.PaymentBusinessApplier;
import com.vn.service.payment.business.PaymentBusinessApplierFactory;
import com.vn.service.payment.provider.PaymentProviderClient;
import com.vn.service.payment.provider.PaymentProviderClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCallbackServiceImplTest {

    private PaymentEventRepository paymentEventRepository;
    private PaymentTransactionRepository paymentTransactionRepository;
    private PaymentProviderClientFactory providerClientFactory;
    private PaymentProviderClient providerClient;
    private PaymentBusinessApplierFactory businessApplierFactory;
    private PaymentBusinessApplier businessApplier;
    private PaymentCallbackServiceImpl service;

    @BeforeEach
    void setUp() {
        paymentEventRepository = mock(PaymentEventRepository.class);
        paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        providerClientFactory = mock(PaymentProviderClientFactory.class);
        providerClient = mock(PaymentProviderClient.class);
        businessApplierFactory = mock(PaymentBusinessApplierFactory.class);
        businessApplier = mock(PaymentBusinessApplier.class);
        service = new PaymentCallbackServiceImpl(
                paymentEventRepository,
                paymentTransactionRepository,
                providerClientFactory,
                businessApplierFactory
        );

        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(providerClientFactory.get(PaymentProvider.VNPAY)).thenReturn(providerClient);
    }

    @Test
    void handleVnpayIpnShouldRejectInvalidSignature() {
        when(providerClient.verifyCallback(any())).thenReturn(verification(false, true, 25_000L));

        PaymentIpnResponse response = service.handleVnpayIpn(vnpayParams(), Map.of("host", "localhost"));

        assertThat(response.rspCode()).isEqualTo("97");
        verify(paymentTransactionRepository, never()).findLockedByProviderAndPaymentCode(any(), any());
        assertLastEventStatus(PaymentEventProcessingStatus.FAILED, false);
    }

    @Test
    void handleVnpayIpnShouldReturnOrderNotFoundWhenPaymentMissing() {
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.empty());

        PaymentIpnResponse response = service.handleVnpayIpn(vnpayParams(), Map.of());

        assertThat(response.rspCode()).isEqualTo("01");
        assertLastEventStatus(PaymentEventProcessingStatus.FAILED, true);
    }

    @Test
    void handleVnpayIpnShouldReturnInvalidAmountWithoutUpdatingPayment() {
        PaymentTransaction payment = pendingPayment();
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 99_999L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));

        PaymentIpnResponse response = service.handleVnpayIpn(vnpayParams(), Map.of());

        assertThat(response.rspCode()).isEqualTo("04");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(businessApplier, never()).applySuccess(any());
        assertLastEventStatus(PaymentEventProcessingStatus.FAILED, true);
    }

    @Test
    void handleVnpayIpnShouldMarkPaymentSuccessAndApplyBusinessSideEffect() {
        PaymentTransaction payment = pendingPayment();
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));
        when(businessApplierFactory.get(PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK))
                .thenReturn(businessApplier);

        PaymentIpnResponse response = service.handleVnpayIpn(vnpayParams(), Map.of());

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getProviderTransactionId()).isEqualTo("14123456");
        assertThat(payment.getPaidAt()).isEqualTo(Instant.parse("2026-06-13T03:03:00Z"));
        verify(businessApplier).applySuccess(payment);
        assertLastEventStatus(PaymentEventProcessingStatus.PROCESSED, true);
    }

    @Test
    void handleVnpayIpnShouldMarkProviderFailureAndNotCreateLoan() {
        PaymentTransaction payment = pendingPayment();
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, false, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));

        PaymentIpnResponse response = service.handleVnpayIpn(vnpayParams(), Map.of());

        assertThat(response.rspCode()).isEqualTo("00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("24");
        verify(businessApplier, never()).applySuccess(any());
        assertLastEventStatus(PaymentEventProcessingStatus.PROCESSED, true);
    }

    @Test
    void handleVnpayIpnShouldIgnoreDuplicateTerminalPayment() {
        PaymentTransaction payment = pendingPayment();
        payment.setStatus(PaymentStatus.SUCCESS);
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));

        PaymentIpnResponse response = service.handleVnpayIpn(vnpayParams(), Map.of());

        assertThat(response.rspCode()).isEqualTo("02");
        verify(businessApplier, never()).applySuccess(any());
        assertLastEventStatus(PaymentEventProcessingStatus.IGNORED, true);
    }

    @Test
    void confirmVnpayReturnShouldMarkPaymentSuccessAndApplyBusinessSideEffect() {
        PaymentTransaction payment = pendingPayment();
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));
        when(businessApplierFactory.get(PaymentPurpose.EBOOK_PAYMENT, PaymentTargetType.BOOK_EBOOK))
                .thenReturn(businessApplier);

        PaymentResponse response = service.confirmVnpayReturn(10L, vnpayParams(), Map.of("host", "localhost"));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.paymentCode()).isEqualTo("PAY202606130001");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getProviderTransactionId()).isEqualTo("14123456");
        verify(businessApplier).applySuccess(payment);
        assertLastEventStatus(PaymentEventProcessingStatus.PROCESSED, true);
    }

    @Test
    void confirmVnpayReturnShouldRejectInvalidSignature() {
        when(providerClient.verifyCallback(any())).thenReturn(verification(false, true, 25_000L));

        assertThatThrownBy(() -> service.confirmVnpayReturn(10L, vnpayParams(), Map.of()))
                .isInstanceOf(AppException.class);

        verify(paymentTransactionRepository, never()).findLockedByProviderAndPaymentCode(any(), any());
        assertLastEventStatus(PaymentEventProcessingStatus.FAILED, false);
    }

    @Test
    void confirmVnpayReturnShouldRejectDifferentMember() {
        PaymentTransaction payment = pendingPayment();
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.confirmVnpayReturn(99L, vnpayParams(), Map.of()))
                .isInstanceOf(AppException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(businessApplier, never()).applySuccess(any());
        assertLastEventStatus(PaymentEventProcessingStatus.FAILED, true);
    }

    @Test
    void confirmVnpayReturnShouldBeIdempotentForTerminalPayment() {
        PaymentTransaction payment = pendingPayment();
        payment.setStatus(PaymentStatus.SUCCESS);
        when(providerClient.verifyCallback(any())).thenReturn(verification(true, true, 25_000L));
        when(paymentTransactionRepository.findLockedByProviderAndPaymentCode(PaymentProvider.VNPAY, "PAY202606130001"))
                .thenReturn(Optional.of(payment));

        PaymentResponse response = service.confirmVnpayReturn(10L, vnpayParams(), Map.of());

        assertThat(response.status()).isEqualTo("SUCCESS");
        verify(businessApplier, never()).applySuccess(any());
        assertLastEventStatus(PaymentEventProcessingStatus.IGNORED, true);
    }

    private void assertLastEventStatus(PaymentEventProcessingStatus status, Boolean signatureValid) {
        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository, atLeast(2)).save(eventCaptor.capture());
        PaymentEvent event = eventCaptor.getAllValues().get(eventCaptor.getAllValues().size() - 1);
        assertThat(event.getProcessingStatus()).isEqualTo(status);
        assertThat(event.getSignatureValid()).isEqualTo(signatureValid);
        assertThat(event.getRawPayload()).containsKey("vnp_TxnRef");
    }

    private ProviderCallbackVerificationResult verification(boolean signatureValid, boolean success, Long amount) {
        return new ProviderCallbackVerificationResult(
                PaymentProvider.VNPAY,
                signatureValid,
                "PAY202606130001",
                "14123456",
                amount,
                "VND",
                success,
                success ? "00" : "24",
                success ? "00" : "02",
                Instant.parse("2026-06-13T03:03:00Z"),
                Map.of("bankCode", "NCB"),
                new LinkedHashMap<>(vnpayParams())
        );
    }

    private Map<String, String> vnpayParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", "PAY202606130001");
        params.put("vnp_TransactionNo", "14123456");
        params.put("vnp_Amount", "2500000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_SecureHash", "hash");
        return params;
    }

    private PaymentTransaction pendingPayment() {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setId(9001L);
        payment.setPaymentCode("PAY202606130001");
        payment.setMemberId(10L);
        payment.setProvider(PaymentProvider.VNPAY);
        payment.setProviderOrderId("PAY202606130001");
        payment.setPurpose(PaymentPurpose.EBOOK_PAYMENT);
        payment.setTargetType(PaymentTargetType.BOOK_EBOOK);
        payment.setTargetId(1001L);
        payment.setAmount(25_000L);
        payment.setCurrency("VND");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setExpiredAt(Instant.now().plusSeconds(900));
        payment.setProviderMetadata(new LinkedHashMap<>());
        return payment;
    }
}
