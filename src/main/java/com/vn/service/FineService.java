package com.vn.service;

import com.vn.dto.circulation.response.FineResponse;
import org.springframework.data.domain.Page;

public interface FineService {

    Page<FineResponse> getMyFines(Long memberId, int page, int size);
}
