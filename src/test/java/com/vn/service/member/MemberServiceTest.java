package com.vn.service.member;

import com.vn.dto.member.request.UpdateMyProfileRequest;
import com.vn.dto.member.response.MyProfileResponse;
import com.vn.entity.Member;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.MemberMapper;
import com.vn.repository.MemberRepository;
import com.vn.service.impl.MemberServiceImpl;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    private MemberServiceImpl memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberServiceImpl(memberRepository, new MemberMapper());
    }

    @Test
    void getMyProfile_shouldReturnCurrentMemberProfile() {
        Member member = TestDataFactory.activeMember(1L);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        MyProfileResponse response = memberService.getMyProfile(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo(member.getEmail());
        assertThat(response.fullName()).isEqualTo(member.getFullName());
    }

    @Test
    void getMyProfile_shouldThrowResourceNotFound_whenMemberDoesNotExist() {
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getMyProfile(99L))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void updateMyProfile_shouldUpdateAllowedFieldsOnly() {
        Member member = TestDataFactory.activeMember(1L);
        UpdateMyProfileRequest request = new UpdateMyProfileRequest("  Nguyen Van B  ", " 0987654321 ");

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MyProfileResponse response = memberService.updateMyProfile(1L, request);

        assertThat(response.fullName()).isEqualTo("Nguyen Van B");
        assertThat(response.phone()).isEqualTo("0987654321");
        assertThat(response.email()).isEqualTo(member.getEmail());
        verify(memberRepository).save(member);
    }

    @Test
    void updateMyProfile_shouldSetPhoneNull_whenPhoneIsBlank() {
        Member member = TestDataFactory.activeMember(1L);
        UpdateMyProfileRequest request = new UpdateMyProfileRequest(null, "   ");

        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MyProfileResponse response = memberService.updateMyProfile(1L, request);

        assertThat(response.fullName()).isEqualTo(member.getFullName());
        assertThat(response.phone()).isNull();
    }
}

