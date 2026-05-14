package com.vn.security;

import com.vn.entity.Member;
import com.vn.enums.MemberStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// Adapter: chuyển Member entity thành UserDetails cho Spring Security
@Getter
@RequiredArgsConstructor
public class MemberUserDetails implements UserDetails {

    private final Member member;

    // Spring Security dùng authorities để phân quyền (ROLE_MEMBER, ROLE_ADMIN, ...)
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
    }

    @Override
    public String getPassword() {
        return member.getPassword();
    }

    // Spring Security dùng username để định danh user — ở đây là email
    @Override
    public String getUsername() {
        return member.getEmail();
    }

    // Tài khoản chỉ active khi status = ACTIVE
    @Override
    public boolean isAccountNonLocked() {
        return member.getStatus() == MemberStatus.ACTIVE;
    }

    @Override
    public boolean isEnabled() {
        return member.getStatus() == MemberStatus.ACTIVE;
    }
}

