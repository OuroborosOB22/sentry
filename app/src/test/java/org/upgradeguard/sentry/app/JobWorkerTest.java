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

    static class FakeProvider implements ExecutionProvider {
        String decision = "REJECT";
        List<String> script = new ArrayList<>(List.of("RUNNING", "SUCCEEDED"));
        boolean tamper;
        boolean lose;
        boolean failSubmit;
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger cleanups = new AtomicInteger();
        final AtomicInteger submits = new AtomicInteger();
        int polls;

        @Override
        public String id() {
            return "FAKE";
        }

        @Override
        public ProviderCheck validate() {
            return new ProviderCheck(true, "fake", List.of());
        }

        @Override
        public String submit(JobSpec spec) throws IOException {
            submits.incrementAndGet();
            if (failSubmit) {
                throw new IOException("cannot start");
            }
            return spec.jobId();
        }

        @Override
        public ProviderStatus status(String handle) throws IOException {
            if (lose) {
                throw new UnknownHandleException(handle);
            }
            String state = script.get(Math.min(polls++, script.size() - 1));
            return new ProviderStatus(state, state.toLowerCase());
        }

        @Override
        public void cancel(String handle) {
            cancels.incrementAndGet();
            script = new ArrayList<>(List.of("FAILED"));
            polls = 0;
        }

        @Override
        public Path collect(String handle, Path destination) throws IOException {
            EvidenceFolders.create(destination, handle, decision);
            if (tamper) {
                Files.writeString(destination.resolve("after/pytest.log"), "forged");
            }
            return destination;
        }

        @Override
        public void cleanup(String handle) {
            cleanups.incrementAndGet();
        }
    }

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
}
