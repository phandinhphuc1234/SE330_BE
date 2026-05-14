package com.vn.entity;

import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========================
    // BASIC INFO
    // ========================
    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(unique = true, length = 100, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(length = 20)
    private String phone;

    // ========================
    // AUTH / SECURITY
    // ========================
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    // ========================
    // BUSINESS LOGIC
    // ========================
    private Integer maxBorrowLimit;

    private Instant membershipExpiresAt;

    // ========================
    // AUDIT
    // ========================
    private Instant createdAt;

    private Instant updatedAt;

    // ========================
    // LIFECYCLE HOOK
    // ========================
    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();

        if (this.status == null) {
            this.status = MemberStatus.PENDING_VERIFICATION;
        }

        if (this.role == null) {
            this.role = MemberRole.MEMBER;
        }

        if (this.maxBorrowLimit == null) {
            this.maxBorrowLimit = 5;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
