package com.vn.service.impl;

import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.request.CheckoutRequest;
import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.dto.circulation.response.CheckoutPreviewResponse;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.enums.BorrowStatus;
import com.vn.mapper.CirculationMapper;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.CirculationService;
import com.vn.service.IdempotencyService;
import com.vn.service.impl.circulation.usecase.CheckinUseCase;
import com.vn.service.impl.circulation.usecase.CheckoutUseCase;
import com.vn.service.impl.circulation.usecase.RenewalUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CirculationServiceImpl implements CirculationService {

    private static final int MAX_PAGE_SIZE = 10;

    private final CheckoutUseCase checkoutUseCase;
    private final CheckinUseCase checkinUseCase;
    private final RenewalUseCase renewalUseCase;
    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationMapper circulationMapper;
    private final IdempotencyService idempotencyService;

    // Chức năng: kiểm tra trước một lượt mượn sách và trả về các lý do bị chặn nếu chưa đủ điều kiện.
    @Override
    @Transactional(readOnly = true)
    public CheckoutPreviewResponse previewCheckout(CheckoutRequest request) {
        return checkoutUseCase.previewCheckout(request);
    }

    // Chức năng: tạo lượt mượn sách cho staff, có idempotency để tránh tạo trùng khi retry request.
    @Override
    public BorrowResponse checkout(Long actorId, String idempotencyKey, CheckoutRequest request) {
        return idempotencyService.execute(
                actorId,
                "POST",
                "/api/staff/circulation/checkouts",
                idempotencyKey,
                request,
                BorrowResponse.class,
                () -> checkoutUseCase.checkout(request)
        );
    }

    // Chức năng: xử lý trả sách, cập nhật trạng thái bản sách và tính tiền phạt quá hạn nếu có.
    @Override
    public CheckinResponse checkin(Long actorId, String idempotencyKey, CheckinRequest request) {
        return idempotencyService.execute(
                actorId,
                "POST",
                "/api/staff/circulation/checkins",
                idempotencyKey,
                request,
                CheckinResponse.class,
                () -> checkinUseCase.checkin(request)
        );
    }

    // Chức năng: cho member tự gia hạn lượt mượn của chính mình.
    @Override
    public RenewBorrowResponse renewMyBorrow(Long actorId, String idempotencyKey, Long borrowId, RenewBorrowRequest request) {
        return idempotencyService.execute(
                actorId,
                "PUT",
                "/api/borrows/{borrowId}/extend",
                idempotencyKey,
                request,
                RenewBorrowResponse.class,
                () -> renewalUseCase.renew(actorId, false, borrowId, request)
        );
    }

    // Chức năng: cho staff gia hạn lượt mượn thay cho member.
    @Override
    public RenewBorrowResponse staffRenewBorrow(Long actorId, String idempotencyKey, Long borrowId, RenewBorrowRequest request) {
        return idempotencyService.execute(
                actorId,
                "PUT",
                "/api/staff/borrows/{borrowId}/extend",
                idempotencyKey,
                request,
                RenewBorrowResponse.class,
                () -> renewalUseCase.renew(actorId, true, borrowId, request)
        );
    }

    // Chức năng: lấy danh sách lượt mượn đang còn hiệu lực của member hiện tại.
    @Override
    @Transactional(readOnly = true)
    public Page<BorrowResponse> getMyActiveBorrows(Long memberId, int page, int size) {
        return borrowRecordRepository
                .findByMemberIdAndStatusInOrderByBorrowedAtDesc(memberId, BorrowStatus.activeStatuses(), buildPageable(page, size))
                .map(circulationMapper::toBorrowResponse);
    }

    // Chức năng: lấy lịch sử mượn/trả sách của member hiện tại.
    @Override
    @Transactional(readOnly = true)
    public Page<BorrowResponse> getMyBorrowHistory(Long memberId, int page, int size) {
        return borrowRecordRepository
                .findByMemberIdOrderByBorrowedAtDesc(memberId, buildPageable(page, size))
                .map(circulationMapper::toBorrowResponse);
    }

    // Chức năng: tạo Pageable và giới hạn page size để bảo vệ API danh sách.
    private Pageable buildPageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
