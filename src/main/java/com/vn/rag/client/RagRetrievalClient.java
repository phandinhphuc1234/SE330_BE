package com.vn.rag.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface RagRetrievalClient {

    RetrievalResponse search(RetrievalRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RetrievalRequest(
            String query,
            Long bookId,
            Long ebookId,
            Long documentId,
            Integer topK,
            BigDecimal scoreThreshold
    ) {
        public static RetrievalRequest forEbook(String query,
                                                Long ebookId,
                                                Integer topK,
                                                BigDecimal scoreThreshold) {
            return new RetrievalRequest(query, null, ebookId, null, topK, scoreThreshold);
        }
    }

    record RetrievalResponse(
            String queryTextHash,
            String queryTextPolicy,
            String embeddingVersion,
            Integer topK,
            Integer resultCount,
            Map<String, Object> appliedFilters,
            List<RetrievalHit> results
    ) {
    }

    record RetrievalHit(
            String pointId,
            String vectorId,
            Double score,
            String text,
            RetrievalCitation citation,
            Map<String, Object> metadata
    ) {
    }

    record RetrievalCitation(
            String documentId,
            Long documentInternalId,
            Long bookId,
            Long ebookId,
            String chapterTitle,
            Integer chapterIndex,
            Integer pageStart,
            Integer pageEnd,
            Integer chunkIndex,
            String vectorId
    ) {
    }
}
