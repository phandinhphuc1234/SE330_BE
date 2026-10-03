package com.vn.loan.service;

import com.vn.loan.dto.response.FineResponse;
import org.springframework.data.domain.Page;

public interface FineService {

    Page<FineResponse> getMyFines(Long memberId, int page, int size);
}
