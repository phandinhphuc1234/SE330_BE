# Spec 08: VNPAY QueryDr And Refund

## Mục tiêu

Spec này là phase sau MVP.

MVP payment ebook chưa cần implement QueryDr và Refund, nhưng tài liệu VNPAY yêu cầu hiểu rõ hai API này để thiết kế không bị cụt đường:

```text
QueryDr: truy vấn kết quả giao dịch tại VNPAY.
Refund: gửi yêu cầu hoàn tiền giao dịch.
```

Không dùng QueryDr để thay thế IPN trong flow chính. IPN vẫn là nguồn cập nhật realtime chính.

## Khi nào dùng QueryDr

Use cases:

```text
IPN không về hoặc timeout.
User return về frontend nhưng payment vẫn PENDING quá lâu.
Admin cần đối soát một giao dịch.
PaymentReconciliationJob phase sau.
```

Không dùng:

```text
Không gọi QueryDr cho mọi request payment detail.
Không dùng QueryDr để bypass verify IPN.
Không cấp loan trực tiếp từ Return URL.
```

## QueryDr endpoint VNPAY

```text
URL: https://sandbox.vnpayment.vn/merchant_webapi/api/transaction
Method: POST
Content-Type: application/json
Command: querydr
```

Request params:

| Param | Required | Meaning |
|---|---:|---|
| `vnp_RequestId` | Yes | Request id merchant tự sinh, unique trong ngày |
| `vnp_Version` | Yes | `2.1.0` |
| `vnp_Command` | Yes | `querydr` |
| `vnp_TmnCode` | Yes | VNPAY TMN code |
| `vnp_TxnRef` | Yes | `payment.paymentCode` |
| `vnp_OrderInfo` | Yes | Description |
| `vnp_TransactionNo` | No | VNPAY transaction id nếu đã có |
| `vnp_TransactionDate` | Yes | Original pay create date, lấy từ `provider_metadata.vnpCreateDate` |
| `vnp_CreateDate` | Yes | Request time GMT+7 `yyyyMMddHHmmss` |
| `vnp_IpAddr` | Yes | Server IP gọi API |
| `vnp_SecureHash` | Yes | Checksum |

QueryDr checksum data order:

```text
vnp_RequestId
|vnp_Version
|vnp_Command
|vnp_TmnCode
|vnp_TxnRef
|vnp_TransactionDate
|vnp_CreateDate
|vnp_IpAddr
|vnp_OrderInfo
```

Pseudo:

```java
String data = requestId + "|"
        + version + "|"
        + "querydr" + "|"
        + tmnCode + "|"
        + paymentCode + "|"
        + vnpCreateDateFromPayment + "|"
        + requestCreateDate + "|"
        + serverIp + "|"
        + orderInfo;

String secureHash = hmacSha512(hashSecret, data);
```

QueryDr response fields to parse:

```text
vnp_ResponseCode
vnp_Message
vnp_TxnRef
vnp_Amount
vnp_BankCode
vnp_PayDate
vnp_TransactionNo
vnp_TransactionType
vnp_TransactionStatus
vnp_SecureHash
```

QueryDr response checksum data order:

```text
vnp_ResponseId
|vnp_Command
|vnp_ResponseCode
|vnp_Message
|vnp_TmnCode
|vnp_TxnRef
|vnp_Amount
|vnp_BankCode
|vnp_PayDate
|vnp_TransactionNo
|vnp_TransactionType
|vnp_TransactionStatus
|vnp_OrderInfo
|vnp_PromotionCode
|vnp_PromotionAmount
```

Response code meaning for QueryDr:

| Code | Meaning |
|---|---|
| `00` | Query request success |
| `02` | TmnCode invalid |
| `03` | Invalid request format |
| `91` | Transaction not found |
| `94` | Duplicate request in API time window |
| `97` | Invalid checksum |
| `99` | Other errors |

Important:

```text
vnp_ResponseCode == "00" chỉ nói QueryDr request thành công.
Kết quả thanh toán thật phải xem vnp_TransactionStatus.
Chỉ coi giao dịch thanh toán thành công khi vnp_TransactionStatus == "00".
```

## Refund endpoint VNPAY

```text
URL: https://sandbox.vnpayment.vn/merchant_webapi/api/transaction
Method: POST
Content-Type: application/json
Command: refund
```

Refund không nằm trong MVP ebook payment.

Khi implement phase sau, cần có bảng riêng:

```text
payment_refunds
payment_refund_events
```

Không nên update trực tiếp `payment_transactions` thành FAILED sau khi refund. Payment đã SUCCESS vẫn là payment thành công; refund là nghiệp vụ hoàn tiền riêng.

Refund request params:

| Param | Required | Meaning |
|---|---:|---|
| `vnp_RequestId` | Yes | Request id merchant tự sinh, unique trong ngày |
| `vnp_Version` | Yes | `2.1.0` |
| `vnp_Command` | Yes | `refund` |
| `vnp_TmnCode` | Yes | VNPAY TMN code |
| `vnp_TransactionType` | Yes | `02` full refund, `03` partial refund |
| `vnp_TxnRef` | Yes | original `payment.paymentCode` |
| `vnp_Amount` | Yes | refund amount in VNPAY format, amount * 100 |
| `vnp_OrderInfo` | Yes | refund description |
| `vnp_TransactionNo` | No | original VNPAY transaction id nếu có |
| `vnp_TransactionDate` | Yes | original pay create date |
| `vnp_CreateBy` | Yes | user/admin tạo refund |
| `vnp_CreateDate` | Yes | request time GMT+7 |
| `vnp_IpAddr` | Yes | server IP |
| `vnp_SecureHash` | Yes | checksum |

Refund checksum data order:

```text
vnp_RequestId
|vnp_Version
|vnp_Command
|vnp_TmnCode
|vnp_TransactionType
|vnp_TxnRef
|vnp_Amount
|vnp_TransactionNo
|vnp_TransactionDate
|vnp_CreateBy
|vnp_CreateDate
|vnp_IpAddr
|vnp_OrderInfo
```

Refund response code meaning:

| Code | Meaning |
|---|---|
| `00` | Refund request success |
| `02` | TmnCode invalid |
| `03` | Invalid request format |
| `91` | Refund target transaction not found |
| `94` | Refund request already sent / processing |
| `95` | Original transaction not successful, refund refused |
| `97` | Invalid checksum |
| `99` | Other errors |

## SOLID extension

Không nhét QueryDr/Refund vào `PaymentProviderClient` MVP nếu chưa dùng.

Khi làm phase sau, thêm interface riêng:

```java
public interface PaymentProviderMaintenanceClient {
    PaymentProvider supports();

    ProviderQueryResult queryTransaction(ProviderQueryRequest request);

    ProviderRefundResult refund(ProviderRefundRequest request);
}
```

`VnpayPaymentProviderClient` có thể implement thêm interface này, hoặc tách class:

```text
VnpayPaymentProviderClient
VnpayMaintenanceClient
```

Khuyến nghị:

```text
Tách VnpayMaintenanceClient để create payment/callback không phình quá lớn.
```

## Acceptance criteria phase sau

```text
QueryDr request ký đúng pipe-delimited checksum theo tài liệu VNPAY.
QueryDr response verify checksum trước khi đọc status.
QueryDr không tự cấp loan nếu không đi qua reconciliation service có lock/idempotency.
Refund request có request id unique trong ngày.
Refund amount không vượt amount đã thanh toán trừ refund đã xử lý.
Refund success không đổi payment SUCCESS thành FAILED.
Raw request/response được lưu để audit.
```
