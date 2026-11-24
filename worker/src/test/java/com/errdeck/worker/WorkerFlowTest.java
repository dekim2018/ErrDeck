package com.errdeck.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.core.envelope.Envelope;
import com.errdeck.core.envelope.EnvelopeCodec;
import com.errdeck.core.envelope.EnvelopeHeader;
import com.errdeck.core.envelope.EnvelopeItem;
import com.errdeck.core.envelope.EnvelopeLimits;
import com.errdeck.core.envelope.ItemType;
import com.errdeck.core.event.Event;
import com.errdeck.core.event.ExceptionInfo;
import com.errdeck.core.event.StackFrame;
import com.errdeck.core.fixtures.ExampleEnvelopes;
import com.errdeck.worker.support.TestInfra;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ingest 가 쌓은 것과 같은 대기열 항목을 넣고, worker 가 이슈를 만들고 건수와 원본을 저장하고 대기열을 비우는지 본다. */
@SpringBootTest(properties = "errdeck.worker.batch-max-wait=200ms")
class WorkerFlowTest extends TestInfra {

    private static final EnvelopeCodec CODEC = new EnvelopeCodec(EnvelopeLimits.DEFAULT);

    @Autowired
    RedisConnectionFactory redis;

    @Autowired
    JdbcClient jdbc;

    @Test
    void 넣은_봉투의_이벤트가_전부_저장되고_대기열이_빈다() {
        long projectId = createProject(jdbc);
        int envelopes = 300;

        try (RedisConnection connection = redis.getConnection()) {
            for (int i = 0; i < envelopes; i++) {
                add(connection, "errdeck:stream:events", projectId, twoExampleEvents());
                add(connection, "errdeck:stream:tx", projectId, transactionOnly());
            }
        }

        // 예제 이벤트 2건은 지문이 다르다. 이슈당 분당 상한 100을 넘으므로 원본은 지문마다 상한까지, 건수는 전부 남는다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(counted(projectId)).isEqualTo(envelopes * 2L));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(length("errdeck:stream:events")).isZero();
            assertThat(length("errdeck:stream:tx")).isZero();
        });
        assertThat(pending("errdeck:stream:events")).isZero();
        assertThat(issues(projectId)).isEqualTo(2);
    }

    @Test
    void 같은_에러_1만_건은_이슈_하나로_남고_건수는_1만_원본은_상한_이하다() {
        long projectId = createProject(jdbc);
        long start = System.currentTimeMillis();

        try (RedisConnection connection = redis.getConnection()) {
            for (int i = 0; i < 200; i++) {
                add(connection, "errdeck:stream:events", projectId, sameError(50));
            }
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(counted(projectId)).isEqualTo(10_000));
        long minutes = (System.currentTimeMillis() - start) / 60_000 + 2;
        assertThat(issues(projectId)).isEqualTo(1);
        assertThat(stored(projectId)).isBetween(100L, 100L * minutes);
    }

    @Test
    void 같은_봉투가_두_번_오면_두_번째는_중복으로_거른다() {
        long projectId = createProject(jdbc);
        byte[] envelope = sameError(5);

        try (RedisConnection connection = redis.getConnection()) {
            add(connection, "errdeck:stream:events", projectId, envelope);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(counted(projectId)).isEqualTo(5));
        try (RedisConnection connection = redis.getConnection()) {
            add(connection, "errdeck:stream:events", projectId, envelope);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(length("errdeck:stream:events")).isZero());

        assertThat(counted(projectId)).isEqualTo(5);
        assertThat(stored(projectId)).isEqualTo(5);
    }

    @Test
    void 풀_수_없는_봉투는_원문을_격리하고_대기열에서_뺀다() {
        long projectId = createProject(jdbc);
        byte[] garbage = "gzip 이 아닌 본문".getBytes(StandardCharsets.UTF_8);

        try (RedisConnection connection = redis.getConnection()) {
            add(connection, "errdeck:stream:events", projectId, garbage);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT raw FROM quarantined_events WHERE project_id = ?").param(projectId).query(byte[].class).list())
                .containsExactly(garbage));
    }

    /** 원본은 상한으로 줄지만 건수는 집계 테이블에 전부 남는다(10.3). */
    private static long counted(long projectId) {
        String sum = clickHouse("SELECT sum(times) FROM issue_stats_1m WHERE project_id = " + projectId).strip();
        return Long.parseLong(sum);
    }

    private static long stored(long projectId) {
        return Long.parseLong(clickHouse("SELECT count() FROM events FINAL WHERE project_id = " + projectId).strip());
    }

    private long issues(long projectId) {
        return jdbc.sql("SELECT count(*) FROM issues WHERE project_id = ?").param(projectId).query(Long.class).single();
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

    /** 같은 스택의 에러 여러 건. event_id 는 모두 다르다. */
    private static byte[] sameError(int count) {
        List<EnvelopeItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Event event = new Event(UUID.randomUUID(), Instant.now(), "error", null, "prod", "r1", "user-" + (i % 7), null, List.of(), 1,
                    List.of(new ExceptionInfo("java.sql.SQLTransientConnectionException", "Connection is not available, request " + i,
                            List.of(new StackFrame("com.app.PaymentService", "charge", "PaymentService.java", 10 + i % 3, null, true)))),
                    null, Map.of(), List.of());
            items.add(new EnvelopeItem(ItemType.EVENT, event.toJson()));
        }
        return CODEC.encode(new Envelope(new EnvelopeHeader("test", Instant.now()), items));
    }

    /** 예제 봉투의 이벤트 2건을 event_id 만 바꿔 담는다. 같은 event_id 는 중복으로 걸러지기 때문이다(6.7). */
    private static byte[] twoExampleEvents() {
        Envelope example = CODEC.read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));
        List<EnvelopeItem> items = example.items().stream()
                .filter(item -> item.type() == ItemType.EVENT)
                .map(item -> {
                    Event e = Event.fromJson(item.payload());
                    Event renamed = new Event(UUID.randomUUID(), e.timestamp(), e.level(), e.message(), e.environment(), e.release(),
                            e.userId(), e.traceId(), e.fingerprint(), 1, e.exceptions(), e.request(), e.tags(), e.breadcrumbs());
                    return new EnvelopeItem(ItemType.EVENT, renamed.toJson());
                })
                .toList();
        return CODEC.encode(new Envelope(example.header(), items));
    }

    /** SDK 는 트랜잭션을 에러와 다른 봉투로 보낸다(11장 끝). */
    private static byte[] transactionOnly() {
        Envelope example = CODEC.read(ExampleEnvelopes.load(ExampleEnvelopes.EXAMPLE));
        return CODEC.encode(new Envelope(example.header(),
                example.items().stream().filter(item -> item.type() == ItemType.TRANSACTION).toList()));
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
