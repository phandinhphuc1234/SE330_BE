package com.vn.repository;

import com.vn.entity.AutoRenewalAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoRenewalAttemptRepository extends JpaRepository<AutoRenewalAttempt, Long> {
}
