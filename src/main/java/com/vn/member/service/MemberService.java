package com.vn.member.service;

import com.vn.member.dto.request.UpdateMyProfileRequest;
import com.vn.member.dto.response.MyProfileResponse;
// Interface member service chưa các service mà member cần
public interface MemberService {

    MyProfileResponse getMyProfile(Long memberId);

    MyProfileResponse updateMyProfile(Long memberId, UpdateMyProfileRequest request);
}

