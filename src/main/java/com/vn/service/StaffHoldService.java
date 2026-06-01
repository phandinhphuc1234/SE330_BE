package com.vn.service;

import com.vn.dto.staff.hold.response.StaffHoldResponse;
import org.springframework.data.domain.Page;

public interface StaffHoldService {

    Page<StaffHoldResponse> searchHolds(String status, int page, int size);
}
