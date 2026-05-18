package com.vn.repository;

import com.vn.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // Tìm tài khoản theo email khi đăng nhập hoặc load thông tin user từ security context.
    Optional<Member> findByEmail(String email);

    // Kiểm tra email đã được đăng ký chưa.
    boolean existsByEmail(String email);
}

