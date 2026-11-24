package com.errdeck.worker.app;

import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.Tick;
import java.util.List;

/** ClickHouse. 테이블마다 묶음 하나를 한 번에 쓴다(10.4). */
public interface EventStore {

    void writeEvents(List<EventRow> rows);

    void writeTicks(List<Tick> ticks);
}
