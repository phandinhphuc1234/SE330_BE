package com.vn.repository;

import com.vn.entity.Member;
import com.vn.enums.BorrowStatus;
import com.vn.enums.MemberStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // Tìm tài khoản theo email khi đăng nhập hoặc load thông tin user từ security context.
    Optional<Member> findByEmail(String email);

    // Kiểm tra email đã được đăng ký chưa.
    boolean existsByEmail(String email);

    // Tìm kiếm member cho màn staff borrowers.
    @Query("""
            select member
            from Member member
            where (:status is null or member.status = :status)
              and (
                    :qLike is null
                    or lower(member.fullName) like :qLike
                    or lower(member.email) like :qLike
                    or lower(coalesce(member.phone, '')) like :qLike
                    or (:memberId is not null and member.id = :memberId)
              )
              and (
                    :hasOverdue is null
                    or :hasOverdue = false
                    or exists (
                        select borrow.id
                        from BorrowRecord borrow
                        where borrow.member = member
                          and (
                                borrow.status = :overdueStatus
                                or (borrow.status = :borrowedStatus and borrow.dueDate < :now)
                          )
                    )
              )
            """)
    Page<Member> searchStaffMembers(@Param("qLike") String qLike,
                                    @Param("memberId") Long memberId,
                                    @Param("status") MemberStatus status,
                                    @Param("hasOverdue") Boolean hasOverdue,
                                    @Param("overdueStatus") BorrowStatus overdueStatus,
                                    @Param("borrowedStatus") BorrowStatus borrowedStatus,
                                    @Param("now") Instant now,
                                    Pageable pageable);
}

