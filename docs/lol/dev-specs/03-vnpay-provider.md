# Spec 03: VNPAY Provider Implementation

## Mục tiêu

Implement `VnpayPaymentProviderClient` như một implementation của `PaymentProviderClient`.

Code VNPAY-specific chỉ nằm trong provider này. `PaymentService` không được biết các field như `vnp_TxnRef`, `vnp_ResponseCode`, `vnp_TransactionStatus`.

## Config

```yaml
payment:
  vnpay:
    enabled: true
    pay-url: https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
    transaction-url: https://sandbox.vnpayment.vn/merchant_webapi/api/transaction
    tmn-code: ${VNPAY_TMN_CODE}
    hash-secret: ${VNPAY_HASH_SECRET}
    return-url: ${APP_BASE_URL}/api/payments/return/vnpay
    ipn-url: ${APP_BASE_URL}/api/payments/ipn/vnpay
    version: 2.1.0
    command: pay
    order-type: other
    locale: vn
    expire-minutes: 15
    request-timeout-ms: 5000
```

Environment variables:

```text
VNPAY_TMN_CODE
VNPAY_HASH_SECRET
APP_BASE_URL
FRONTEND_BASE_URL
```

Runtime requirements:

```text
APP_BASE_URL dùng cho IPN cần public HTTPS URL.
Local sandbox cần ngrok/cloudflared hoặc public tunnel tương đương.
VNPAY PAY URL dùng HTTP GET redirect.
VNPAY IPN dùng HTTP GET server-to-server.
VNPAY Return URL dùng browser redirect.
```

## Create payment params

VNPAY PAY URL:

```text
https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
```

Method:

```text
GET redirect
```

Required params:

| VNPAY param | Required | Mapping |
|---|---:|---|
| `vnp_Version` | Yes | config `2.1.0` |
| `vnp_Command` | Yes | `pay` |
| `vnp_TmnCode` | Yes | config `tmn-code` |
| `vnp_Amount` | Yes | `amount * 100` |
| `vnp_CurrCode` | Yes | `VND` |
| `vnp_TxnRef` | Yes | internal `paymentCode` |
| `vnp_OrderInfo` | Yes | generated description |
| `vnp_OrderType` | Yes | config `other` |
| `vnp_Locale` | Yes | request locale, default `vn` |
| `vnp_ReturnUrl` | Yes | config return URL |
| `vnp_IpAddr` | Yes | client IP |
| `vnp_CreateDate` | Yes | now in GMT+7, `yyyyMMddHHmmss` |
| `vnp_ExpireDate` | Yes | expire time in GMT+7, `yyyyMMddHHmmss` |
| `vnp_SecureHash` | Yes | HMAC SHA512 |

Optional params:

| VNPAY param | Allowed values | Rule |
|---|---|---|
| `vnp_BankCode` | `VNPAYQR`, `VNBANK`, `INTCARD`, bank code | Omit if user should choose method on VNPAY |

Important rules:

```text
currency MVP chỉ VND.
vnp_Amount phải nhân 100, không có dấu phân cách.
vnp_TxnRef phải unique; dùng payment_code unique toàn hệ thống.
vnp_OrderInfo tối đa 255 ký tự, nên dùng tiếng Việt không dấu và tránh ký tự đặc biệt.
Date format dùng timezone Asia/Ho_Chi_Minh hoặc GMT+7: yyyyMMddHHmmss.
Nếu bankCode null/blank thì không đưa vnp_BankCode vào params hoặc hash data.
```

OrderInfo MVP:

```text
Thanh toan ebook {bookEbookId} ma {paymentCode}
```

## Build payment URL

Flow:

```text
1. Build map params vnp_* excluding vnp_SecureHash.
2. Remove null/blank params.
3. Sort params ascending by key.
4. URL encode key and value.
5. Join hashData = key=value&key=value.
6. secureHash = HMAC_SHA512(hashSecret, hashData).
7. queryString = same sorted query + vnp_SecureHash.
8. paymentUrl = payUrl + "?" + queryString.
```

Pseudo:

```java
Map<String, String> params = new TreeMap<>();
params.put("vnp_Version", "2.1.0");
params.put("vnp_Command", "pay");
params.put("vnp_TmnCode", tmnCode);
params.put("vnp_Amount", String.valueOf(amount * 100));
params.put("vnp_CurrCode", "VND");
params.put("vnp_TxnRef", paymentCode);
params.put("vnp_OrderInfo", orderInfo);
params.put("vnp_OrderType", orderType);
params.put("vnp_Locale", locale);
params.put("vnp_ReturnUrl", returnUrl);
params.put("vnp_IpAddr", clientIp);
params.put("vnp_CreateDate", formatVnpayDate(now));
params.put("vnp_ExpireDate", formatVnpayDate(expiredAt));

if (bankCode != null && !bankCode.isBlank()) {
    params.put("vnp_BankCode", bankCode);
}

String hashData = buildEncodedQuery(params);
String secureHash = hmacSha512(hashSecret, hashData);
String paymentUrl = payUrl + "?" + hashData + "&vnp_SecureHash=" + secureHash;
```

## Verify IPN/Return checksum

IPN và Return URL nhận cùng nhóm params từ VNPAY.

Required callback params:

| VNPAY param | Mapping |
|---|---|
| `vnp_TmnCode` | merchant code |
| `vnp_Amount` | paid amount, divided by 100 internally |
| `vnp_BankCode` | bank/wallet code |
| `vnp_BankTranNo` | bank transaction number |
| `vnp_CardType` | ATM/QRCODE/etc |
| `vnp_PayDate` | pay date `yyyyMMddHHmmss` |
| `vnp_OrderInfo` | order info |
| `vnp_TransactionNo` | VNPAY transaction id |
| `vnp_ResponseCode` | response code |
| `vnp_TransactionStatus` | transaction status |
| `vnp_TxnRef` | internal payment code |
| `vnp_SecureHash` | checksum |

