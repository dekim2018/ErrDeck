package com.errdeck.worker.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.worker.app.QueueSource;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import com.errdeck.worker.support.TestInfra;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;

/** 소비 그룹 읽기, 이어받기, 확인 처리와 지우기를 실제 Redis 로 확인한다. 스프링 없이 어댑터만 쓴다. */
class RedisQueueSourceTest extends TestInfra {

    private LettuceConnectionFactory factory;
    private String group;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        // 다른 테스트의 worker 와 섞이지 않게 그룹 이름을 매번 바꾼다.
        group = "g-" + UUID.randomUUID();
        try (RedisConnection connection = factory.getConnection()) {
            connection.keyCommands().del(bytes(QueueStream.EVENTS.key()));
        }
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void 읽은_항목의_본문은_바이트_그대로이고_확인_처리하면_대기열에서_지워진다() {
        RedisQueueSource source = new RedisQueueSource(factory, group, "me");
        source.ensureGroups();
        byte[] body = {0, (byte) 0x8b, (byte) 0xff, 10, 13};
        add(41, body);
        add(42, body);

        List<QueueEntry> entries = source.read(QueueStream.EVENTS, 10, Duration.ofMillis(100));
        source.acknowledge(QueueStream.EVENTS, entries.stream().map(QueueEntry::id).toList());

        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst().body()).isEqualTo(body);
        assertThat(entries.getFirst().projectId()).isEqualTo(41);
        assertThat(entries.getFirst().receivedAt()).isNotNull();
        assertThat(length()).isZero();
        assertThat(pending()).isZero();
        source.close();
    }

    @Test
    void 죽은_worker_가_확인하지_않은_항목을_본문까지_이어받는다() throws Exception {
        RedisQueueSource source = new RedisQueueSource(factory, group, "me");
        source.ensureGroups();
        byte[] body = "압축된 봉투".getBytes(StandardCharsets.UTF_8);
        add(7, body);
        readAs("dead-worker");

        Thread.sleep(50);
        List<QueueEntry> claimed = source.claim(QueueStream.EVENTS, Duration.ofMillis(10), 100, QueueSource.CLAIM_START).entries();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().body()).isEqualTo(body);
        assertThat(claimed.getFirst().projectId()).isEqualTo(7);
        source.acknowledge(QueueStream.EVENTS, List.of(claimed.getFirst().id()));
        assertThat(pending()).isZero();
        source.close();
    }

    @Test
    void 미확인_목록을_쪽마다_넘겨_끝까지_이어받는다() throws Exception {
        RedisQueueSource source = new RedisQueueSource(factory, group, "me");
        source.ensureGroups();
        for (int i = 0; i < 7; i++) {
            add(7, new byte[] {(byte) i});
        }
        readAs("dead-worker");
        Thread.sleep(50);

        List<QueueEntry> all = new java.util.ArrayList<>();
        String cursor = QueueSource.CLAIM_START;
        int pages = 0;
        do {
            QueueSource.Claimed page = source.claim(QueueStream.EVENTS, Duration.ofMillis(10), 3, cursor);
            all.addAll(page.entries());
            cursor = page.next();
            pages++;
        } while (!QueueSource.CLAIM_START.equals(cursor) && pages < 10);

        assertThat(all).hasSize(7);
        assertThat(pages).isEqualTo(3);
        source.close();
    }

    @Test
    void 기다린_시간이_짧은_미확인_항목은_이어받지_않는다() {
        RedisQueueSource source = new RedisQueueSource(factory, group, "me");
        source.ensureGroups();
        add(7, new byte[] {1});
        readAs("busy-worker");

        assertThat(source.claim(QueueStream.EVENTS, Duration.ofMinutes(1), 100, QueueSource.CLAIM_START).entries()).isEmpty();
        source.close();
    }

    @Test
    void 소비_그룹을_두_번_만들어도_오류가_나지_않는다() {
        RedisQueueSource source = new RedisQueueSource(factory, group, "me");

        source.ensureGroups();
        source.ensureGroups();
        source.close();
    }

    @SuppressWarnings("unchecked")
    private void readAs(String consumer) {
        try (RedisConnection connection = factory.getConnection()) {
            connection.streamCommands().xReadGroup(Consumer.from(group, consumer), StreamReadOptions.empty().count(10),
                    StreamOffset.create(bytes(QueueStream.EVENTS.key()), ReadOffset.lastConsumed()));
        }
    }

    private void add(long projectId, byte[] body) {
        Map<byte[], byte[]> fields = new LinkedHashMap<>();
        fields.put(bytes("project_id"), bytes(Long.toString(projectId)));
        fields.put(bytes("received_at"), bytes(Long.toString(System.currentTimeMillis())));
        fields.put(bytes("body"), body);
        MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord().in(bytes(QueueStream.EVENTS.key())).ofMap(fields);
        try (RedisConnection connection = factory.getConnection()) {
            connection.streamCommands().xAdd(record);
        }
    }

    private long length() {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.streamCommands().xLen(bytes(QueueStream.EVENTS.key()));
        }
    }

    private long pending() {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.streamCommands().xPending(bytes(QueueStream.EVENTS.key()), group).getTotalPendingMessages();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
