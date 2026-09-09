package com.vn.repository;

import com.vn.entity.MemberStatusAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberStatusAuditRepository extends JpaRepository<MemberStatusAudit, Long> {
}
