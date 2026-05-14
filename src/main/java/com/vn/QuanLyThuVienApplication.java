package com.vn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class QuanLyThuVienApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuanLyThuVienApplication.class, args);
    }

}