Verify rule:

```text
1. Collect params starting with vnp_.
2. Remove vnp_SecureHash.
3. Remove vnp_SecureHashType if present.
4. Sort ascending by key.
5. URL encode key/value.
6. Join key=value&key=value.
7. HMAC SHA512 with VNPAY_HASH_SECRET.
8. Compare case-insensitive with received vnp_SecureHash.
```

## Callback normalization

`verifyCallback` returns `ProviderCallbackVerificationResult`.

Mapping:

```text
signatureValid = checksum valid
providerOrderId = vnp_TxnRef
providerTransactionId = vnp_TransactionNo
amount = Long.parseLong(vnp_Amount) / 100
currency = VND
responseCode = vnp_ResponseCode
transactionStatus = vnp_TransactionStatus
paidAt = parse vnp_PayDate if present
paymentSuccess = responseCode == "00" && transactionStatus == "00"
rawPayload = all vnp_* params
providerMetadata = bank/card/paydate/order info fields
```

Important:

```text
Payment success phải cần cả vnp_ResponseCode == "00" và vnp_TransactionStatus == "00".
Không dùng OR giữa hai field này.
vnp_ResponseCode == "00" nhưng vnp_TransactionStatus != "00" không được cấp loan.
vnp_TransactionStatus == "04" là giao dịch đảo, không cấp loan và nên giữ thông tin để đối soát.
vnp_TransactionStatus == "07" là nghi ngờ gian lận, không cấp loan.
```

Provider metadata gợi ý:

```json
{
  "tmnCode": "CTTVNP01",
  "bankCode": "NCB",
  "bankTranNo": "NCB20170829152730",
  "cardType": "ATM",
  "orderInfo": "Thanh toan ebook 1001 ma PAY202606130001",
  "responseCode": "00",
  "transactionStatus": "00",
  "payDate": "20260613100300"
}
```

## Return URL

`parseReturn` chỉ:

```text
Collect params.
Verify checksum.
Parse providerOrderId = vnp_TxnRef.
Parse responseCode, transactionStatus.
Return ProviderReturnResult.
```

Return URL không được:

```text
Update payment_transactions.
Tạo ebook_loan.
Tin rằng user đã thanh toán thành công chỉ vì browser quay về.
```

Sau Return URL, frontend phải gọi:

```http
GET /api/payments/{paymentId}
```

hoặc:

```http
GET /api/payments/by-code/{paymentCode}
```

để lấy trạng thái thật đã được IPN xử lý.

## VNPAY response and status codes

Common callback `vnp_ResponseCode` values:

| Code | Meaning |
|---|---|
| `00` | Giao dịch thành công |
| `07` | Trừ tiền thành công nhưng nghi ngờ gian lận |
| `09` | Thẻ/tài khoản chưa đăng ký InternetBanking |
| `10` | Xác thực sai quá số lần |
| `11` | Hết hạn chờ thanh toán |
| `12` | Thẻ/tài khoản bị khóa |
| `13` | Sai OTP |
| `24` | Khách hàng hủy giao dịch |
| `51` | Không đủ số dư |
| `65` | Vượt hạn mức |
| `75` | Ngân hàng bảo trì |
| `79` | Sai mật khẩu thanh toán quá số lần |
| `99` | Lỗi khác |

`vnp_TransactionStatus` values:

| Code | Meaning |
|---|---|
| `00` | Giao dịch thành công |
| `01` | Giao dịch chưa hoàn tất |
| `02` | Giao dịch bị lỗi |
| `04` | Giao dịch đảo |
| `05` | VNPAY đang xử lý giao dịch hoàn tiền |
| `06` | VNPAY đã gửi yêu cầu hoàn tiền sang ngân hàng |
| `07` | Giao dịch nghi ngờ gian lận |
| `09` | Hoàn trả bị từ chối |

## Acceptance criteria

```text
buildPaymentUrl tạo URL hợp lệ có vnp_SecureHash.
vnp_Amount luôn nhân 100.
vnp_CreateDate và vnp_ExpireDate dùng GMT+7 format yyyyMMddHHmmss.
bankCode blank thì không đưa vào query/hash.
verifyCallback reject sai checksum.
verifyCallback normalize callback về DTO generic.
paymentSuccess chỉ true khi ResponseCode và TransactionStatus đều là 00.
Return URL không update payment.
Không có logic ebook trong VnpayPaymentProviderClient.
```

## Test cases

```text
Build URL có đủ vnp_Version, vnp_Command, vnp_TmnCode, vnp_Amount, vnp_TxnRef, vnp_ReturnUrl, vnp_ExpireDate, vnp_SecureHash.
Amount 25000 VND thành vnp_Amount 2500000.
Params được sort đúng trước khi ký.
Blank bankCode không có trong hashData.
bankCode VNBANK có trong hashData.
OrderInfo được normalize không dấu/ký tự đặc biệt.
Same params verify hash thành công.
Sửa amount làm verify thất bại.
ResponseCode 00 và TransactionStatus 00 thì paymentSuccess = true.
ResponseCode 00 nhưng TransactionStatus 04 thì paymentSuccess = false.
ResponseCode 07 và TransactionStatus 00 thì paymentSuccess = false.
Return URL valid checksum chỉ trả ProviderReturnResult, không update DB.
```
