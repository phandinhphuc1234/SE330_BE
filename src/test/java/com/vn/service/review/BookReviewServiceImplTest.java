package com.vn.service.review;

import com.vn.dto.review.request.CreateReviewRequest;
import com.vn.dto.review.request.UpdateReviewRequest;
import com.vn.dto.review.response.BookReviewResponse;
import com.vn.dto.review.response.BookReviewStatsResponse;
import com.vn.entity.Book;
import com.vn.entity.BookReview;
import com.vn.entity.Member;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.BookReviewMapper;
import com.vn.repository.BookRepository;
import com.vn.repository.BookReviewRepository;
import com.vn.repository.MemberRepository;
import com.vn.repository.projection.BookReviewStatsProjection;
import com.vn.repository.projection.RatingDistributionProjection;
import com.vn.service.impl.BookReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookReviewServiceImplTest {

    @Mock
    private BookReviewRepository bookReviewRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private MemberRepository memberRepository;

    private BookReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BookReviewServiceImpl(
                bookReviewRepository,
                bookRepository,
                memberRepository,
                new BookReviewMapper()
        );
    }

    @Test
    void getBookReviewsShouldValidateBookAndNormalizePagination() {
        Book book = book();
        BookReview review = review(book, member());
        PageRequest pageable = PageRequest.of(0, 100);
        when(bookRepository.findByIdAndDeletedAtIsNull(101L)).thenReturn(Optional.of(book));
        when(bookReviewRepository.findByBookIdOrderByCreatedAtDesc(101L, pageable))
                .thenReturn(new PageImpl<>(List.of(review), pageable, 1));

        var result = service.getBookReviews(101L, -5, 500);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().rating()).isEqualTo(5);
        verify(bookReviewRepository).findByBookIdOrderByCreatedAtDesc(101L, pageable);
    }

    @Test
    void getBookReviewStatsShouldReturnAverageTotalAndCompleteDistribution() {
        BookReviewStatsProjection stats = org.mockito.Mockito.mock(BookReviewStatsProjection.class);
        RatingDistributionProjection fiveStars = org.mockito.Mockito.mock(RatingDistributionProjection.class);
        when(bookRepository.findByIdAndDeletedAtIsNull(101L)).thenReturn(Optional.of(book()));
        when(stats.getAverageRating()).thenReturn(4.5);
        when(stats.getTotalReviews()).thenReturn(2L);
        when(bookReviewRepository.findReviewStatsByBookIds(List.of(101L))).thenReturn(List.of(stats));
        when(fiveStars.getRating()).thenReturn(5);
        when(fiveStars.getTotal()).thenReturn(1L);
        when(bookReviewRepository.findRatingDistributionByBookId(101L)).thenReturn(List.of(fiveStars));

        BookReviewStatsResponse result = service.getBookReviewStats(101L);

        assertThat(result.averageRating()).isEqualTo(4.5);
        assertThat(result.totalReviews()).isEqualTo(2L);
        assertThat(result.ratingDistribution()).hasSize(5);
        assertThat(result.ratingDistribution()).containsEntry(1, 0L).containsEntry(5, 1L);
    }

    @Test
    void createReviewShouldPersistReviewForAuthenticatedMember() {
        Book book = book();
        Member member = member();
        when(bookRepository.findByIdAndDeletedAtIsNull(101L)).thenReturn(Optional.of(book));
        when(bookReviewRepository.existsByBookIdAndMemberId(101L, 7L)).thenReturn(false);
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(bookReviewRepository.saveAndFlush(any(BookReview.class))).thenAnswer(invocation -> {
            BookReview saved = invocation.getArgument(0);
            saved.setId(55L);
            return saved;
        });

        BookReviewResponse result = service.createReview(
                101L, 7L, new CreateReviewRequest(5, "Rất hữu ích")
        );

        assertThat(result.reviewId()).isEqualTo(55L);
        assertThat(result.memberId()).isEqualTo(7L);
        assertThat(result.content()).isEqualTo("Rất hữu ích");
    }

    @Test
    void createReviewShouldRejectExistingReviewBeforeInsert() {
        when(bookRepository.findByIdAndDeletedAtIsNull(101L)).thenReturn(Optional.of(book()));
        when(bookReviewRepository.existsByBookIdAndMemberId(101L, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.createReview(
                101L, 7L, new CreateReviewRequest(4, null)
        )).isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS.getCode());

        verify(memberRepository, never()).findById(any());
        verify(bookReviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateReviewShouldOnlyUpdateOwnedReview() {
        BookReview review = review(book(), member());
        when(bookReviewRepository.findByBookIdAndMemberId(101L, 7L)).thenReturn(Optional.of(review));
        when(bookReviewRepository.save(review)).thenReturn(review);

        BookReviewResponse result = service.updateReview(
                101L, 7L, new UpdateReviewRequest(3, "Đã đọc lại")
        );

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.content()).isEqualTo("Đã đọc lại");
    }

    @Test
    void deleteReviewShouldReturnNotFoundWhenMemberDoesNotOwnOne() {
        when(bookReviewRepository.findByBookIdAndMemberId(101L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteReview(101L, 7L))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.REVIEW_NOT_FOUND.getCode());
    }

    private Book book() {
        return Book.builder().id(101L).title("Clean Code").isbn("9780132350884").build();
    }

    private Member member() {
        return Member.builder().id(7L).fullName("Nguyễn Văn A").email("member@example.com").build();
    }

    private BookReview review(Book book, Member member) {
        return BookReview.builder()
                .id(55L)
                .book(book)
                .member(member)
                .rating(5)
                .content("Rất hữu ích")
                .build();
    }
}
