package com.vn.book.mapper;

import com.vn.book.service.image.BookImageUrlResolverFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BookImageMapperTest {
    @Test
    void missingCover_shouldMapToNullWithoutDereferencingImage() {
        BookImageUrlResolverFactory factory = mock(BookImageUrlResolverFactory.class);
        assertThat(new BookImageMapper(factory).toCoverManagementResponse(null, null)).isNull();
        verifyNoInteractions(factory);
    }
}
