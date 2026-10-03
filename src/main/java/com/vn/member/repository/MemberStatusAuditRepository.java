package com.vn.member.repository;

import com.vn.member.entity.MemberStatusAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberStatusAuditRepository extends JpaRepository<MemberStatusAudit, Long> {
}
