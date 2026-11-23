package com.errdeck.worker.app;

import com.errdeck.worker.domain.EventRow;
import java.util.List;

/** 이벤트 저장소. 묶음 하나를 한 번에 쓴다(10.4). */
public interface EventSink {

    /** @throws RuntimeException 쓰지 못했을 때. 묶음은 확인 처리하지 않고 다시 쓴다 */
    void write(List<EventRow> rows);
}
