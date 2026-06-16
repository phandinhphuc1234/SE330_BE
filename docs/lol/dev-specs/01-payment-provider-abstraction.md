# Spec 01: Payment Provider Abstraction

## Mục tiêu

Thiết kế payment theo SOLID để sau này thêm cổng mới như MoMo, ZaloPay, Stripe mà không phải sửa nhiều logic trong `PaymentService`.

MVP chỉ implement `VNPAY`, nhưng code phải đi qua interface.

## Design yêu cầu

`PaymentService` không được gọi trực tiếp class cụ thể như `VnpayPaymentProviderClient`.

Đúng:

```java
PaymentProviderClient client = providerClientFactory.get(request.provider());
ProviderPaymentCreateResult result = client.createPayment(providerRequest);
```

Không đúng:

```java
vnpayPaymentProviderClient.buildPaymentUrl(...);
```

## Interface chính

```java
public interface PaymentProviderClient {
    PaymentProvider supports();

    ProviderPaymentCreateResult createPayment(ProviderPaymentCreateRequest request);

    ProviderCallbackVerificationResult verifyCallback(ProviderCallbackRequest request);

    ProviderReturnResult parseReturn(ProviderReturnRequest request);
}
```

## DTO contract

```java
public record ProviderPaymentCreateRequest(
        String paymentCode,
        Long amount,
        String currency,
        String description,
        String clientIp,
        String returnUrl,
        String ipnUrl,
        String bankCode,
        String locale,
        Instant expiredAt
) {}
```

```java
public record ProviderPaymentCreateResult(
        PaymentProvider provider,
        String providerOrderId,
        String paymentUrl,
        Instant expiredAt,
        Map<String, Object> providerMetadata
) {}
```

```java
public record ProviderCallbackRequest(
        PaymentProvider provider,
        Map<String, String> params,
        Map<String, String> headers,
        String rawBody
) {}
```

```java
public record ProviderCallbackVerificationResult(
        PaymentProvider provider,
        boolean signatureValid,
        String providerOrderId,
        String providerTransactionId,
        Long amount,
        String currency,
        boolean paymentSuccess,
        String responseCode,
        String transactionStatus,
        Instant paidAt,
        Map<String, Object> providerMetadata,
        Map<String, Object> rawPayload
) {}
```

```java
public record ProviderReturnResult(
        PaymentProvider provider,
        boolean signatureValid,
        String providerOrderId,
        String providerTransactionId,
        String responseCode,
        Map<String, Object> rawPayload
) {}
```

## Factory

```java
public class PaymentProviderClientFactory {
    private final Map<PaymentProvider, PaymentProviderClient> clients;

    public PaymentProviderClient get(PaymentProvider provider) {
        PaymentProviderClient client = clients.get(provider);
        if (client == null) {
            throw new BusinessException("UNSUPPORTED_PAYMENT_PROVIDER");
        }
        return client;
    }
}
```

## Ranh giới trách nhiệm

`PaymentService` chịu trách nhiệm:

```text
Validate request chung.
Idempotency.
Tạo payment_transactions.
Gọi provider client qua interface.
Lưu payment_url, provider_order_id, provider metadata.
```

`PaymentProviderClient` chịu trách nhiệm:

```text
Build provider payment URL.
Ký request.
Verify callback.
Parse callback về DTO generic.
Parse return URL về DTO generic.
Không cấp loan.
Không biết nghiệp vụ ebook.
```

`PaymentBusinessApplier` chịu trách nhiệm:

```text
Validate target nghiệp vụ.
Tính amount nghiệp vụ.
Apply success sau khi payment SUCCESS.
```

## Acceptance criteria

```text
PaymentService chỉ phụ thuộc PaymentProviderClient interface.
Thêm provider mới chỉ cần thêm implementation mới và properties mới.
VNPAY-specific params không rò rỉ vào PaymentService.
Callback result được normalize thành ProviderCallbackVerificationResult.
Business ebook không phụ thuộc VNPAY.
```

## Test cases

```text
Factory trả đúng VnpayPaymentProviderClient khi provider = VNPAY.
Factory trả lỗi UNSUPPORTED_PAYMENT_PROVIDER với provider chưa hỗ trợ.
PaymentService mock PaymentProviderClient vẫn tạo được payment.
VNPAY params chỉ xuất hiện trong VnpayPaymentProviderClient test.
```
