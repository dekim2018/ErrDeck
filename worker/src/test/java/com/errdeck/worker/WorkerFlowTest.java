package com.errdeck.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Event;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import com.errdeck.worker.support.TestInfra;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;

/** ingest 가 쌓은 것과 같은 대기열 항목을 넣고, worker 가 ClickHouse 에 쓰고 대기열을 비우는지 본다. */
@SpringBootTest(properties = "errdeck.worker.batch-max-wait=200ms")
class WorkerFlowTest extends TestInfra {

    /** 예제 봉투에서 트랜잭션 항목만 남긴 것. SDK 는 트랜잭션을 에러와 다른 봉투로 보낸다(11장 끝). */
    private static final byte[] TRANSACTION = transactionOnly();

    @Autowired
    RedisConnectionFactory redis;

    @Test
    void 넣은_봉투의_이벤트가_전부_저장되고_대기열이_빈다() {
        long projectId = ThreadLocalRandom.current().nextLong(1_000_000, 4_000_000_000L);
        int envelopes = 300;

        try (RedisConnection connection = redis.getConnection()) {
            for (int i = 0; i < envelopes; i++) {
                add(connection, "errdeck:stream:events", projectId, twoEvents());
                add(connection, "errdeck:stream:tx", projectId, TRANSACTION);
            }
        }

        // 봉투마다 이벤트가 2건 있다. tx 대기열의 봉투는 확인 처리만 하고 저장하지 않는다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(stored(projectId)).isEqualTo(envelopes * 2L));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(length("errdeck:stream:events")).isZero();
            assertThat(length("errdeck:stream:tx")).isZero();
        });
        assertThat(pending("errdeck:stream:events")).isZero();
        assertThat(pending("errdeck:stream:tx")).isZero();
        assertThat(clickHouse("SELECT uniqExact(fingerprint) FROM events WHERE project_id = " + projectId).strip()).isEqualTo("2");
    }

    /** 병합으로 숫자가 흔들리지 않게 FINAL 로 센다. event_id 는 봉투마다 새로 만들므로 합쳐질 행이 없다. */
    private static long stored(long projectId) {
        return Long.parseLong(clickHouse("SELECT count() FROM events FINAL WHERE project_id = " + projectId).strip());
    }

    /**
     * 예제 봉투의 이벤트 2건을 event_id 만 바꿔 담는다. 같은 event_id 는 ClickHouse 가 병합 때 하나로 합치므로(6.7)
     * 예제 그대로 여러 번 넣으면 저장 건수를 셀 수 없다.
     */
    private static byte[] transactionOnly() {
        EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
        Envelope example = codec.read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));
        return codec.encode(new Envelope(example.header(),
                example.items().stream().filter(item -> item.type() == ItemType.TRANSACTION).toList()));
    }

    private static byte[] twoEvents() {
        EnvelopeCodec codec = new EnvelopeCodec(EnvelopeLimits.DEFAULT);
        Envelope example = codec.read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));
        List<EnvelopeItem> items = example.items().stream()
                .filter(item -> item.type() == ItemType.EVENT)
                .map(item -> {
                    Event e = Event.fromJson(item.payload());
                    Event renamed = new Event(UUID.randomUUID(), e.timestamp(), e.level(), e.message(), e.environment(), e.release(),
                            e.userId(), e.traceId(), e.fingerprint(), e.count(), e.exceptions(), e.request(), e.tags(), e.breadcrumbs());
                    return new EnvelopeItem(ItemType.EVENT, renamed.toJson());
                })
                .toList();
        return codec.encode(new Envelope(example.header(), items));
    }

    private long length(String key) {
        try (RedisConnection connection = redis.getConnection()) {
            Long length = connection.streamCommands().xLen(key.getBytes(StandardCharsets.UTF_8));
            return length == null ? 0 : length;
        }
    }

    private long pending(String key) {
        try (RedisConnection connection = redis.getConnection()) {
            var summary = connection.streamCommands().xPending(key.getBytes(StandardCharsets.UTF_8), "errdeck-workers");
            return summary == null ? 0 : summary.getTotalPendingMessages();
        }
    }

    private static void add(RedisConnection connection, String key, long projectId, byte[] body) {
        Map<byte[], byte[]> fields = new LinkedHashMap<>();
        fields.put(bytes("project_id"), bytes(Long.toString(projectId)));
        fields.put(bytes("received_at"), bytes(Long.toString(System.currentTimeMillis())));
        fields.put(bytes("body"), body);
        MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord().in(bytes(key)).ofMap(fields);
        connection.streamCommands().xAdd(record);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
