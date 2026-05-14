package com.vn.mapper;

import com.vn.dto.auth.request.RegistrationRequest;
import com.vn.dto.member.response.MyProfileResponse;
import com.vn.entity.Member;
import org.springframework.stereotype.Component;
// Mapper chỉ làm công việc của nó là mapping từ DTO sang object va ngược lại
// Không nên có business logic trong DTO
@Component
public class MemberMapper {

    public Member toMember(RegistrationRequest request, String encodedPassword) {
        return Member.builder()
                .fullName(request.fullName())
                .email(request.email())
                .password(encodedPassword)
                .phone(request.phone())
                .build();
    }

    public MyProfileResponse toMyProfileResponse(Member member) {
        return new MyProfileResponse(
                member.getId(),
                member.getFullName(),
                member.getEmail(),
                member.getPhone(),
                member.getRole(),
                member.getStatus(),
                member.getMaxBorrowLimit(),
                member.getMembershipExpiresAt(),
                member.getCreatedAt(),
                member.getUpdatedAt()
        );
    }
}

