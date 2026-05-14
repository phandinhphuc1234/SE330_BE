package com.vn.config;

import com.vn.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
//
import org.springframework.security.config.Customizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
// Import HttpMethod để cấu hình rule theo từng HTTP method.
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableAsync // cho @Async trong EmailServiceImpl
//
@RequiredArgsConstructor
public class SecurityConfig {
    // Inject JwtAuthFilter để thêm vào chuỗi filter
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 0. Disable form login mặc định của Spring Security
                .formLogin(form -> form.disable())
                // REST API dùng Bearer token, không dùng Basic Auth popup/default.
                .httpBasic(httpBasic -> httpBasic.disable())
                // Logout được xử lý bằng endpoint /api/auth/logout để clear Redis + cookie.
                .logout(logout -> logout.disable())
                // 1. Tắt CSRF (REST API dùng JWT, không cần CSRF)
                .csrf(csrf -> csrf.disable())

                // 2. Cấu hình CORS
                .cors(Customizer.withDefaults())

                // 3. Stateless session (không lưu session trên server)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 4. Phân quyền endpoint
                .authorizeHttpRequests(auth -> auth
                        // Cho phép truy cập không cần đăng nhập
                        .requestMatchers("/").permitAll()
                        // Các request không cấn bảo vệ
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/resend-verification").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll()
                        // Catalog public read endpoints
                        .requestMatchers(HttpMethod.GET, "/api/books", "/api/books/*", "/api/authors", "/api/categories").permitAll()
                        // Cho phép truy cập swagger-ui và api-docs không cần đăng nhập
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").permitAll()
                        // Catalog management endpoints
                        .requestMatchers(HttpMethod.DELETE, "/api/books/*", "/api/book-copies/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/categories").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/categories/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/books", "/api/books/import-csv", "/api/authors").hasAnyRole("LIBRARIAN", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/books/*", "/api/book-copies/*", "/api/authors/*").hasAnyRole("LIBRARIAN", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/books/*/copies").hasAnyRole("LIBRARIAN", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/books/*/copies/bulk").hasAnyRole("LIBRARIAN", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/books/*/copies").hasAnyRole("LIBRARIAN", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/*/authors").hasAnyRole("LIBRARIAN", "ADMIN")
                        // Tất cả request còn lại phải authenticated
                        .anyRequest().authenticated()
                )

                // 5. Thêm JwtAuthFilter trước UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    // BCrypt để hash password khi register và verify khi login
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

