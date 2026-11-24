package com.errdeck.worker.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.worker.domain.IssueKey;
import com.errdeck.worker.domain.IssueSeen;
import com.errdeck.worker.domain.NewIssue;
import com.errdeck.worker.support.TestInfra;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "errdeck.worker.compute-threads=1")
class JdbcIssueRepositoryTest extends TestInfra {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate template;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void 같은_새_지문으로_동시에_이슈를_만들어도_이슈는_하나다() throws Exception {
        JdbcIssueRepository repository = new JdbcIssueRepository(jdbc, template, transactions);
        long projectId = createProject(jdbc);
        IssueKey key = new IssueKey(projectId, "c".repeat(32));
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);

        List<Long> ids = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                tasks.add(() -> {
                    ready.countDown();
                    ready.await();
                    return repository.createIssue(new NewIssue(key, 1, "E: x", null, Instant.now(), Instant.now(), "r1"));
                });
            }
            for (Future<Long> future : executor.invokeAll(tasks)) {
                ids.add(future.get());
            }
        }

        Set<Long> distinct = ids.stream().collect(Collectors.toSet());
        assertThat(distinct).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM issues WHERE project_id = ?").param(projectId).query(Long.class).single()).isEqualTo(1);
        assertThat(repository.findIssueIds(List.of(key))).containsEntry(key, distinct.iterator().next());
    }

    @Test
    void 해결한_릴리스보다_나중_릴리스에서_나면_재발이고_이전_릴리스에서_나면_해결을_유지한다() {
        JdbcIssueRepository repository = new JdbcIssueRepository(jdbc, template, transactions);
        long projectId = createProject(jdbc);
        for (String version : List.of("web@1", "web@2", "web@3")) {
            jdbc.sql("INSERT INTO releases (project_id, version, created_at) VALUES (?, ?, now() + (? || ' seconds')::interval)")
                    .params(projectId, version, version.substring(4)).update();
        }
        Instant now = Instant.now();
        long issueId = repository.createIssue(new NewIssue(new IssueKey(projectId, "e".repeat(32)), 1, "t", null, now, now, "web@1"));
        jdbc.sql("UPDATE issues SET status = 'resolved', resolved_in_release = 'web@2' WHERE id = ?").param(issueId).update();

        repository.touch(List.of(new IssueSeen(issueId, now.plusSeconds(1), "web@1", 1)));
        assertThat(status(issueId)).isEqualTo("resolved");
        repository.touch(List.of(new IssueSeen(issueId, now.plusSeconds(2), "web@2", 1)));
        assertThat(status(issueId)).isEqualTo("resolved");
        repository.touch(List.of(new IssueSeen(issueId, now.plusSeconds(3), "web@3", 1)));
        assertThat(status(issueId)).isEqualTo("regressed");
    }

    @Test
    void 등록하지_않은_릴리스는_해결한_릴리스와_다를_때만_재발이고_릴리스_없이_해결했으면_어느_이벤트든_재발이다() {
        JdbcIssueRepository repository = new JdbcIssueRepository(jdbc, template, transactions);
        long projectId = createProject(jdbc);
        Instant now = Instant.now();
        long same = repository.createIssue(new NewIssue(new IssueKey(projectId, "f".repeat(32)), 1, "t", null, now, now, "api@1"));
        long anyRelease = repository.createIssue(new NewIssue(new IssueKey(projectId, "1".repeat(32)), 1, "t", null, now, now, "api@1"));
        jdbc.sql("UPDATE issues SET status = 'resolved', resolved_in_release = 'api@1' WHERE id = ?").param(same).update();
        jdbc.sql("UPDATE issues SET status = 'resolved', resolved_in_release = NULL WHERE id = ?").param(anyRelease).update();

        repository.touch(List.of(new IssueSeen(same, now.plusSeconds(1), "api@1", 1), new IssueSeen(anyRelease, now.plusSeconds(1), "api@1", 1)));
        assertThat(status(same)).isEqualTo("resolved");
        assertThat(status(anyRelease)).isEqualTo("regressed");
        repository.touch(List.of(new IssueSeen(same, now.plusSeconds(2), "api@2", 1)));
        assertThat(status(same)).isEqualTo("regressed");
    }

    @Test
    void 무시는_기한이_지나거나_횟수_조건에_닿으면_미해결로_돌아간다() {
        JdbcIssueRepository repository = new JdbcIssueRepository(jdbc, template, transactions);
        long projectId = createProject(jdbc);
        Instant now = Instant.now();
        long byTime = repository.createIssue(new NewIssue(new IssueKey(projectId, "2".repeat(32)), 1, "t", null, now, now, null));
        long byCount = repository.createIssue(new NewIssue(new IssueKey(projectId, "3".repeat(32)), 1, "t", null, now, now, null));
        jdbc.sql("UPDATE issues SET status = 'ignored', ignored_until = now() - interval '1 minute' WHERE id = ?").param(byTime).update();
        jdbc.sql("UPDATE issues SET status = 'ignored', ignored_until_count = 10 WHERE id = ?").param(byCount).update();

        repository.touch(List.of(new IssueSeen(byTime, now.plusSeconds(1), null, 1), new IssueSeen(byCount, now.plusSeconds(1), null, 6)));
        assertThat(status(byTime)).isEqualTo("unresolved");
        assertThat(status(byCount)).isEqualTo("ignored");
        repository.touch(List.of(new IssueSeen(byCount, now.plusSeconds(2), null, 4)));
        assertThat(status(byCount)).isEqualTo("unresolved");
    }

    private String status(long issueId) {
        return jdbc.sql("SELECT status FROM issues WHERE id = ?").param(issueId).query(String.class).single();
    }

    @Test
    void 최근_발생은_더_나중_시각일_때만_고치고_최초_발생은_더_이를_때만_고친다() {
        JdbcIssueRepository repository = new JdbcIssueRepository(jdbc, template, transactions);
        long projectId = createProject(jdbc);
        Instant first = Instant.parse("2026-11-24T10:00:00Z");
        long issueId = repository.createIssue(new NewIssue(new IssueKey(projectId, "d".repeat(32)), 1, "t", null, first, first, "r1"));

        repository.touch(List.of(new IssueSeen(issueId, first.plusSeconds(60), "r2")));
        repository.touch(List.of(new IssueSeen(issueId, first.plusSeconds(30), "r1")));
        repository.touch(List.of(new IssueSeen(issueId, first.minusSeconds(30), "r0")));

        var row = jdbc.sql("SELECT first_seen, last_seen, last_release FROM issues WHERE id = ?").param(issueId)
                .query((rs, n) -> List.of(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getString(3))).single();
        assertThat(row).containsExactly(first.minusSeconds(30), first.plusSeconds(60), "r2");
    }
}
