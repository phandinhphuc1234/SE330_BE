package com.vn.rag.job;

import com.vn.rag.service.EbookRagIngestionStatusSyncService;
import com.vn.rag.service.EbookRagIngestionStatusSyncService.SyncSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class EbookRagIngestionStatusSyncJob {

    private final EbookRagIngestionStatusSyncService syncService;

    @Value("${app.rag.ingestion-sync.batch-size:25}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.rag.ingestion-sync.fixed-delay-ms:5000}",
            initialDelayString = "${app.rag.ingestion-sync.initial-delay-ms:10000}",
            scheduler = "taskScheduler"
    )
    public void synchronizeStatuses() {
        try {
            SyncSummary summary = syncService.syncBatch(batchSize);
            if (summary.polled() > 0) {
                log.info(
                        "RAG ingestion status sync completed: polled={} updated={} skipped={} pollFailures={} permanentlyFailed={}",
                        summary.polled(), summary.updated(), summary.skipped(), summary.pollFailures(),
                        summary.permanentlyFailed()
                );
            }
        } catch (RuntimeException exception) {
            log.error("RAG ingestion status sync batch failed", exception);
        }
    }
}
