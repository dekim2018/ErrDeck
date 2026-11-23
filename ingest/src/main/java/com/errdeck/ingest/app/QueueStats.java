package com.errdeck.ingest.app;

import com.errdeck.ingest.domain.Stream;
import java.time.Duration;

public interface QueueStats {

    /** @param oldestAge 가장 오래 기다린 항목의 나이. 비어 있으면 0 */
    record Snapshot(long length, Duration oldestAge) {
    }

    Snapshot snapshot(Stream stream);
}
