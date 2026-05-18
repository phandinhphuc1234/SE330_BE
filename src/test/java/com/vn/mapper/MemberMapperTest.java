package com.vn.mapper;

import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.member.response.MyProfileResponse;
import com.vn.entity.Member;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberMapperTest {

    private final MemberMapper memberMapper = new MemberMapper();

    @Test
    void toMember_shouldMapRegistrationRequestToMember() {
        RegistrationRequest request = new RegistrationRequest(
                "Nguyen Van A",
                "user@example.com",
                "Password123",
                "0123456789"
        );

        Member member = memberMapper.toMember(request, "encoded-password");

        assertThat(member.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(member.getEmail()).isEqualTo("user@example.com");
        assertThat(member.getPassword()).isEqualTo("encoded-password");
        assertThat(member.getPhone()).isEqualTo("0123456789");
    }

    @Test
    void toMyProfileResponse_shouldHideSensitiveFields() {
        Member member = TestDataFactory.activeMember(1L);

        MyProfileResponse response = memberMapper.toMyProfileResponse(member);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.fullName()).isEqualTo(member.getFullName());
        assertThat(response.email()).isEqualTo(member.getEmail());
        assertThat(response.phone()).isEqualTo(member.getPhone());
        assertThat(response.role()).isEqualTo(member.getRole());
        assertThat(response.status()).isEqualTo(member.getStatus());
    }
}

