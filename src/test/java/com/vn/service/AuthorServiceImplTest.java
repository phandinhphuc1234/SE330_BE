package com.vn.service;

import com.vn.dto.catalog.response.AuthorResponse;
import com.vn.entity.Author;
import com.vn.mapper.AuthorMapper;
import com.vn.repository.AuthorRepository;
import com.vn.service.impl.AuthorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorServiceImplTest {

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private AuthorMapper authorMapper;

    private AuthorServiceImpl authorService;

    @BeforeEach
    void setUp() {
        authorService = new AuthorServiceImpl(authorRepository, authorMapper);
    }

    @Test
    void getAuthors_shouldReturnAllAuthorsSortedByName_whenSearchBlank() {
        Author author = author(1L, "Robert C. Martin");
        AuthorResponse response = response(1L, "Robert C. Martin");
        when(authorRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))).thenReturn(List.of(author));
        when(authorMapper.toAuthorResponse(author)).thenReturn(response);

        List<AuthorResponse> result = authorService.getAuthors(" ", null);

        assertThat(result).containsExactly(response);
        verify(authorRepository).findAll(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Test
    void getAuthors_shouldSearchByQName_whenNameMissing() {
        Author author = author(2L, "Martin Fowler");
        AuthorResponse response = response(2L, "Martin Fowler");
        when(authorRepository.findByNameContainingIgnoreCaseOrderByNameAsc("martin")).thenReturn(List.of(author));
        when(authorMapper.toAuthorResponse(author)).thenReturn(response);

        List<AuthorResponse> result = authorService.getAuthors(" Martin ", null);

        assertThat(result).containsExactly(response);
        verify(authorRepository).findByNameContainingIgnoreCaseOrderByNameAsc("martin");
    }

    @Test
    void getAuthors_shouldPreferNameOverQ_whenBothProvided() {
        Author author = author(3L, "Kent Beck");
        AuthorResponse response = response(3L, "Kent Beck");
        when(authorRepository.findByNameContainingIgnoreCaseOrderByNameAsc("kent")).thenReturn(List.of(author));
        when(authorMapper.toAuthorResponse(author)).thenReturn(response);

        List<AuthorResponse> result = authorService.getAuthors("martin", " Kent ");

        assertThat(result).containsExactly(response);
        verify(authorRepository).findByNameContainingIgnoreCaseOrderByNameAsc("kent");
    }

    private Author author(Long id, String name) {
        return Author.builder()
                .id(id)
                .name(name)
                .bio("Bio")
                .build();
    }

    private AuthorResponse response(Long id, String name) {
        return new AuthorResponse(id, name, "Bio", null, null);
    }
}
