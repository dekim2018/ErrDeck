package com.errdeck.worker.app;

import com.errdeck.worker.app.WorkerMetrics.Outcome;
import com.errdeck.worker.domain.EntryRef;
import com.errdeck.worker.domain.EventRow;
import com.errdeck.worker.domain.IssueKey;
import com.errdeck.worker.domain.IssueSeen;
import com.errdeck.worker.domain.NewIssue;
import com.errdeck.worker.domain.ProcessedEntry;
import com.errdeck.worker.domain.Quarantine;
import com.errdeck.worker.domain.Tick;
import com.errdeck.worker.domain.TransactionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 쓰기 단계(6.1의 4, 7, 8, 9번과 10번의 확인 처리 앞까지).
 *
 * <ol>
 *   <li>이미 처리한 대기열 항목을 거르고(Redis), 묶음 안에서 두 번 온 event_id 를 거른다.</li>
 *   <li>묶음 안의 처음 보는 지문을 모아 이슈를 한 번에 찾고, 없으면 만든다(PostgreSQL).</li>
 *   <li>이슈별 분당 상한 안의 것만 원본으로 고른다.</li>
 *   <li>원본을 {@code events} 에, 받은 것 전부의 건수를 {@code event_ticks} 에 쓴다(ClickHouse).</li>
 *   <li>처리한 항목을 남기고, 이슈의 최근 발생을 이슈당 한 번 고치고, 격리할 원문을 옮긴다.</li>
 * </ol>
 *
 * <p>중간에 실패하면 단계 구조가 같은 쓰기를 다시 부른다. 끝난 단계는 건너뛰어, 이미 쓴 건수를 두 번 더하지 않고
 * 분당 상한을 두 번 세지 않는다. 계산 스레드가 I/O 를 기다리지 않도록 이 모든 I/O 는 쓰기 스레드에서 한다(14.5).
 */
public class IssueBatchWriter implements BatchWriter {

    private final SeenEntries seenEntries;
    private final IssueRepository issues;
    private final EventStore store;
    private final WorkerMetrics metrics;
    private final Clock clock;
    private final IssueCache cache;
    private final SampleLimiter limiter;

    public IssueBatchWriter(SeenEntries seenEntries, IssueRepository issues, EventStore store, WorkerMetrics metrics, Clock clock,
                            int issueCacheSize, int samplesPerIssuePerMinute) {
        this.seenEntries = seenEntries;
        this.issues = issues;
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
        this.cache = new IssueCache(issueCacheSize);
        this.limiter = new SampleLimiter(samplesPerIssuePerMinute);
    }

    @Override
    public BatchWrite prepare(List<ProcessedEntry> batch) {
        return new Write(batch);
    }

    private final class Write implements BatchWrite {

        private final List<ProcessedEntry> entries;
        private final List<EventRow> rows = new ArrayList<>();
        private final List<Quarantine> quarantined = new ArrayList<>();
        private final List<TransactionRow> transactions = new ArrayList<>();

        private List<EventRow> fresh;
        private List<EntryRef> freshEntries;
        private int duplicates;
        private Map<IssueKey, Long> issueIds;
        private List<EventRow> sampled;
        private List<Tick> ticks;
        private boolean eventsWritten;
        private boolean ticksWritten;
        private boolean transactionsWritten;
        private boolean seenMarked;
        private boolean touched;
        private boolean quarantineWritten;

        Write(List<ProcessedEntry> batch) {
            entries = batch;
            batch.forEach(entry -> {
                rows.addAll(entry.rows());
                quarantined.addAll(entry.quarantined());
                transactions.addAll(entry.transactions());
            });
        }

        @Override
        public void run() {
            if (fresh == null) {
                fresh = dropDuplicates();
                duplicates = rows.size() - fresh.size();
            }
            if (issueIds == null) {
                issueIds = resolveIssues(fresh);
            }
            if (sampled == null) {
                sample();
            }
            if (!eventsWritten) {
                long start = System.nanoTime();
                store.writeEvents(sampled);
                metrics.flushed("events", sampled.size(), System.nanoTime() - start);
                eventsWritten = true;
            }
            if (!ticksWritten) {
                long start = System.nanoTime();
                store.writeTicks(ticks);
                metrics.flushed("event_ticks", ticks.size(), System.nanoTime() - start);
                ticksWritten = true;
            }
            if (!transactionsWritten) {
                // 트랜잭션은 중복을 거르지 않는다. 다시 쓰는 일은 묶음 재시도뿐이고, 화면은 span_id 로 하나만 보여 준다.
                long start = System.nanoTime();
                store.writeTransactions(transactions);
                metrics.flushed("transactions", transactions.size(), System.nanoTime() - start);
                metrics.transactions(transactions.size());
                transactionsWritten = true;
            }
            if (!seenMarked) {
                seenEntries.markSeen(freshEntries);
                seenMarked = true;
            }
            if (!touched) {
                issues.touch(lastSeenPerIssue());
                touched = true;
            }
            if (!quarantineWritten) {
                issues.quarantine(quarantined);
                quarantineWritten = true;
                metrics.processed(Outcome.STORED, sampled.size());
                metrics.processed(Outcome.COUNTED_ONLY, fresh.size() - sampled.size());
                metrics.processed(Outcome.DUPLICATE, duplicates);
                metrics.processed(Outcome.QUARANTINED, quarantined.size());
            }
        }

