package com.errdeck.worker.out.redis;

import com.errdeck.worker.app.QueueSource;
import com.errdeck.worker.domain.QueueEntry;
import com.errdeck.worker.domain.QueueStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

/**
 * Redis 스트림 대기열. 스트림마다 읽기 전용 연결을 하나씩 둔다. 블로킹 읽기가 다른 명령의 연결을 붙잡지 않게 하기 위해서다(14.5).
 * 스트림 하나는 읽기 스레드 하나만 읽는다.
 */
public class RedisQueueSource implements QueueSource {

    /** Lua 스크립트의 unpack 은 인자 수에 한계가 있으므로 확인 처리를 나눠 보낸다. */
    private static final int ACK_CHUNK = 1000;

    private final RedisConnectionFactory factory;
    private final String group;
    private final String consumer;
    private final byte[] claimScript = script("redis/claim.lua");
    private final byte[] ackScript = script("redis/ack.lua");
    private final Map<QueueStream, RedisConnection> readConnections = new EnumMap<>(QueueStream.class);
    private RedisConnection ackConnection;

    public RedisQueueSource(RedisConnectionFactory factory, String group, String consumer) {
        this.factory = factory;
        this.group = group;
        this.consumer = consumer;
    }

    /** 소비 그룹이 없으면 만든다. 이미 쌓인 항목부터 읽도록 처음(0)에서 시작한다. */
    public void ensureGroups() {
        try (RedisConnection connection = factory.getConnection()) {
            for (QueueStream stream : QueueStream.values()) {
                try {
                    connection.streamCommands().xGroupCreate(bytes(stream.key()), group, ReadOffset.from("0"), true);
                } catch (RuntimeException e) {
                    if (!alreadyExists(e)) {
                        throw e;
                    }
                }
            }
        }
    }

    /** 스프링이 감싼 예외의 원인까지 보고 그룹이 이미 있다는 오류인지 가린다. */
    private static boolean alreadyExists(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (String.valueOf(t.getMessage()).contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked") // xReadGroup 의 가변 인자가 제네릭 배열이다. 스트림 하나만 넘긴다.
    public List<QueueEntry> read(QueueStream stream, int count, Duration block) {
        List<ByteRecord> records = readConnection(stream).streamCommands().xReadGroup(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count).block(block),
                StreamOffset.create(bytes(stream.key()), ReadOffset.lastConsumed()));
        if (records == null) {
            return List.of();
        }
        List<QueueEntry> entries = new ArrayList<>(records.size());
        for (ByteRecord record : records) {
            Map<String, byte[]> fields = new java.util.HashMap<>();
            record.getValue().forEach((key, value) -> fields.put(new String(key, StandardCharsets.UTF_8), value));
            entries.add(entry(stream, record.getId().getValue(), fields.get("project_id"), fields.get("received_at"), fields.get("body")));
        }
        return entries;
    }

    @Override
    public Claimed claim(QueueStream stream, Duration minIdle, int count, String start) {
        List<Object> flat = readConnection(stream).scriptingCommands().eval(claimScript, ReturnType.MULTI, 1,
                bytes(stream.key()), bytes(group), bytes(consumer), bytes(Long.toString(minIdle.toMillis())),
                bytes(Integer.toString(count)), bytes(start));
        List<QueueEntry> entries = new ArrayList<>();
        if (flat == null || flat.isEmpty()) {
            return new Claimed(entries, CLAIM_START);
        }
        String next = new String((byte[]) flat.getFirst(), StandardCharsets.UTF_8);
        for (int i = 1; i + 3 < flat.size(); i += 4) {
            entries.add(entry(stream, new String((byte[]) flat.get(i), StandardCharsets.UTF_8),
                    (byte[]) flat.get(i + 1), (byte[]) flat.get(i + 2), (byte[]) flat.get(i + 3)));
        }
        return new Claimed(entries, next);
    }

    @Override
    public synchronized void acknowledge(QueueStream stream, Collection<String> ids) {
        List<String> all = List.copyOf(ids);
        for (int from = 0; from < all.size(); from += ACK_CHUNK) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + ACK_CHUNK));
            byte[][] keysAndArgs = new byte[chunk.size() + 2][];
            keysAndArgs[0] = bytes(stream.key());
            keysAndArgs[1] = bytes(group);
            for (int i = 0; i < chunk.size(); i++) {
                keysAndArgs[i + 2] = bytes(chunk.get(i));
            }
            ackConnection().scriptingCommands().eval(ackScript, ReturnType.INTEGER, 1, keysAndArgs);
        }
    }

    public synchronized void close() {
        readConnections.values().forEach(RedisConnection::close);
        readConnections.clear();
        if (ackConnection != null) {
            ackConnection.close();
            ackConnection = null;
        }
    }

    private RedisConnection readConnection(QueueStream stream) {
        synchronized (readConnections) {
            return readConnections.computeIfAbsent(stream, s -> factory.getConnection());
        }
    }

    private RedisConnection ackConnection() {
        if (ackConnection == null) {
            ackConnection = factory.getConnection();
        }
        return ackConnection;
    }

    private static QueueEntry entry(QueueStream stream, String id, byte[] projectId, byte[] receivedAt, byte[] body) {
        return new QueueEntry(stream, id,
                projectId == null ? 0 : Long.parseLong(new String(projectId, StandardCharsets.UTF_8)),
                receivedAt == null ? null : Instant.ofEpochMilli(Long.parseLong(new String(receivedAt, StandardCharsets.UTF_8))),
                body == null ? new byte[0] : body);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] script(String path) {
        try {
            return new ClassPathResource(path).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
