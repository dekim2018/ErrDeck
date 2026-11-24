package com.errdeck.worker.app;

import com.errdeck.worker.domain.ProcessedEntry;
import java.util.List;

/** 계산을 마친 묶음을 저장소들에 쓴다. 쓰기 스레드 하나만 부른다. */
public interface BatchWriter {

    /** 묶음 하나의 쓰기를 준비한다. 단계 구조는 성공할 때까지 같은 쓰기의 {@link BatchWrite#run()} 을 다시 부른다. */
    BatchWrite prepare(List<ProcessedEntry> batch);

    interface BatchWrite {

        /** @throws RuntimeException 저장소 하나라도 쓰지 못했을 때. 다시 부르면 끝난 단계는 건너뛴다 */
        void run();
    }
}
