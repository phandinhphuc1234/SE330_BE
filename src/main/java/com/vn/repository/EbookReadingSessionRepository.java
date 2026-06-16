package com.vn.repository;

import com.vn.entity.EbookReadingSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface EbookReadingSessionRepository extends JpaRepository<EbookReadingSession, Long> {

    // Fallback khi Redis miss: tìm session bằng hash, không bao giờ bằng raw token.
    Optional<EbookReadingSession> findBySessionTokenHash(String sessionTokenHash);

    // Refresh/close dùng lock để không đua trạng thái với worker spec sau hoặc request song song.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EbookReadingSession> findByIdAndSessionTokenHash(Long id, String sessionTokenHash);
}
