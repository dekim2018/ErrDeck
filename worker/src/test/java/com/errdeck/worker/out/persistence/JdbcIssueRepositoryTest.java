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
