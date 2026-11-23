package com.errdeck.ingest.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.ingest.config.IngestProperties;
import com.errdeck.ingest.domain.Admission;
import com.errdeck.ingest.domain.Platform;
import com.errdeck.ingest.domain.ProjectKey;
import com.errdeck.ingest.domain.Stream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.util.unit.DataSize;

class IngestServiceTest {

    private static final ProjectKey KEY = new ProjectKey(1, Platform.SPRING, 6000, Set.of(), null);

    @Test
    void 진행_중인_Redis_호출이_상한에_닿으면_기다리지_않고_바로_거절한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        EnvelopeQueue blocking = (key, stream, body) -> {
            entered.countDown();
            await(release);
            return new Admission.Accepted();
        };
        IngestService service = new IngestService(blocking, properties(2));

        Thread first = Thread.startVirtualThread(() -> service.ingest(KEY, Stream.EVENTS, new byte[1]));
        Thread second = Thread.startVirtualThread(() -> service.ingest(KEY, Stream.EVENTS, new byte[1]));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        Admission third = service.ingest(KEY, Stream.EVENTS, new byte[1]);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(third).isEqualTo(new Admission.Unavailable());
        assertThat(waited).isLessThan(Duration.ofMillis(100));

        release.countDown();
        first.join();
        second.join();
        assertThat(service.ingest(KEY, Stream.EVENTS, new byte[1])).isEqualTo(new Admission.Accepted());
    }

    @Test
    void Redis_가_느리거나_끊기면_503으로_응답할_결과를_돌려주고_자리를_돌려준다() {
        EnvelopeQueue failing = (key, stream, body) -> {
            throw new QueryTimeoutException("200ms 초과");
        };
        IngestService service = new IngestService(failing, properties(1));

        assertThat(service.ingest(KEY, Stream.EVENTS, new byte[1])).isEqualTo(new Admission.Unavailable());
        // 실패한 호출이 자리를 돌려주지 않으면 상한 1에서 두 번째 호출은 대기열까지 가지 못한다.
        assertThat(service.ingest(KEY, Stream.EVENTS, new byte[1])).isEqualTo(new Admission.Unavailable());
    }

    static IngestProperties properties(int maxInFlight) {
        return new IngestProperties(
                DataSize.ofKilobytes(200),
                new IngestProperties.KeyCache(Duration.ofSeconds(30), 100),
                new IngestProperties.RateLimit(1000, Duration.ofMinutes(2)),
                new IngestProperties.Queue(20_000, 20_000, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new IngestProperties.Redis(maxInFlight));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
