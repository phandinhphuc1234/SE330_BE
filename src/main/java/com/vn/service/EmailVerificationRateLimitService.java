package com.vn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class EmailVerificationRateLimitService {
    // Khai báo các hằng số cấu hình cho chức năng resend email verification bằng Redis
    private static final String COOLDOWN_PREFIX = "email:verify:cooldown:";
    private static final String RESEND_COUNT_PREFIX = "email:verify:resend-count:";
    private static final long COOLDOWN_SECONDS = 60;
    private static final long RESEND_WINDOW_SECONDS = 86_400;
    private static final int MAX_RESEND_PER_WINDOW = 5;
    // Khai báo StringRedisTemplate redisTemplate để thao tác với dữ liệu String trên Redis
    private final StringRedisTemplate redisTemplate;
    // Các construtor khởi tạo các giá trị
    public boolean isInCooldown(Long memberId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(memberId)));
    }
    // Nếu member resend quá 5 lần thì trả về flag = true
    public boolean hasExceededResendLimit(Long memberId) {
        return getResendCount(memberId) >= MAX_RESEND_PER_WINDOW;
    }
    // Constructor mỗi lần yêu cầu resend email được gửi thì tăng
    public long incrementResendCount(Long memberId) {
        String key = resendCountKey(memberId);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, RESEND_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        return count == null ? 0L : count;
    }
    // Thời gian giới hạn giữa mỗi lần gửi
    public void startCooldown(Long memberId) {
        redisTemplate.opsForValue().set(cooldownKey(memberId), "1", COOLDOWN_SECONDS, TimeUnit.SECONDS);
    }
    // Xóa toàn bộ trạng thái rate-limit resend email của một member trong Redis.
    public void clear(Long memberId) {
        redisTemplate.delete(cooldownKey(memberId));
        redisTemplate.delete(resendCountKey(memberId));
    }
    // Lấy số lần resend của member trong redis
    private long getResendCount(Long memberId) {
        String value = redisTemplate.opsForValue().get(resendCountKey(memberId));
        return value == null ? 0L : Long.parseLong(value);
    }
    // Tạo tên key Redis cho cooldown gủi lại email xác thực
    private String cooldownKey(Long memberId) {
        return COOLDOWN_PREFIX + memberId;
    }
    // Tạo tên key Redis cho số lần resendCountKey,
    private String resendCountKey(Long memberId) {
        return RESEND_COUNT_PREFIX + memberId;
    }
}

