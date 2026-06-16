package com.vn.service.payment.provider.vnpay;

import com.vn.config.VnpayPaymentProperties;
import com.vn.dto.payment.provider.ProviderCallbackRequest;
import com.vn.dto.payment.provider.ProviderCallbackVerificationResult;
import com.vn.dto.payment.provider.ProviderPaymentCreateRequest;
import com.vn.dto.payment.provider.ProviderPaymentCreateResult;
import com.vn.dto.payment.provider.ProviderReturnRequest;
import com.vn.dto.payment.provider.ProviderReturnResult;
import com.vn.enums.PaymentProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.service.payment.provider.PaymentProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class VnpayPaymentProviderClient implements PaymentProviderClient {

    private static final String VND = "VND";
    private static final String SUCCESS_CODE = "00";
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNPAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter VNPAY_FORMATTER_WITH_ZONE = VNPAY_DATE_FORMATTER
            .withZone(VNPAY_ZONE);

    private final VnpayPaymentProperties properties;

    @Override
    public PaymentProvider supports() {
        return PaymentProvider.VNPAY;
    }

    @Override
    public ProviderPaymentCreateResult createPayment(ProviderPaymentCreateRequest request) {
        // Build PAY URL từ contract generic; PaymentService không biết các field vnp_*.
        validateCreateRequest(request);
        String returnUrl = resolveReturnUrl(request);
        String ipnUrl = resolveIpnUrl(request);
        log.info("VNPAY returnUrl={}", returnUrl);
        log.info("VNPAY ipnUrl={}", ipnUrl);

        String createDate = formatVnpayDate(Instant.now());
        String expireDate = formatVnpayDate(request.expiredAt());
        String locale = StringUtils.hasText(request.locale()) ? request.locale() : "vn";
        String orderInfo = normalizeOrderInfo(request.description());

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", properties.getVersion());
        params.put("vnp_Command", properties.getCommand());
        params.put("vnp_TmnCode", properties.getTmnCode());
        // VNPAY yêu cầu amount nhân 100 dù tiền hệ thống đang lưu whole VND.
        params.put("vnp_Amount", String.valueOf(request.amount() * 100));
        params.put("vnp_CurrCode", VND);
        params.put("vnp_TxnRef", request.paymentCode());
        params.put("vnp_OrderInfo", orderInfo);
        params.put("vnp_OrderType", properties.getOrderType());
        params.put("vnp_Locale", locale);
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", request.clientIp());
        params.put("vnp_CreateDate", createDate);
        params.put("vnp_ExpireDate", expireDate);

        // Nếu bankCode rỗng thì bỏ khỏi query/hash để user tự chọn phương thức trên VNPAY.
        if (StringUtils.hasText(request.bankCode())) {
            params.put("vnp_BankCode", request.bankCode());
        }

        String queryString = buildEncodedQuery(params);
        String secureHash = hmacSha512(properties.getHashSecret(), queryString);
        String paymentUrl = properties.getPaymentUrl() + "?" + queryString + "&vnp_SecureHash=" + secureHash;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("vnpCreateDate", createDate);
        metadata.put("vnpExpireDate", expireDate);
        metadata.put("bankCode", request.bankCode());
        metadata.put("locale", locale);
        metadata.put("orderInfo", orderInfo);
        metadata.put("returnUrl", returnUrl);
        metadata.put("ipnUrl", ipnUrl);

        return new ProviderPaymentCreateResult(
                PaymentProvider.VNPAY,
                request.paymentCode(),
                paymentUrl,
                request.expiredAt(),
                metadata
        );
    }

    @Override
    public ProviderCallbackVerificationResult verifyCallback(ProviderCallbackRequest request) {
        // IPN callback dùng signature để chứng minh payload thật sự đến từ VNPAY.
        Map<String, String> params = request == null ? Map.of() : request.params();
        Map<String, Object> rawPayload = collectRawVnpayPayload(params);
        boolean signatureValid = isSignatureValid(params);
        String responseCode = getParam(params, "vnp_ResponseCode");
        String transactionStatus = getParam(params, "vnp_TransactionStatus");

        return new ProviderCallbackVerificationResult(
                PaymentProvider.VNPAY,
                signatureValid,
                getParam(params, "vnp_TxnRef"),
                getParam(params, "vnp_TransactionNo"),
                parseVnpayAmount(getParam(params, "vnp_Amount")),
                VND,
                SUCCESS_CODE.equals(responseCode) && SUCCESS_CODE.equals(transactionStatus),
                responseCode,
                transactionStatus,
                parseVnpayPayDate(getParam(params, "vnp_PayDate")),
                buildProviderMetadata(params),
                rawPayload
        );
    }

    @Override
    public ProviderReturnResult parseReturn(ProviderReturnRequest request) {
        // Return URL chỉ phục vụ frontend hiển thị kết quả; không cập nhật DB ở flow này.
        Map<String, String> params = request == null ? Map.of() : request.params();
        return new ProviderReturnResult(
                PaymentProvider.VNPAY,
                isSignatureValid(params),
                getParam(params, "vnp_TxnRef"),
                getParam(params, "vnp_TransactionNo"),
                getParam(params, "vnp_ResponseCode"),
                collectRawVnpayPayload(params)
        );
    }

    private void validateCreateRequest(ProviderPaymentCreateRequest request) {
        if (!StringUtils.hasText(properties.getTmnCode())
                || !StringUtils.hasText(properties.getHashSecret())
                || !StringUtils.hasText(properties.getPaymentUrl())
                || Boolean.FALSE.equals(properties.getEnabled())) {
            throw new AppException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
        if (request == null
                || !StringUtils.hasText(request.paymentCode())
                || request.amount() == null
                || request.amount() <= 0
                || request.expiredAt() == null
                || !StringUtils.hasText(resolveReturnUrl(request))
                || !StringUtils.hasText(request.clientIp())) {
            throw new AppException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
        if (!VND.equalsIgnoreCase(request.currency())) {
            throw new AppException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }

    private String resolveReturnUrl(ProviderPaymentCreateRequest request) {
        return StringUtils.hasText(request.returnUrl()) ? request.returnUrl() : properties.getReturnUrl();
    }

    private String resolveIpnUrl(ProviderPaymentCreateRequest request) {
        return StringUtils.hasText(request.ipnUrl()) ? request.ipnUrl() : properties.getIpnUrl();
    }

    // Giữ orderInfo ngắn và ổn định vì provider giới hạn độ dài.
    private String normalizeOrderInfo(String description) {
        String normalized = StringUtils.hasText(description) ? description.trim() : "Thanh toan ebook";
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private String formatVnpayDate(Instant instant) {
        return VNPAY_FORMATTER_WITH_ZONE.format(instant);
    }

    // VNPAY ký trên chuỗi params đã sort key tăng dần và URL-encode.
    private String buildEncodedQuery(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) {
                continue;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(urlEncode(entry.getKey()))
                    .append('=')
                    .append(urlEncode(entry.getValue()));
        }
        return query.toString();
    }

    private boolean isSignatureValid(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }

        String receivedHash = getParam(params, "vnp_SecureHash");
        if (!StringUtils.hasText(receivedHash) || !StringUtils.hasText(properties.getHashSecret())) {
            return false;
        }

        Map<String, String> signedParams = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith("vnp_")) {
                continue;
            }
            if ("vnp_SecureHash".equals(key) || "vnp_SecureHashType".equals(key)) {
                continue;
            }
            if (StringUtils.hasText(entry.getValue())) {
                signedParams.put(key, entry.getValue());
            }
        }

        String hashData = buildEncodedQuery(signedParams);
        String expectedHash = hmacSha512(properties.getHashSecret(), hashData);
        return expectedHash.equalsIgnoreCase(receivedHash);
    }

    private Map<String, Object> collectRawVnpayPayload(Map<String, String> params) {
        Map<String, Object> rawPayload = new LinkedHashMap<>();
        if (params == null) {
            return rawPayload;
        }
        params.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().startsWith("vnp_"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> rawPayload.put(entry.getKey(), entry.getValue()));
        return rawPayload;
    }

    private Map<String, Object> buildProviderMetadata(Map<String, String> params) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tmnCode", getParam(params, "vnp_TmnCode"));
        metadata.put("bankCode", getParam(params, "vnp_BankCode"));
        metadata.put("bankTranNo", getParam(params, "vnp_BankTranNo"));
        metadata.put("cardType", getParam(params, "vnp_CardType"));
        metadata.put("orderInfo", getParam(params, "vnp_OrderInfo"));
        metadata.put("responseCode", getParam(params, "vnp_ResponseCode"));
        metadata.put("transactionStatus", getParam(params, "vnp_TransactionStatus"));
        metadata.put("payDate", getParam(params, "vnp_PayDate"));
        return metadata;
    }

    private String getParam(Map<String, String> params, String key) {
        return params == null ? null : params.get(key);
    }

    private Long parseVnpayAmount(String amount) {
        if (!StringUtils.hasText(amount)) {
            return null;
        }
        try {
            return Long.parseLong(amount) / 100;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Instant parseVnpayPayDate(String payDate) {
        if (!StringUtils.hasText(payDate)) {
            return null;
        }
        try {
            return LocalDateTime.parse(payDate, VNPAY_DATE_FORMATTER)
                    .atZone(VNPAY_ZONE)
                    .toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // SecureHash dùng HMAC SHA512 với secret merchant.
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
            throw new AppException(ErrorCode.PAYMENT_PROVIDER_ERROR);
        }
    }
}
