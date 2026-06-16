package com.vn.service.payment.provider.vnpay;

import com.vn.config.VnpayPaymentProperties;
import com.vn.dto.payment.provider.ProviderCallbackRequest;
import com.vn.dto.payment.provider.ProviderCallbackVerificationResult;
import com.vn.dto.payment.provider.ProviderPaymentCreateRequest;
import com.vn.dto.payment.provider.ProviderPaymentCreateResult;
import com.vn.dto.payment.provider.ProviderReturnRequest;
import com.vn.dto.payment.provider.ProviderReturnResult;
import com.vn.enums.PaymentProvider;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class VnpayPaymentProviderClientTest {

    @Test
    void createPaymentShouldBuildSignedVnpayPaymentUrl() {
        VnpayPaymentProviderClient client = new VnpayPaymentProviderClient(vnpayProperties());

        ProviderPaymentCreateResult result = client.createPayment(new ProviderPaymentCreateRequest(
                "PAY202606130001",
                25_000L,
                "VND",
                "Thanh toan ebook 1001 ma PAY202606130001",
                "127.0.0.1",
                null,
                null,
                "VNBANK",
                "vn",
                Instant.parse("2026-06-13T10:15:00Z")
        ));

        assertThat(result.provider()).isEqualTo(PaymentProvider.VNPAY);
        assertThat(result.providerOrderId()).isEqualTo("PAY202606130001");
        assertThat(result.paymentUrl()).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");

        Map<String, String> params = parseQuery(result.paymentUrl());
        assertThat(params).containsEntry("vnp_TxnRef", "PAY202606130001");
        assertThat(params).containsEntry("vnp_Amount", "2500000");
        assertThat(params).containsEntry("vnp_BankCode", "VNBANK");
        assertThat(params).containsKey("vnp_SecureHash");
        assertThat(result.providerMetadata()).containsKeys("vnpCreateDate", "vnpExpireDate", "bankCode", "locale", "orderInfo");
    }

    @Test
    void createPaymentShouldOmitBlankBankCode() {
        VnpayPaymentProviderClient client = new VnpayPaymentProviderClient(vnpayProperties());

        ProviderPaymentCreateResult result = client.createPayment(new ProviderPaymentCreateRequest(
                "PAY202606130002",
                25_000L,
                "VND",
                "Thanh toan ebook 1001 ma PAY202606130002",
                "127.0.0.1",
                null,
                null,
                " ",
                "vn",
                Instant.parse("2026-06-13T10:15:00Z")
        ));

        assertThat(parseQuery(result.paymentUrl())).doesNotContainKey("vnp_BankCode");
    }

    @Test
    void verifyCallbackShouldNormalizeValidSuccessCallback() {
        VnpayPaymentProviderClient client = new VnpayPaymentProviderClient(vnpayProperties());
        Map<String, String> params = signedCallbackParams("00", "00");

        ProviderCallbackVerificationResult result = client.verifyCallback(new ProviderCallbackRequest(
                PaymentProvider.VNPAY,
                params,
                Map.of(),
                ""
        ));

        assertThat(result.signatureValid()).isTrue();
        assertThat(result.providerOrderId()).isEqualTo("PAY202606130001");
        assertThat(result.providerTransactionId()).isEqualTo("14123456");
        assertThat(result.amount()).isEqualTo(25_000L);
        assertThat(result.currency()).isEqualTo("VND");
        assertThat(result.paymentSuccess()).isTrue();
        assertThat(result.paidAt()).isEqualTo(Instant.parse("2026-06-13T03:03:00Z"));
        assertThat(result.providerMetadata()).containsEntry("transactionStatus", "00");
        assertThat(result.rawPayload()).containsKey("vnp_SecureHash");
    }

    @Test
    void verifyCallbackShouldRejectTamperedAmountChecksum() {
        VnpayPaymentProviderClient client = new VnpayPaymentProviderClient(vnpayProperties());
        Map<String, String> params = signedCallbackParams("00", "00");
        params.put("vnp_Amount", "999999");

        ProviderCallbackVerificationResult result = client.verifyCallback(new ProviderCallbackRequest(
                PaymentProvider.VNPAY,
                params,
                Map.of(),
                ""
        ));

        assertThat(result.signatureValid()).isFalse();
    }

    @Test
    void verifyCallbackShouldRequireBothResponseCodeAndTransactionStatusSuccess() {
        VnpayPaymentProviderClient client = new VnpayPaymentProviderClient(vnpayProperties());

        ProviderCallbackVerificationResult reversed = client.verifyCallback(new ProviderCallbackRequest(
                PaymentProvider.VNPAY,
                signedCallbackParams("00", "04"),
                Map.of(),
                ""
        ));
        ProviderCallbackVerificationResult suspicious = client.verifyCallback(new ProviderCallbackRequest(
                PaymentProvider.VNPAY,
                signedCallbackParams("07", "00"),
                Map.of(),
                ""
        ));

        assertThat(reversed.signatureValid()).isTrue();
        assertThat(reversed.paymentSuccess()).isFalse();
        assertThat(suspicious.signatureValid()).isTrue();
        assertThat(suspicious.paymentSuccess()).isFalse();
    }

    @Test
    void parseReturnShouldOnlyNormalizeReturnPayload() {
        VnpayPaymentProviderClient client = new VnpayPaymentProviderClient(vnpayProperties());
        Map<String, String> params = signedCallbackParams("24", "02");

        ProviderReturnResult result = client.parseReturn(new ProviderReturnRequest(
                PaymentProvider.VNPAY,
                params,
                Map.of(),
                ""
        ));

        assertThat(result.provider()).isEqualTo(PaymentProvider.VNPAY);
        assertThat(result.signatureValid()).isTrue();
        assertThat(result.providerOrderId()).isEqualTo("PAY202606130001");
        assertThat(result.providerTransactionId()).isEqualTo("14123456");
        assertThat(result.responseCode()).isEqualTo("24");
        assertThat(result.rawPayload()).containsKey("vnp_SecureHash");
    }

    private Map<String, String> parseQuery(String paymentUrl) {
        String query = paymentUrl.substring(paymentUrl.indexOf('?') + 1);
        return Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                ));
    }

    private Map<String, String> signedCallbackParams(String responseCode, String transactionStatus) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", "TESTCODE");
        params.put("vnp_Amount", "2500000");
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_BankTranNo", "NCB20260613100300");
        params.put("vnp_CardType", "ATM");
        params.put("vnp_PayDate", "20260613100300");
        params.put("vnp_OrderInfo", "Thanh toan ebook 1001 ma PAY202606130001");
        params.put("vnp_TransactionNo", "14123456");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_TxnRef", "PAY202606130001");
        params.put("vnp_SecureHash", sign(params));
        return params;
    }

    private String sign(Map<String, String> params) {
        return hmacSha512("TESTSECRET", buildEncodedQuery(new TreeMap<>(params)));
    }

    private String buildEncodedQuery(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(entry -> !"vnp_SecureHash".equals(entry.getKey()))
                .filter(entry -> !"vnp_SecureHashType".equals(entry.getKey()))
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String hmacSha512(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
        properties.setLocale("vn");
        properties.setExpireMinutes(15);
        properties.setRequestTimeoutMs(5000);
        properties.setEnabled(true);
        return properties;
    }
}
