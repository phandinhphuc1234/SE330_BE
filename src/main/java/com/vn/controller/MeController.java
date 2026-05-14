package com.vn.controller;

import com.vn.controller.docs.MeApiDocs;
import com.vn.dto.member.request.UpdateMyProfileRequest;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.member.response.MyProfileResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
// Controller cho lấy các thông tin cá nhân
public class MeController implements MeApiDocs {

    private final MemberService memberService;

    // Trả về thông tin cá nhân của User
    // Authentication Principal
    @GetMapping
    @Override
    public ResponseEntity<ApiResponse<MyProfileResponse>> getMyProfile(
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        Long memberId = getCurrentMemberId(userDetails);
        MyProfileResponse profile = memberService.getMyProfile(memberId);

        return ResponseEntity.ok(ApiResponse.success("Lấy thông tin cá nhân thành công", profile));
    }

    // Update thông tin cá nhân của user
    @PatchMapping
    @Override
    public ResponseEntity<ApiResponse<MyProfileResponse>> updateMyProfile(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody UpdateMyProfileRequest request) {
        Long memberId = getCurrentMemberId(userDetails);
        MyProfileResponse profile = memberService.updateMyProfile(memberId, request);

        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin cá nhân thành công", profile));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}

