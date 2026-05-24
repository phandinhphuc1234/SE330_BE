package com.vn.service;

import com.vn.dto.catalog.response.BookCopyResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.enums.BookCopyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.BookCopyMapper;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.BookRepository;
import com.vn.service.impl.BookCopyServiceImpl;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookCopyServiceImplTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private BookCopyRepository bookCopyRepository;

    @Mock
    private BookCopyMapper bookCopyMapper;

    private BookCopyServiceImpl bookCopyService;

    @BeforeEach
    void setUp() {
        bookCopyService = new BookCopyServiceImpl(bookRepository, bookCopyRepository, bookCopyMapper);
    }

    @Test
    void getBookCopies_shouldApplyOptionalFilters() {
        Book book = TestDataFactory.book(10L, 1);
        BookCopy copy = TestDataFactory.bookCopy(50L, book, BookCopyStatus.AVAILABLE);
        BookCopyResponse response = new BookCopyResponse(
                50L,
                10L,
                "LIB-2026-000005",
                BookCopyStatus.AVAILABLE,
                "GOOD",
                "Shelf A",
                null,
                null
        );
        when(bookRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(book));
        when(bookCopyRepository.searchActiveCopiesByBookId(
                10L,
                BookCopyStatus.AVAILABLE,
                "%000005%",
                "%good%",
                "%shelf a%"
        )).thenReturn(List.of(copy));
        when(bookCopyMapper.toBookCopyResponse(copy)).thenReturn(response);

        List<BookCopyResponse> result = bookCopyService.getBookCopies(
                10L,
                "available",
                "000005",
                "Good",
                "Shelf A"
        );

        assertThat(result).containsExactly(response);
        verify(bookCopyRepository).searchActiveCopiesByBookId(
                10L,
                BookCopyStatus.AVAILABLE,
                "%000005%",
                "%good%",
                "%shelf a%"
        );
    }

    @Test
    void getBookCopies_shouldThrowBadRequest_whenStatusInvalid() {
        Book book = TestDataFactory.book(10L, 1);
        when(bookRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(book));

        assertThatThrownBy(() -> bookCopyService.getBookCopies(10L, "UNKNOWN", null, null, null))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BAD_REQUEST.getCode());

        verify(bookCopyRepository, never()).searchActiveCopiesByBookId(
                10L,
                null,
                null,
                null,
                null
        );
    }
}
