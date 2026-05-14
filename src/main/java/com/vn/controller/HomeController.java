package com.vn.controller;

import com.vn.controller.docs.HomeApiDocs;
import com.vn.dto.common.ApiResponse;
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

