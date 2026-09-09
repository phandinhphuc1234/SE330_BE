package com.vn.service.impl.importer.job;

import com.vn.dto.catalog.response.BookImportJobResponse;
import com.vn.enums.BookImportJobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BookImportSseService {

    private static final long TIMEOUT_MILLIS = 15 * 60 * 1000L;
    private static final String SNAPSHOT_EVENT = "book-import-snapshot";

    private final ConcurrentHashMap<UUID, Set<SseEmitter>> emittersByJobId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId, BookImportJobResponse initialState) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emittersByJobId.computeIfAbsent(jobId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(error -> removeEmitter(jobId, emitter));

        sendToEmitter(jobId, emitter, SNAPSHOT_EVENT, initialState);
        if (isTerminal(initialState)) {
            emitter.complete();
        }

        return emitter;
    }

    public void publish(UUID jobId, String eventName, BookImportJobResponse response) {
        Set<SseEmitter> emitters = emittersByJobId.get(jobId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            sendToEmitter(jobId, emitter, eventName, response);
            if (isTerminal(response)) {
                emitter.complete();
            }
        }
    }

    private void sendToEmitter(UUID jobId, SseEmitter emitter, String eventName, BookImportJobResponse response) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .id(response.jobId().toString())
                    .data(response));
        } catch (IOException | IllegalStateException exception) {
            log.debug("Removing closed CSV import SSE emitter. jobId={} reason={}",
                    jobId, exception.getClass().getSimpleName());
            removeEmitter(jobId, emitter);
        }
    }

    private void removeEmitter(UUID jobId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByJobId.get(jobId);
        if (emitters == null) {
            return;
        }

        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByJobId.remove(jobId);
        }
    }

    private boolean isTerminal(BookImportJobResponse response) {
        return BookImportJobStatus.COMPLETED.name().equals(response.status())
                || BookImportJobStatus.FAILED.name().equals(response.status());
    }
}
