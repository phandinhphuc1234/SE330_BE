package com.vn.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    // Tên scheme dùng trong @SecurityRequirement
    private static final String BEARER_SCHEME = "Bearer Authentication";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                // Thông tin chung về API
                .info(new Info()
                        .title("Quản Lý Thư Viện API")
                        .description("REST API cho hệ thống quản lý thư viện — "
                                + "bao gồm xác thực, quản lý sách, mượn/trả, thông báo.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Library Team")
                                .email("23521216@gm.uit.edu.vn")))

                // Server environments:Nói cho Swagger/OpenAPI biết API base URL là gì
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development")))

                // Cấu hình Bearer token cho Swagger UI
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Nhập access token (không cần prefix 'Bearer')")))

                // Mặc định yêu cầu Bearer token cho tất cả API
                // Các endpoint public sẽ override bằng @SecurityRequirements(value = {})
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}

