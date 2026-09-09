package com.vn.service;

import com.vn.dto.auth.request.ChangePasswordRequest;
import com.vn.dto.auth.request.ForgotPasswordRequest;
import com.vn.dto.auth.request.ResetPasswordRequest;

public interface PasswordManagementService {

    void requestPasswordReset(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    void changePassword(Long memberId, ChangePasswordRequest request);
}
