package com.vn.service.ebook;

import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class EbookReadingSessionTokenService {

    // 32 bytes = 256-bit token. Token này không phải JWT, chỉ là bearer secret cho reader session.
    private static final int TOKEN_BYTES = 32;
    // HMAC giúp backend xác minh token bằng secret mà không cần lưu raw token.
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";

    // SecureRandom đủ cho token bảo mật; không dùng Random thường cho credential.
    private final SecureRandom secureRandom = new SecureRandom();
    // Secret riêng cho reading session; local có thể fallback JWT_SECRET, production nên tách riêng.
    private final String sessionSecret;

    public EbookReadingSessionTokenService(
            @Value("${app.ebook.reader.session-secret:}") String sessionSecret) {
        this.sessionSecret = sessionSecret;
    }

    // Tạo raw token 256-bit, base64url để frontend đặt vào header an toàn.
    public String generateRawToken() {
        byte[] token = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(token);
        // withoutPadding để header gọn và tránh ký tự '=' không cần thiết.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    // DB/Redis chỉ lưu hash HMAC của raw token; raw token không bao giờ persist.
    public String hashToken(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new AppException(ErrorCode.READING_SESSION_REQUIRED);
        }
        if (!StringUtils.hasText(sessionSecret)) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(sessionSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            // Hash dạng base64url để dùng làm Redis key suffix an toàn.
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(rawToken.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // Hash user-agent để audit mà không lưu raw user-agent dài/nhạy cảm vào session row.
    public String hashUserAgent(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(userAgent.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
