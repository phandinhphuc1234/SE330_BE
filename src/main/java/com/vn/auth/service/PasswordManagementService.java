package com.vn.auth.service;

import com.vn.auth.dto.request.ChangePasswordRequest;
import com.vn.auth.dto.request.ForgotPasswordRequest;
import com.vn.auth.dto.request.ResetPasswordRequest;

public interface PasswordManagementService {

    void requestPasswordReset(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    void changePassword(Long memberId, ChangePasswordRequest request);
}
