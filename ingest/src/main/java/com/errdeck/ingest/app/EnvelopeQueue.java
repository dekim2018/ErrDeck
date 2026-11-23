package com.errdeck.ingest.app;

import com.errdeck.ingest.domain.Admission;
import com.errdeck.ingest.domain.ProjectKey;
import com.errdeck.ingest.domain.Stream;

/** 대기열. 한도 차감, 길이 확인, 적재를 한 번에 해서 확인과 적재 사이에 다른 요청이 끼지 못하게 한다(adr/0003). */
public interface EnvelopeQueue {

    /**
     * @return {@link Admission.Accepted}, {@link Admission.RateLimited}, {@link Admission.QueueFull} 중 하나
     * @throws RuntimeException 저장소를 쓸 수 없을 때
     */
    Admission admit(ProjectKey key, Stream stream, byte[] body);
}
