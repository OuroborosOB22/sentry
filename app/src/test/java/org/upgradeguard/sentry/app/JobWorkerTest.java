package org.upgradeguard.sentry.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.jobs.JobWorker;
import org.upgradeguard.sentry.app.provider.ExecutionProvider;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:worker;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/worker-test-data", "sentry.job-timeout=PT3S",
        "sentry.lease-duration=PT5S"})
class JobWorkerTest {
    @Autowired
    JobRepository jobs;
    @Autowired
    ProjectRepository projects;
    @Autowired
    JobService service;
    @Autowired
    JobWorker worker;
    @Autowired
    ProviderRegistry registry;
    @Autowired
    JdbcTemplate jdbc;

    FakeProvider fake = new FakeProvider();

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM findings");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM snapshots");
        jdbc.update("DELETE FROM projects");
        projects.insert(new Rows.Project("demo", "Demo", "/tmp/demo", "MAVEN", Instant.now()));
        registry.register(fake);
        worker.setPollInterval(Duration.ofMillis(20));
    }

    private String submit() {
        return service.submit("demo", new JobService.Submission("REHEARSE_UPGRADE", "org.demo:lib", "1.0.0", "2.0.0", null, "FAKE"));
    }

    @Test
    void aSuccessfulJobKeepsTheVerifiedEvidenceAndItsFindings() throws Exception {
        String id = submit();
        assertTrue(worker.runOne());
        Rows.Job job = jobs.find(id).orElseThrow();
        assertEquals("SUCCEEDED", job.status());
        assertEquals("REJECT", job.verdict());
        assertEquals(id + "/evidence.json", job.evidencePath());
        assertFalse(jobs.findings(id).isEmpty());
        assertEquals("REJECT", service.evidence(id).orElseThrow().path("verdict").path("decision").asText());
        assertEquals(1, fake.cleanups.get());
    }

    @Test
    void anAcceptedUpgradeIsStillASucceededJob() {
        fake.decision = "ACCEPT";
        String id = submit();
        worker.runOne();
        assertEquals("SUCCEEDED", jobs.find(id).orElseThrow().status());
        assertEquals("ACCEPT", jobs.find(id).orElseThrow().verdict());
    }

    @Test
    void evidenceThatDoesNotMatchItsHashesFailsTheJobAndIsNotKept() throws Exception {
        fake.tamper = true;
        String id = submit();
        worker.runOne();
        Rows.Job job = jobs.find(id).orElseThrow();
        assertEquals("FAILED", job.status());
        assertTrue(job.error().contains("recorded hash"), job.error());
        assertEquals(null, job.evidencePath());
        assertTrue(service.evidence(id).isEmpty());
    }

    @Test
    void aProviderThatCannotStartFailsTheJobWithItsMessage() {
        fake.failSubmit = true;
        String id = submit();
        worker.runOne();
        Rows.Job job = jobs.find(id).orElseThrow();
        assertEquals("FAILED", job.status());
        assertEquals("cannot start", job.error());
    }

    @Test
    void aJobThatOutlivesItsDeadlineIsTimedOutAndCancelled() {
        fake.script = new ArrayList<>(List.of("RUNNING"));
        String id = submit();
        worker.runOne();
        assertEquals("TIMED_OUT", jobs.find(id).orElseThrow().status());
        assertEquals(1, fake.cancels.get());
    }

    @Test
    void cancellingARunningJobStopsItAndMarksItCancelled() throws Exception {
        fake.script = new ArrayList<>(List.of("RUNNING"));
        String id = submit();
        Thread runner = new Thread(worker::runOne);
        runner.start();
        for (int attempt = 0; attempt < 100 && !jobs.find(id).orElseThrow().status().equals("RUNNING"); attempt++) {
            Thread.sleep(20);
        }
        assertTrue(service.cancel(id));
        runner.join(5000);
        assertEquals("CANCELLED", jobs.find(id).orElseThrow().status());
        assertEquals(1, fake.cancels.get());
    }

    @Test
    void aWorkerThatLostItsHandleReportsInterruptedAndNeverStartsTheJobAgain() {
        fake.lose = true;
        String id = submit();
        worker.runOne();
        assertEquals("INTERRUPTED", jobs.find(id).orElseThrow().status());
        assertFalse(worker.runOne());
        assertEquals(1, fake.submits.get());
    }

    @Test
    void submissionsAreValidatedBeforeAnythingIsQueued() {
        for (JobService.Submission bad : List.of(
                new JobService.Submission("DELETE_EVERYTHING", "a:b", "1", "2", null, "FAKE"),
                new JobService.Submission("REHEARSE_UPGRADE", "not a coordinate", "1", "2", null, "FAKE"),
                new JobService.Submission("REHEARSE_UPGRADE", "a:b", "1; rm -rf /", "2", null, "FAKE"),
                new JobService.Submission("REHEARSE_UPGRADE", "a:b", "1", "2", null, "MARS"),
                new JobService.Submission("REHEARSE_RECOVERY", "a:b", null, null, "snap-unknown", "FAKE"))) {
            assertThrows(JobService.Rejected.class, () -> service.submit("demo", bad), bad.toString());
        }
        assertThrows(JobService.Rejected.class, () -> service.submit("nobody", new JobService.Submission("SNAPSHOT", null, null, null, null, "FAKE")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class));
        assertNotNull(service);
    }

    private String snapshotJob(String mode) {
        fake.snapshotMode = mode;
        fake.projectSlug = "tmp-demo".equals(mode) ? "tmp-demo" : "demo";
        String id = service.submit("demo", new JobService.Submission("SNAPSHOT", null, null, null, null, "FAKE"));
        worker.runOne();
        return id;
    }

    @Test
    void aSnapshotIsStoredUnderTheProjectAndItsSlugAndRecorded() {
        projects.insert(new Rows.Project("other", "Other", "/tmp/other", "MAVEN", Instant.now()));
        jdbc.update("UPDATE projects SET path = ? WHERE id = 'demo'", "/tmp/demo");
        String id = snapshotJob("ok");
        Rows.Job job = jobs.find(id).orElseThrow();
        assertEquals("SUCCEEDED", job.status(), job.error());
        var stored = jdbc.queryForList("SELECT folder FROM snapshots WHERE project_id = 'demo'", String.class);
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).endsWith("snapshots/demo/demo/snap-0123456789ab"), stored.get(0));
        assertTrue(Files.isRegularFile(Path.of(stored.get(0), "snapshot.json")));
    }

    @Test
    void snapshotOutputThatTriesToLandInAnotherProjectOrBeAnotherIdIsRefused() {
        for (String mode : List.of("other-project", "wrong-name", "tampered", "symlink", "two")) {
            String id = snapshotJob(mode);
            Rows.Job job = jobs.find(id).orElseThrow();
            assertEquals("FAILED", job.status(), mode + ": " + job.error());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM snapshots", Integer.class), mode);
        }
    }
}
