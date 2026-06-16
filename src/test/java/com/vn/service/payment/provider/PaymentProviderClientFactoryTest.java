package com.vn.service.payment.provider;

import com.vn.config.VnpayPaymentProperties;
import com.vn.enums.PaymentProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.service.payment.provider.vnpay.VnpayPaymentProviderClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderClientFactoryTest {

    @Test
    void getShouldReturnVnpayClientWhenProviderIsVnpay() {
        VnpayPaymentProviderClient vnpayClient = new VnpayPaymentProviderClient(vnpayProperties());
        PaymentProviderClientFactory factory = new PaymentProviderClientFactory(List.of(vnpayClient));

        PaymentProviderClient client = factory.get(PaymentProvider.VNPAY);

        assertThat(client).isSameAs(vnpayClient);
    }

    @Test
    void getShouldThrowUnsupportedPaymentProviderWhenProviderIsNotRegistered() {
        PaymentProviderClientFactory factory = new PaymentProviderClientFactory(List.of(new VnpayPaymentProviderClient(vnpayProperties())));

        assertThatThrownBy(() -> factory.get(PaymentProvider.MOMO))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNSUPPORTED_PAYMENT_PROVIDER.getCode());
    }

    private VnpayPaymentProperties vnpayProperties() {
        VnpayPaymentProperties properties = new VnpayPaymentProperties();
        properties.setTmnCode("TESTCODE");
        properties.setHashSecret("TESTSECRET");
        properties.setPaymentUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.setReturnUrl("http://localhost:3000/payment/vnpay-return");
        properties.setIpnUrl("http://localhost:8080/api/payments/ipn/vnpay");
        properties.setVersion("2.1.0");
        properties.setCommand("pay");
        properties.setOrderType("other");
        return properties;
    }
}
