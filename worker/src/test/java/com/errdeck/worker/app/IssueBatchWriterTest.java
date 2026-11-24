package com.errdeck.worker.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.errdeck.worker.domain.EntryRef;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.IssueKey;
import com.errdeck.worker.domain.IssueSeen;
import com.errdeck.worker.domain.NewIssue;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.Quarantine;
import com.errdeck.worker.domain.QueueStream;
import com.errdeck.worker.domain.Tick;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IssueBatchWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:30Z");

    private final MemorySeen seen = new MemorySeen();
    private final MemoryIssues issues = new MemoryIssues();
    private final MemoryStore store = new MemoryStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IssueBatchWriter writer = new IssueBatchWriter(seen, issues, store, new WorkerMetrics(registry),
            Clock.fixed(NOW, ZoneOffset.UTC), 1000, 100);

    @Test
    void 같은_에러_1만_건은_이슈_하나이고_건수는_1만_원본은_상한_100건이다() {
        List<EventRow> rows = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(row(1, "a".repeat(32), NOW.minusSeconds(i % 30)));
        }

        writer.prepare(entries(rows)).run();

        assertThat(issues.created).hasSize(1);
        assertThat(store.ticks).hasSize(10_000);
        assertThat(store.ticks.stream().mapToLong(Tick::count).sum()).isEqualTo(10_000);
        assertThat(store.events).hasSize(100);
        assertThat(processed("stored")).isEqualTo(100);
        assertThat(processed("counted_only")).isEqualTo(9_900);
    }

    @Test
    void 접힌_이벤트의_횟수를_건수에_그대로_더한다() {
        EventRow folded = withCount(row(1, "a".repeat(32), NOW), 7);

        writer.prepare(entries(List.of(folded))).run();

        assertThat(store.ticks.getFirst().count()).isEqualTo(7);
    }

    @Test
    void 이미_처리한_event_id_와_묶음_안의_중복은_거르고_처리한_것은_남긴다() {
        EventRow old = row(1, "a".repeat(32), NOW);
        seen.ids.add(old.eventId());
        EventRow fresh = row(1, "a".repeat(32), NOW);

        writer.prepare(entries(List.of(old, fresh, fresh))).run();

        assertThat(store.ticks).hasSize(1);
        assertThat(store.events).extracting(EventRow::eventId).containsExactly(fresh.eventId());
        assertThat(seen.ids).contains(fresh.eventId());
        assertThat(processed("duplicate")).isEqualTo(2);
    }

    @Test
    void 이슈를_한_번_찾으면_다음_묶음부터는_저장소에_묻지_않는다() {
        writer.prepare(entries(List.of(row(1, "a".repeat(32), NOW)))).run();
        writer.prepare(entries(List.of(row(1, "a".repeat(32), NOW)))).run();
        writer.prepare(entries(List.of(row(1, "a".repeat(32), NOW), row(1, "b".repeat(32), NOW)))).run();

        assertThat(issues.lookups.get()).isEqualTo(2);
        assertThat(issues.created).hasSize(2);
    }

    @Test
    void 다른_프로젝트의_같은_지문은_다른_이슈다() {
        writer.prepare(entries(List.of(row(1, "a".repeat(32), NOW), row(2, "a".repeat(32), NOW)))).run();

        assertThat(issues.created).hasSize(2);
    }

    @Test
    void 이슈의_최근_발생은_이슈당_한_번_가장_나중_이벤트로_고친다() {
        EventRow early = row(1, "a".repeat(32), NOW.minusSeconds(20));
        EventRow late = withRelease(row(1, "a".repeat(32), NOW.minusSeconds(5)), "r2");

        writer.prepare(entries(List.of(late, early))).run();

        assertThat(issues.touched).hasSize(1);
        assertThat(issues.touched.getFirst().lastSeen()).isEqualTo(late.timestamp());
        assertThat(issues.touched.getFirst().release()).isEqualTo("r2");
        assertThat(issues.created.values().iterator().next().firstSeen()).isEqualTo(early.timestamp());
    }

    @Test
    void 중간에_실패한_쓰기를_이어서_하면_끝난_단계를_다시_하지_않는다() {
        store.ticksFailures = 2;
        List<EventRow> rows = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            rows.add(row(1, "a".repeat(32), NOW));
        }
        BatchWriter.BatchWrite write = writer.prepare(entries(rows));

        assertThatThrownBy(write::run).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(write::run).isInstanceOf(IllegalStateException.class);
        write.run();

        assertThat(store.eventWrites).isEqualTo(1);
        assertThat(store.events).hasSize(100);
        assertThat(store.ticks).hasSize(150);
        assertThat(issues.created).hasSize(1);
        assertThat(processed("stored")).isEqualTo(100);
    }

    @Test
    void 격리할_원문을_옮긴다() {
        ProcessedEntry entry = new ProcessedEntry(new EntryRef(QueueStream.EVENTS, "1-0"), NOW, List.of(),
                List.of(new Quarantine(1, new byte[] {9}, "형식")));

        writer.prepare(List.of(entry)).run();

        assertThat(issues.quarantined).hasSize(1);
        assertThat(processed("quarantined")).isEqualTo(1);
    }

    private double processed(String outcome) {
        return registry.get("errdeck.events.processed").tag("outcome", outcome).counter().count();
    }

    private static List<ProcessedEntry> entries(List<EventRow> rows) {
        return List.of(new ProcessedEntry(new EntryRef(QueueStream.EVENTS, UUID.randomUUID().toString()), NOW, rows));
    }

    private static EventRow row(long projectId, String fingerprint, Instant timestamp) {
        return new EventRow(projectId, UUID.randomUUID(), fingerprint, 1, timestamp, NOW, "prod", "r1", "error", "E", "m",
                0, "", Map.of(), false, "{}", 1, "E: m", "com.app.A#f");
    }

    private static EventRow withCount(EventRow r, long count) {
        return new EventRow(r.projectId(), r.eventId(), r.fingerprint(), r.ruleVersion(), r.timestamp(), r.receivedAt(), r.environment(),
                r.release(), r.level(), r.excType(), r.message(), r.userHash(), r.traceId(), r.tags(), r.restored(), r.payload(), count,
                r.title(), r.culprit());
    }

    private static EventRow withRelease(EventRow r, String release) {
        return new EventRow(r.projectId(), r.eventId(), r.fingerprint(), r.ruleVersion(), r.timestamp(), r.receivedAt(), r.environment(),
                release, r.level(), r.excType(), r.message(), r.userHash(), r.traceId(), r.tags(), r.restored(), r.payload(), r.count(),
                r.title(), r.culprit());
    }

    private static final class MemorySeen implements SeenEvents {
        final Set<UUID> ids = new HashSet<>();

        @Override
        public Set<UUID> alreadySeen(Collection<UUID> eventIds) {
            Set<UUID> out = new HashSet<>(eventIds);
            out.retainAll(ids);
            return out;
        }

        @Override
        public void markSeen(Collection<UUID> eventIds) {
            ids.addAll(eventIds);
        }
    }

    private static final class MemoryIssues implements IssueRepository {
        final Map<IssueKey, NewIssue> created = new HashMap<>();
        final Map<IssueKey, Long> ids = new HashMap<>();
        final List<IssueSeen> touched = new ArrayList<>();
        final List<Quarantine> quarantined = new ArrayList<>();
        final AtomicInteger lookups = new AtomicInteger();

        @Override
        public Map<IssueKey, Long> findIssueIds(Collection<IssueKey> keys) {
            lookups.incrementAndGet();
            Map<IssueKey, Long> found = new HashMap<>();
            keys.forEach(k -> {
                if (ids.containsKey(k)) {
                    found.put(k, ids.get(k));
                }
            });
            return found;
        }

        @Override
        public long createIssue(NewIssue issue) {
            created.put(issue.key(), issue);
            long id = ids.size() + 1L;
            ids.put(issue.key(), id);
            return id;
        }

        @Override
        public void touch(List<IssueSeen> seen) {
            touched.addAll(seen);
        }

        @Override
        public void quarantine(List<Quarantine> items) {
            quarantined.addAll(items);
        }
    }

    private static final class MemoryStore implements EventStore {
        final List<EventRow> events = new ArrayList<>();
        final List<Tick> ticks = new ArrayList<>();
        int ticksFailures;
        int eventWrites;

        @Override
        public void writeEvents(List<EventRow> rows) {
            eventWrites++;
            events.addAll(rows);
        }

        @Override
        public void writeTicks(List<Tick> rows) {
            if (ticksFailures-- > 0) {
                throw new IllegalStateException("ClickHouse 일시 실패");
            }
            ticks.addAll(rows);
        }
    }
}
