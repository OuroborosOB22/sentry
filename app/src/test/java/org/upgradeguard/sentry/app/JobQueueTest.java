package org.upgradeguard.sentry.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:queue;DB_CLOSE_DELAY=-1"})
class JobQueueTest {
    @Autowired
    JobRepository jobs;
    @Autowired
    ProjectRepository projects;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM snapshots");
        jdbc.update("DELETE FROM projects");
        projects.insert(new Rows.Project("demo", "Demo", "/tmp/demo", "MAVEN", Instant.now()));
    }

    private void enqueue(String id, int maxAttempts) {
        jobs.insert(new Rows.Job(id, "demo", "REHEARSE_UPGRADE", "QUEUED", "{}", "LOCAL", null, null, null, 0, maxAttempts, null, null, false, null, null,
                Instant.now(), null, null));
    }

    @Test
    void aJobIsClaimedByExactlyOneWorkerEvenWhenManyRace() throws Exception {
        for (int number = 0; number < 12; number++) {
            enqueue("job-" + number, 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            String owner = "worker-" + worker;
            futures.add(pool.submit(() -> {
                List<String> mine = new ArrayList<>();
                while (true) {
                    var claimed = jobs.claimNext(owner, Duration.ofSeconds(30), Instant.now());
                    if (claimed.isEmpty()) {
                        return mine;
                    }
                    mine.add(claimed.get().id());
                }
            }));
        }
        List<String> everything = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            everything.addAll(future.get());
        }
        pool.shutdown();
        assertEquals(12, everything.size());
        assertEquals(12, new HashSet<>(everything).size());
        assertEquals(12, jobs.countRunning("LOCAL"));
    }

    @Test
    void aClaimedJobHasALeaseAndCountsTheAttempt() {
        enqueue("a", 2);
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        Rows.Job claimed = jobs.claimNext("w1", Duration.ofSeconds(30), now).orElseThrow();
        assertEquals("RUNNING", claimed.status());
        assertEquals(1, claimed.attempts());
        assertEquals(now.plusSeconds(30), claimed.leaseExpiresAt());
        assertTrue(jobs.claimNext("w2", Duration.ofSeconds(30), now).isEmpty());
    }

    @Test
    void onlyTheOwnerCanHeartbeatOrFinish() {
        enqueue("a", 2);
        Instant now = Instant.now();
        jobs.claimNext("w1", Duration.ofSeconds(30), now);
        assertFalse(jobs.heartbeat("a", "someone-else", Duration.ofSeconds(30), now, "x"));
        assertTrue(jobs.heartbeat("a", "w1", Duration.ofSeconds(30), now.plusSeconds(5), "building"));
        assertFalse(jobs.finish("a", "someone-else", "SUCCEEDED", "ACCEPT", "a/evidence.json", null, now));
        assertTrue(jobs.finish("a", "w1", "SUCCEEDED", "ACCEPT", "a/evidence.json", null, now));
        assertEquals("ACCEPT", jobs.find("a").orElseThrow().verdict());
        assertFalse(jobs.finish("a", "w1", "FAILED", null, null, "late", now));
    }

    @Test
    void aJobWhoseWorkerDiedIsRequeuedThenInterruptedWhenRetriesRunOut() {
        enqueue("a", 2);
        Instant start = Instant.parse("2026-10-24T10:00:00Z");
        jobs.claimNext("w1", Duration.ofSeconds(10), start);
        assertEquals(0, jobs.recoverExpired(start.plusSeconds(5)));
        assertEquals(1, jobs.recoverExpired(start.plusSeconds(20)));
        assertEquals("QUEUED", jobs.find("a").orElseThrow().status());
        jobs.claimNext("w2", Duration.ofSeconds(10), start.plusSeconds(21));
        assertEquals(1, jobs.recoverExpired(start.plusSeconds(60)));
        Rows.Job last = jobs.find("a").orElseThrow();
        assertEquals("INTERRUPTED", last.status());
        assertEquals(2, last.attempts());
    }

    @Test
    void aJobThatAlreadyReachedARemoteHostIsNeverRequeued() {
        enqueue("a", 5);
        Instant start = Instant.parse("2026-10-24T10:00:00Z");
        jobs.claimNext("w1", Duration.ofSeconds(10), start);
        jobs.setRemoteId("a", "remote-1");
        jobs.recoverExpired(start.plusSeconds(60));
        assertEquals("INTERRUPTED", jobs.find("a").orElseThrow().status());
    }

    @Test
    void cancellingAQueuedJobIsImmediateAndARunningOneIsFlagged() {
        enqueue("queued", 2);
        enqueue("running", 2);
        assertTrue(jobs.requestCancel("queued", Instant.now()));
        assertEquals("CANCELLED", jobs.find("queued").orElseThrow().status());
        assertTrue(jobs.claimNext("w1", Duration.ofSeconds(30), Instant.now()).isPresent());
        assertTrue(jobs.requestCancel("running", Instant.now()));
        assertTrue(jobs.cancelRequested("running"));
        assertEquals("RUNNING", jobs.find("running").orElseThrow().status());
        assertFalse(jobs.requestCancel("nothing", Instant.now()));
    }

    @Test
    void aRunningJobWithACancelFlagEndsCancelledWhenItsLeaseExpires() {
        enqueue("a", 2);
        Instant start = Instant.parse("2026-10-24T10:00:00Z");
        jobs.claimNext("w1", Duration.ofSeconds(10), start);
        jobs.requestCancel("a", start);
        jobs.recoverExpired(start.plusSeconds(30));
        assertEquals("CANCELLED", jobs.find("a").orElseThrow().status());
    }

    @Test
    void findingsAreStoredInOrderAndReplacedNotAppended() {
        enqueue("a", 2);
        jobs.replaceFindings("a", List.of(new Rows.Finding("one", "BLOCKING", "first"), new Rows.Finding("two", "INFO", "second")));
        jobs.replaceFindings("a", List.of(new Rows.Finding("three", "WARNING", "third")));
        assertEquals(List.of("three"), jobs.findings("a").stream().map(Rows.Finding::code).toList());
        Set<String> statuses = new HashSet<>(jdbc.queryForList("SELECT status FROM jobs", String.class));
        assertEquals(Collections.singleton("QUEUED"), statuses);
    }
}
