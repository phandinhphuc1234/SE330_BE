package com.vn.service.impl;

import com.vn.dto.circulation.response.FineResponse;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.FineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class FineServiceImpl implements FineService {

    private static final int MAX_PAGE_SIZE = 10;

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationMapper circulationMapper;

    // Chức năng: lấy các khoản phạt đã được tính cho member hiện tại.
    @Override
    @Transactional(readOnly = true)
    public Page<FineResponse> getMyFines(Long memberId, int page, int size) {
        return borrowRecordRepository
                .findByMemberIdAndFineAmountGreaterThanOrderByFineCalculatedAtDesc(
                        memberId,
                        BigDecimal.ZERO,
                        buildPageable(page, size)
                )
                .map(circulationMapper::toFineResponse);
    }

    // Chức năng: tạo Pageable và giới hạn page size cho API danh sách tiền phạt.
    private Pageable buildPageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
