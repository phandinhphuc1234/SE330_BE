package com.vn.loan.repository;

import com.vn.loan.entity.AutoRenewalAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoRenewalAttemptRepository extends JpaRepository<AutoRenewalAttempt, Long> {
}
