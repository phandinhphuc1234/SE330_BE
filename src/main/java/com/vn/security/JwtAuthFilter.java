package com.vn.security;

import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.service.RedisTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Filter chạy trước mọi request để validate JWT
// Flow: Extract token → Check blacklist → Validate → Set SecurityContext
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {
    // Các service cần thiết
    private final JwtService jwtService;
    private final MemberUserDetailsService memberUserDetailsService;
    private final RedisTokenService redisTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. Lấy token từ header "Authorization: Bearer xxx"
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Không có token → để Spring Security xử lý (anonymous hoặc reject)
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7); // bỏ "Bearer "

        // 2. Kiểm tra token có bị blacklist không (đã logout)
        if (redisTokenService.isBlacklisted(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Validate token
        // Nếu token không hợp lệ → bỏ qua việc xác thực ở filter này,
// cho request đi tiếp để Spring Security xử lý theo cấu hình authorizeHttpRequests
        if (!jwtService.isValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. Extract email từ token và tìm member
        String email = jwtService.extractEmail(token);

        // Chỉ set context nếu chưa có ai authenticated
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                MemberUserDetails userDetails = (MemberUserDetails) memberUserDetailsService.loadUserByUsername(email);

                // 5. Tạo authentication token và set vào SecurityContext
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null, // credentials không cần vì đã validate JWT
                                userDetails.getAuthorities()
                        );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                // In log ra nếu user không tồn taijntrong
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (UsernameNotFoundException ex) {
                log.warn("eventType={} result={} reason=MEMBER_NOT_FOUND method={} path={}",
                        LogEvent.JWT_AUTHENTICATION,
                        LogResult.FAILED,
                        request.getMethod(),
                        request.getServletPath());
            }
        }

        filterChain.doFilter(request, response);
    }
    // Phương thức của class OncePerRequestFilter
    // Không áp dụng filter cho các endpoint auth (login, register, verify...)
    // Các endpoint nào cần authentication thì phải
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        return ("POST".equals(method) && (
                "/api/auth/register".equals(path)
                        || "/api/auth/login".equals(path)
                        || "/api/auth/refresh".equals(path)
                        || "/api/auth/resend-verification".equals(path)
        )) || ("GET".equals(method) && "/api/auth/verify-email".equals(path));
    }
}

