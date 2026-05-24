package com.vn.logging;

import com.vn.security.MemberUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
// Ưu tiên filter này chạy sớm nhất trong filter chain
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    // Request đi vào trong sẽ được gắn 1 traceId vào
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
            long durationMs = System.currentTimeMillis() - startedAt;
            int statusCode = response.getStatus();
            log.info(
                    "eventType={} result={} memberId={} method={} path={} statusCode={} durationMs={}",
                    LogEvent.HTTP_REQUEST_COMPLETED,
                    statusCode >= 400 ? LogResult.FAILED : LogResult.SUCCESS,
                    currentMemberId(),
                    request.getMethod(),
                    request.getRequestURI(),
                    statusCode,
                    durationMs
            );
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.error(
                    "eventType={} result={} memberId={} method={} path={} statusCode={} durationMs={} reason={}",
                    LogEvent.HTTP_REQUEST_COMPLETED,
                    LogResult.FAILED,
                    currentMemberId(),
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs,
                    ex.getClass().getSimpleName(),
                    ex
            );
            throw ex;
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
    // Những URL sẽ không tiến hành logging ở trong hệ thống
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        return path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api-docs")
                || path.startsWith("/v3/api-docs")
                || path.equals("/favicon.ico")
                || path.startsWith("/actuator/health")
                || path.startsWith("/actuator/info")
                || path.startsWith("/actuator/prometheus")
                || path.startsWith("/actuator/metrics");
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    private String currentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof MemberUserDetails userDetails)) {
            return "anonymous";
        }
        return String.valueOf(userDetails.getMember().getId());
    }
}

