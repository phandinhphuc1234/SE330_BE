package com.vn.service.impl.circulation.support;

import com.vn.entity.BookCopy;
import com.vn.entity.Member;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CirculationLookupService {

    private final MemberRepository memberRepository;
    private final BookCopyRepository bookCopyRepository;

    // Chức năng: tìm member cho preview; không throw để preview có thể trả lỗi dạng danh sách.
    public Member findMemberOrNull(Long memberId) {
        return memberRepository.findById(memberId).orElse(null);
    }

    // Chức năng: tìm bản sách cho preview; không lock vì preview không thay đổi dữ liệu.
    public BookCopy findCopyForPreview(String barcode) {
        return bookCopyRepository.findByBarcodeIgnoreCaseAndDeletedAtIsNull(normalizeRequired(barcode))
                .orElse(null);
    }

    // Chức năng: lấy member theo id và chuẩn hóa lỗi khi không tìm thấy.
    public Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chức năng: lock member khi chuẩn bị tạo BorrowRecord để đồng bộ hạn mức giữa sách vật lý và ebook.
    public Member getLockedMember(Long memberId) {
        return memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chức năng: lấy bản sách theo barcode và chuẩn hóa lỗi khi không tìm thấy.
    public BookCopy getCopyByBarcode(String barcode) {
        return bookCopyRepository.findByBarcodeIgnoreCaseAndDeletedAtIsNull(normalizeRequired(barcode))
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Chức năng: chuẩn hóa chuỗi bắt buộc và báo lỗi nếu giá trị rỗng.
    public String normalizeRequired(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
        return normalized;
    }
}
