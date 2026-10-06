package com.vn.shared.controller;

import com.vn.shared.controller.docs.HomeApiDocs;
import com.vn.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HomeController implements HomeApiDocs {

    @GetMapping("/")
    @Override
    public ApiResponse<Map<String, String>> home() {
        return ApiResponse.success("Library API is running", Map.of(
                "service", "QuanLyThuVien",
                "swagger", "/swagger-ui.html",
                "auth", "/api/auth"
        ));
    }
}

