package com.vn.service;

import com.vn.dto.member.request.UpdateMyProfileRequest;
import com.vn.dto.member.response.MyProfileResponse;
// Interface member service chưa các service mà member cần
public interface MemberService {

    MyProfileResponse getMyProfile(Long memberId);

    MyProfileResponse updateMyProfile(Long memberId, UpdateMyProfileRequest request);
}

