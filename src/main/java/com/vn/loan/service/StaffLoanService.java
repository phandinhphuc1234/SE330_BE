package com.vn.loan.service;

import com.vn.loan.dto.staff.loan.response.StaffLoanResponse;
import org.springframework.data.domain.Page;

public interface StaffLoanService {

    Page<StaffLoanResponse> searchLoans(String q,
                                        String status,
                                        Boolean openOnly,
                                        Boolean overdue,
                                        String dueFrom,
                                        String dueTo,
                                        int page,
                                        int size);

    Page<StaffLoanResponse> searchMemberLoans(Long memberId,
                                              String status,
                                              Boolean openOnly,
                                              Boolean overdue,
                                              int page,
                                              int size);
}
