package com.vn.service.impl.circulation.policy;

import com.vn.dto.circulation.response.CirculationBlockResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.AutoRenewalResultCode;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;
import com.vn.enums.ReservationStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.ReservationRepository;
import com.vn.service.borrow.MediaBorrowLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CirculationPolicyService {

    private final BorrowRecordRepository borrowRecordRepository;
    private final ReservationRepository reservationRepository;
    private final CirculationSettingService circulationSettingService;
    private final MediaBorrowLimitService mediaBorrowLimitService;

    // Chức năng: validate checkout ở dạng danh sách lỗi để dùng cho màn hình preview.
    public List<CirculationBlockResponse> validateCheckout(Member member, BookCopy copy) {
        return buildCheckoutBlocks(member, copy).stream()
                .map(block -> new CirculationBlockResponse(block.errorCode().getCode(), block.message()))
                .toList();
    }

    // Chức năng: validate checkout ở dạng exception để dùng cho flow checkout thật.
    public void assertCheckoutAllowed(Member member, BookCopy copy) {
        List<PolicyBlock> blocks = buildCheckoutBlocks(member, copy);
        if (!blocks.isEmpty()) {
            throw new AppException(blocks.getFirst().errorCode());
        }
    }

    // Chức năng: kiểm tra tài khoản có đúng vai trò MEMBER, đang active và chưa hết hạn.
    public void assertBorrowerAccountAllowed(Member member) {
        PolicyBlock block = validateBorrowerAccount(member);
        if (block != null) {
            throw new AppException(block.errorCode());
        }
    }

    // Chức năng: kiểm tra hạn mức và sách quá hạn trước khi checkout thật, kể cả checkout từ hold.
    public void assertBorrowingCapacityAllowed(Member member) {
        List<PolicyBlock> blocks = new ArrayList<>();
        validateBorrowingCapacity(member, blocks);
        if (!blocks.isEmpty()) {
            throw new AppException(blocks.getFirst().errorCode());
        }
    }

    // Chức năng: kiểm tra toàn bộ điều kiện gia hạn của một lượt mượn.
    public void assertRenewalAllowed(Long actorId, boolean staffFlow, BorrowRecord borrow) {
        assertBorrowerAccountAllowed(borrow.getMember());
        if (mediaBorrowLimitService.hasUnpaidFines(borrow.getMember().getId())) {
            throw new AppException(ErrorCode.MEMBER_HAS_UNPAID_FINES);
        }
        if (!staffFlow && !borrow.getMember().getId().equals(actorId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (!borrow.getStatus().isRenewable()) {
            throw new AppException(ErrorCode.BORROW_NOT_RENEWABLE);
        }
        if (borrow.getDueDate().isBefore(Instant.now()) && !circulationSettingService.isRenewOverdueAllowed()) {
            throw new AppException(ErrorCode.BORROW_NOT_RENEWABLE);
        }
        if (borrow.getRenewCount() >= borrow.getMaxRenewalsAtCheckout()) {
            throw new AppException(ErrorCode.BORROW_NOT_RENEWABLE);
        }

        Book book = borrow.getBookCopy().getBook();
        if (reservationRepository.existsByBookIdAndStatusIn(book.getId(), ReservationStatus.activeStatuses())) {
            throw new AppException(ErrorCode.RENEWAL_BLOCKED_BY_HOLD);
        }
    }

    // Chức năng: validate auto-renewal ở dạng result code để job ghi rõ lý do success/failure từng borrow.
    public AutoRenewalResultCode validateAutoRenewal(BorrowRecord borrow) {
        if (borrow == null) {
            return AutoRenewalResultCode.BORROW_NOT_FOUND;
        }
        PolicyBlock borrowerBlock = validateBorrowerAccount(borrow.getMember());
        if (borrowerBlock != null) {
            return mapAutoRenewalBorrowerBlock(borrowerBlock.errorCode());
        }
        if (!borrow.getStatus().isRenewable()) {
            return AutoRenewalResultCode.BORROW_NOT_RENEWABLE_STATUS;
        }
        if (borrow.getDueDate().isBefore(Instant.now()) && !circulationSettingService.isRenewOverdueAllowed()) {
            return AutoRenewalResultCode.BORROW_OVERDUE;
        }
        if (borrow.getRenewCount() >= borrow.getMaxRenewalsAtCheckout()) {
            return AutoRenewalResultCode.MAX_RENEWALS_REACHED;
        }

        BookCopy copy = borrow.getBookCopy();
        if (copy == null || copy.getStatus() != BookCopyStatus.BORROWED) {
            return AutoRenewalResultCode.BOOK_COPY_NOT_BORROWED;
        }

        Book book = copy.getBook();
        if (book == null || book.getDeletedAt() != null) {
            return AutoRenewalResultCode.BOOK_DELETED;
        }
        if (reservationRepository.existsByBookIdAndStatusIn(book.getId(), ReservationStatus.activeStatuses())) {
            return AutoRenewalResultCode.BLOCKED_BY_HOLD;
        }
        return AutoRenewalResultCode.SUCCESS;
    }

    // Chức năng: gom rule checkout của member và copy vào một chỗ để preview và checkout thật không bị lệch logic.
    private List<PolicyBlock> buildCheckoutBlocks(Member member, BookCopy copy) {
        List<PolicyBlock> blocks = new ArrayList<>();
        addIfPresent(blocks, validateBorrowerAccount(member));
        if (member != null) {
            validateBorrowingCapacity(member, blocks);
        }
        if (member != null && copy != null && copy.getBook() != null) {
            validateNoOpenBorrowForSameBook(member, copy, blocks);
        }
        validateCopyAvailability(copy, blocks);
        return blocks;
    }

    // Chức năng: kiểm tra các điều kiện nền của tài khoản bạn đọc.
    private PolicyBlock validateBorrowerAccount(Member member) {
        if (member == null) {
            return new PolicyBlock(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy bạn đọc");
        }
        if (member.getRole() != MemberRole.MEMBER) {
            return new PolicyBlock(ErrorCode.BORROWER_MUST_BE_MEMBER, ErrorCode.BORROWER_MUST_BE_MEMBER.getMessage());
        }
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return new PolicyBlock(ErrorCode.MEMBER_NOT_ACTIVE, ErrorCode.MEMBER_NOT_ACTIVE.getMessage());
        }
        if (isMembershipExpired(member)) {
            return new PolicyBlock(ErrorCode.MEMBERSHIP_EXPIRED, ErrorCode.MEMBERSHIP_EXPIRED.getMessage());
        }
        return null;
    }

    // Chức năng: kiểm tra hạn mức mượn và tình trạng đang có sách quá hạn/tiền phạt chưa trả.
    private void validateBorrowingCapacity(Member member, List<PolicyBlock> blocks) {
        if (mediaBorrowLimitService.hasUnpaidFines(member.getId())) {
            blocks.add(new PolicyBlock(ErrorCode.MEMBER_HAS_UNPAID_FINES, "Bạn còn khoản nợ tiền phạt chưa thanh toán"));
        }
        if (mediaBorrowLimitService.hasReachedLimit(member)) {
            blocks.add(new PolicyBlock(ErrorCode.BORROW_LIMIT_EXCEEDED, ErrorCode.BORROW_LIMIT_EXCEEDED.getMessage()));
        }
        if (borrowRecordRepository.existsByMemberIdAndStatus(member.getId(), BorrowStatus.OVERDUE)) {
            blocks.add(new PolicyBlock(ErrorCode.MEMBER_HAS_OVERDUE_ITEMS, ErrorCode.MEMBER_HAS_OVERDUE_ITEMS.getMessage()));
        }
    }

    // Chức năng: chặn member mượn nhiều bản copy khác nhau của cùng một đầu sách.
    private void validateNoOpenBorrowForSameBook(Member member, BookCopy copy, List<PolicyBlock> blocks) {
        if (borrowRecordRepository.existsOpenBorrowForMemberAndBook(
                member.getId(),
                copy.getBook().getId(),
                BorrowStatus.openStatuses()
        )) {
            blocks.add(new PolicyBlock(ErrorCode.MEMBER_ALREADY_BORROWED_BOOK, ErrorCode.MEMBER_ALREADY_BORROWED_BOOK.getMessage()));
        }
    }

    // Chức năng: kiểm tra bản sách có thể bắt đầu một lượt mượn mới hay không.
    private void validateCopyAvailability(BookCopy copy, List<PolicyBlock> blocks) {
        if (copy == null) {
            blocks.add(new PolicyBlock(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy bản sách"));
            return;
        }
        if (copy.getStatus() != BookCopyStatus.AVAILABLE || copy.getBook().getDeletedAt() != null) {
            blocks.add(new PolicyBlock(ErrorCode.BOOK_COPY_NOT_AVAILABLE, ErrorCode.BOOK_COPY_NOT_AVAILABLE.getMessage()));
        }
    }

    // Chức năng: xác định thẻ thành viên đã hết hạn hay chưa.
    private boolean isMembershipExpired(Member member) {
        return member.getMembershipExpiresAt() != null && member.getMembershipExpiresAt().isBefore(Instant.now());
    }

    // Chức năng: thêm lỗi vào danh sách nếu rule có trả lỗi.
    private void addIfPresent(List<PolicyBlock> blocks, PolicyBlock block) {
        if (block != null) {
            blocks.add(block);
        }
    }

    private AutoRenewalResultCode mapAutoRenewalBorrowerBlock(ErrorCode errorCode) {
        if (errorCode == ErrorCode.BORROWER_MUST_BE_MEMBER) {
            return AutoRenewalResultCode.BORROWER_MUST_BE_MEMBER;
        }
        if (errorCode == ErrorCode.MEMBER_NOT_ACTIVE) {
            return AutoRenewalResultCode.MEMBER_NOT_ACTIVE;
        }
        if (errorCode == ErrorCode.MEMBERSHIP_EXPIRED) {
            return AutoRenewalResultCode.MEMBERSHIP_EXPIRED;
        }
        return AutoRenewalResultCode.SYSTEM_ERROR;
    }

    private record PolicyBlock(ErrorCode errorCode, String message) {
    }
}