        /** 이미 처리한 항목의 이벤트와, 같은 묶음 안에서 두 번 온 event_id 를 뺀다. 이벤트가 있는 항목만 Redis 에 묻는다. */
        private List<EventRow> dropDuplicates() {
            List<EntryRef> withRows = entries.stream().filter(e -> !e.rows().isEmpty()).map(ProcessedEntry::ref).toList();
            if (withRows.isEmpty()) {
                freshEntries = List.of();
                return List.of();
            }
            Set<EntryRef> done = seenEntries.alreadySeen(withRows);
            freshEntries = withRows.stream().filter(ref -> !done.contains(ref)).toList();
            Set<UUID> ids = new HashSet<>();
            List<EventRow> kept = new ArrayList<>(rows.size());
            for (ProcessedEntry entry : entries) {
                if (done.contains(entry.ref())) {
                    continue;
                }
                for (EventRow row : entry.rows()) {
                    if (ids.add(row.eventId())) {
                        kept.add(row);
                    }
                }
            }
            return kept;
        }

        private void sample() {
            Instant now = clock.instant();
            List<EventRow> keep = new ArrayList<>();
            List<Tick> counted = new ArrayList<>(fresh.size());
            for (EventRow row : fresh) {
                long issueId = issueIds.get(key(row));
                if (limiter.allow(issueId, now)) {
                    keep.add(row);
                }
                counted.add(new Tick(row.projectId(), row.fingerprint(), row.timestamp(), row.count(), row.userHash()));
            }
            sampled = keep;
            ticks = counted;
        }

        private List<IssueSeen> lastSeenPerIssue() {
            Map<Long, EventRow> latest = new LinkedHashMap<>();
            Map<Long, Long> events = new HashMap<>();
            for (EventRow row : fresh) {
                long issueId = issueIds.get(key(row));
                latest.merge(issueId, row, (a, b) -> b.timestamp().isAfter(a.timestamp()) ? b : a);
                events.merge(issueId, row.count(), Long::sum);
            }
            List<IssueSeen> seen = new ArrayList<>(latest.size());
            latest.forEach((issueId, row) -> seen.add(new IssueSeen(issueId, row.timestamp(), row.release(), events.get(issueId))));
            return seen;
        }
    }

    /** 캐시에 없는 지문만 모아 한 번에 찾고, 그래도 없는 것만 만든다. */
    private Map<IssueKey, Long> resolveIssues(Collection<EventRow> rows) {
        Map<IssueKey, Long> resolved = new HashMap<>();
        Map<IssueKey, EventRow> firstByKey = new LinkedHashMap<>();
        for (EventRow row : rows) {
            IssueKey key = key(row);
            Long cached = cache.get(key);
            if (cached != null) {
                resolved.put(key, cached);
            } else {
                firstByKey.merge(key, row, (a, b) -> b.timestamp().isBefore(a.timestamp()) ? b : a);
            }
        }
        if (firstByKey.isEmpty()) {
            return resolved;
        }
        Map<IssueKey, Long> found = issues.findIssueIds(firstByKey.keySet());
        for (Map.Entry<IssueKey, EventRow> entry : firstByKey.entrySet()) {
            Long issueId = found.get(entry.getKey());
            if (issueId == null) {
                EventRow first = entry.getValue();
                issueId = issues.createIssue(new NewIssue(entry.getKey(), first.ruleVersion(), first.title(), first.culprit(),
                        first.timestamp(), first.timestamp(), first.release()));
            }
            cache.put(entry.getKey(), issueId);
            resolved.put(entry.getKey(), issueId);
        }
        return resolved;
    }

    private static IssueKey key(EventRow row) {
        return new IssueKey(row.projectId(), row.fingerprint());
    }
}
