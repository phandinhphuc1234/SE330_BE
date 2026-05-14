package com.vn.service.impl;

import com.vn.dto.member.request.UpdateMyProfileRequest;
import com.vn.dto.member.response.MyProfileResponse;
import com.vn.entity.Member;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.MemberMapper;
import com.vn.repository.MemberRepository;
import com.vn.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberServiceImpl implements MemberService {

    private final MemberRepository memberRepository;
    private final MemberMapper memberMapper;
    // Gọi API thành công thì phải log ra
    @Override
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(Long memberId) {
        Member member = getMember(memberId);

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.GET_MY_PROFILE, LogResult.SUCCESS, memberId, memberId);

        return memberMapper.toMyProfileResponse(member);
    }

    @Override
    @Transactional
    public MyProfileResponse updateMyProfile(Long memberId, UpdateMyProfileRequest request) {
        Member member = getMember(memberId);
        updateAllowedProfileFields(member, request);

        Member savedMember = memberRepository.save(member);

        log.info("eventType={} result={} memberId={} entityType=MEMBER entityId={}",
                LogEvent.UPDATE_MY_PROFILE, LogResult.SUCCESS, memberId, memberId);

        return memberMapper.toMyProfileResponse(savedMember);
    }

    private Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    // Logic update user cho hệ thống
    private void updateAllowedProfileFields(Member member, UpdateMyProfileRequest request) {
        if (request.fullName() != null) {
            member.setFullName(request.fullName().trim());
        }

        if (request.phone() != null) {
            member.setPhone(normalizeOptionalPhone(request.phone()));
        }
    }

    private String normalizeOptionalPhone(String phone) {
        String normalizedPhone = phone.trim();
        return normalizedPhone.isBlank() ? null : normalizedPhone;
    }
}

