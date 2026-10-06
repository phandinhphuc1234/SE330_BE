package com.vn.rag.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

public interface RagAnswerClient {

    AnswerResponse answer(AnswerRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnswerRequest(
            String question,
            Long ebookId,
            Integer topK,
            BigDecimal scoreThreshold
    ) {
        public static AnswerRequest forEbook(String question,
                                             Long ebookId,
                                             Integer topK,
                                             BigDecimal scoreThreshold) {
            return new AnswerRequest(question, ebookId, topK, scoreThreshold);
        }
    }

    record AnswerResponse(
            String answer,
            boolean grounded,
            boolean abstained,
            String reason,
            List<AnswerCitation> citations,
            String model,
            String promptVersion
    ) {
    }

    record AnswerCitation(
            String documentId,
            Long documentInternalId,
            Long bookId,
            Long ebookId,
            String chapterTitle,
            Integer chapterIndex,
            Integer pageStart,
            Integer pageEnd,
            Integer chunkIndex,
            String chunkId,
            String excerpt,
            Double score
    ) {
    }
}
