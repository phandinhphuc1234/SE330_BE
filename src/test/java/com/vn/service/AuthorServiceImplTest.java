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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
        PageRequest pageable = PageRequest.of(0, 6, Sort.by(Sort.Direction.ASC, "name"));
        when(authorRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(author), pageable, 1));
        when(authorMapper.toAuthorResponse(author)).thenReturn(response);

        Page<AuthorResponse> result = authorService.getAuthors(" ", null, 0, 6);

        assertThat(result.getContent()).containsExactly(response);
        assertThat(result.getSize()).isEqualTo(6);
        verify(authorRepository).findAll(pageable);
    }

    @Test
    void getAuthors_shouldSearchByQName_whenNameMissing() {
        Author author = author(2L, "Martin Fowler");
        AuthorResponse response = response(2L, "Martin Fowler");
        PageRequest pageable = PageRequest.of(1, 6, Sort.by(Sort.Direction.ASC, "name"));
        when(authorRepository.findByNameContainingIgnoreCase("martin", pageable))
                .thenReturn(new PageImpl<>(List.of(author), pageable, 7));
        when(authorMapper.toAuthorResponse(author)).thenReturn(response);

        Page<AuthorResponse> result = authorService.getAuthors(" Martin ", null, 1, 6);

        assertThat(result.getContent()).containsExactly(response);
        verify(authorRepository).findByNameContainingIgnoreCase("martin", pageable);
    }

    @Test
    void getAuthors_shouldPreferNameOverQ_whenBothProvided() {
        Author author = author(3L, "Kent Beck");
        AuthorResponse response = response(3L, "Kent Beck");
        PageRequest pageable = PageRequest.of(0, 6, Sort.by(Sort.Direction.ASC, "name"));
        when(authorRepository.findByNameContainingIgnoreCase("kent", pageable))
                .thenReturn(new PageImpl<>(List.of(author), pageable, 1));
        when(authorMapper.toAuthorResponse(author)).thenReturn(response);

        Page<AuthorResponse> result = authorService.getAuthors("martin", " Kent ", 0, 6);

        assertThat(result.getContent()).containsExactly(response);
        verify(authorRepository).findByNameContainingIgnoreCase("kent", pageable);
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
