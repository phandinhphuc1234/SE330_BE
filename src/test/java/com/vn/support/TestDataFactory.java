package com.vn.support;

import com.vn.entity.EmailVerification;
import com.vn.entity.Member;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;

import java.time.Instant;

public final class TestDataFactory {

    private TestDataFactory() {
    }

    public static Member activeMember(Long id) {
        return member(id, MemberStatus.ACTIVE);
    }

    public static Member pendingMember(Long id) {
        return member(id, MemberStatus.PENDING_VERIFICATION);
    }

    public static Member inactiveMember(Long id) {
        return member(id, MemberStatus.INACTIVE);
    }

    public static Member bannedMember(Long id) {
        return member(id, MemberStatus.BANNED);
    }

    public static EmailVerification activeEmailVerification(Long id, Member member, String token) {
        return EmailVerification.builder()
                .id(id)
                .member(member)
                .token(token)
                .expiresAt(Instant.now().plusSeconds(3600))
                .isUsed(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .lastSentAt(Instant.now())
                .build();
    }

    public static EmailVerification expiredEmailVerification(Long id, Member member, String token) {
        return EmailVerification.builder()
                .id(id)
                .member(member)
                .token(token)
                .expiresAt(Instant.now().minusSeconds(60))
                .isUsed(false)
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .lastSentAt(Instant.now().minusSeconds(3600))
                .build();
    }

    private static Member member(Long id, MemberStatus status) {
        return Member.builder()
                .id(id)
                .fullName("Member " + id)
                .email("member" + id + "@example.com")
                .password("hashed-password")
                .phone("0123456789")
                .role(MemberRole.MEMBER)
                .status(status)
                .maxBorrowLimit(5)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}

