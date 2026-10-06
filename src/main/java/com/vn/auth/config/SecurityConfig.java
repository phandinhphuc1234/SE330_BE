package com.vn.auth.config;

import com.vn.shared.exception.ErrorCode;
import com.vn.auth.security.JwtAuthFilter;
import com.vn.auth.security.SecurityErrorResponseWriter;
import com.vn.auth.security.cookie.CookieProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
//
import org.springframework.security.config.Customizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
// Import HttpMethod để cấu hình rule theo từng HTTP method.
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableAsync // cho @Async trong EmailServiceImpl
//
@RequiredArgsConstructor
public class SecurityConfig {
    // Inject JwtAuthFilter để thêm vào chuỗi filter
    private final JwtAuthFilter jwtAuthFilter;
    private final SecurityErrorResponseWriter securityErrorResponseWriter;
    private final CookieProperties cookieProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 0. Disable form login mặc định của Spring Security
                .formLogin(form -> form.disable())
                // REST API dùng Bearer token, không dùng Basic Auth popup/default.
                .httpBasic(httpBasic -> httpBasic.disable())
                // Logout được xử lý bằng endpoint /api/auth/logout để clear Redis + cookie.
                .logout(logout -> logout.disable())
                // Bearer APIs do not use ambient credentials. Refresh does use an
                // HttpOnly cookie, so it must prove possession of a CSRF token.
                .csrf(csrf -> csrf
                        .requireCsrfProtectionMatcher(SecurityConfig::requiresCookieAuthCsrf)
                        .csrfTokenRepository(cookieCsrfTokenRepository(cookieProperties))
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))

                // 2. Cấu hình CORS
                .cors(Customizer.withDefaults())

                // 3. Stateless session (không lưu session trên server)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Security exceptions phát sinh trước controller vẫn theo ApiResponse.
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, ex) ->
                                securityErrorResponseWriter.write(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, ex) ->
                                securityErrorResponseWriter.write(response, ErrorCode.ACCESS_DENIED)))

                // 4. Phân quyền endpoint
                .authorizeHttpRequests(auth -> auth
                        // Cho phép truy cập không cần đăng nhập
                        .requestMatchers("/").permitAll()
                        // Các request không cấn bảo vệ
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/verify-email",
                                "/api/auth/resend-verification", "/api/auth/forgot-password", "/api/auth/reset-password")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        // Catalog public read endpoints, gồm metadata ebook an toàn để render trang
                        // sách.
                        .requestMatchers(HttpMethod.GET, "/api/books", "/api/books/*", "/api/books/*/ebook",
                                "/api/authors", "/api/categories")
                        .permitAll()
                        // Monitoring endpoints used by local Prometheus/Grafana setup.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        // Cho phép truy cập swagger-ui và api-docs không cần đăng nhập
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**")
                        .permitAll()
                        // VNPAY IPN là server-to-server callback public; bảo mật bằng vnp_SecureHash.
                        .requestMatchers(HttpMethod.GET, "/api/payments/ipn/vnpay").permitAll()
                        // Resend webhook is server-to-server and verifies the raw body with Svix signatures.
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/resend").permitAll()
                        // Payment create APIs require a logged-in member.
                        .requestMatchers(HttpMethod.POST, "/api/payments").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/payments/return/vnpay/confirm").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/payments/receipts", "/api/payments/receipts/*")
                        .authenticated()
                        .requestMatchers("/api/admin/payments", "/api/admin/payments/**").authenticated()
                        // Các API cần được đăng nhập
                        .requestMatchers(HttpMethod.GET, "/api/payments/*", "/api/payments/by-code/*").authenticated()
                        // Ebook loans
                        .requestMatchers("/api/ebook-loans", "/api/ebook-loans/**").authenticated()
                        // Secure ebook reader session APIs require a logged-in member and
                        // X-Reading-Session where applicable.
                        .requestMatchers("/api/ebooks/**").authenticated()
                        // Catalog public read endpoints, gồm metadata ebook an toàn để render trang
                        // sách.
                        .requestMatchers(HttpMethod.GET, "/api/books", "/api/books/*", "/api/books/*/ebook",
                                "/api/authors", "/api/categories")
                        .permitAll()
                        // Book review public read endpoints
                        .requestMatchers(HttpMethod.GET, "/api/books/*/reviews", "/api/books/*/reviews/stats")
                        .permitAll()
                        // Tất cả request còn lại phải authenticated
                        .anyRequest().authenticated())

                // 5. Thêm JwtAuthFilter trước UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    static boolean requiresCookieAuthCsrf(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && "/api/auth/refresh".equals(request.getServletPath());
    }

    static CookieCsrfTokenRepository cookieCsrfTokenRepository(CookieProperties properties) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        // HTTPS uses a host-only cookie prefix so another project/subdomain cannot
        // inject a parent-domain CSRF cookie. Local HTTP keeps a compatible name.
        repository.setCookieName(properties.isRefreshTokenSecure() ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie
                .secure(properties.isRefreshTokenSecure())
                .sameSite(properties.getRefreshTokenSameSite()));
        return repository;
    }

    // BCrypt để hash password khi register và verify khi login
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
