package com.vn.repository;

import com.vn.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

// Dùng ở các spec callback/IPN để lưu từng lần provider gọi về.
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
}
