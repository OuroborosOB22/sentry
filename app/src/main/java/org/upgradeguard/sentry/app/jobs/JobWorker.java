package org.upgradeguard.sentry.app.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.provider.EvidenceVerifier;
import org.upgradeguard.sentry.app.provider.ExecutionProvider;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.app.store.SnapshotRepository;
import org.upgradeguard.sentry.contract.Json;

@Component
public class JobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);
    private static final int POLL_FAILURES_ALLOWED = 5;

    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final ProviderRegistry providers;
    private final SentryProperties properties;
    private final String owner = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService pool;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Duration pollInterval = Duration.ofSeconds(1);

    public JobWorker(JobRepository jobs, ProjectRepository projects, SnapshotRepository snapshots, ProviderRegistry providers, SentryProperties properties) {
        this.jobs = jobs;
        this.projects = projects;
        this.snapshots = snapshots;
        this.providers = providers;
        this.properties = properties;
        this.pool = Executors.newFixedThreadPool(Math.max(1, properties.maxConcurrentJobs()));
    }

    public void setPollInterval(Duration interval) {
        this.pollInterval = interval;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        jobs.recoverExpired(Instant.now());
        while (inFlight.get() < properties.maxConcurrentJobs()) {
            Optional<Rows.Job> claimed = jobs.claimNext(owner, properties.leaseDuration(), Instant.now());
            if (claimed.isEmpty()) {
                return;
            }
            inFlight.incrementAndGet();
            Rows.Job job = claimed.get();
            pool.submit(() -> {
                try {
                    execute(job);
                } catch (RuntimeException unexpected) {
                    LOG.error("job {} crashed the worker", job.id(), unexpected);
                    jobs.finish(job.id(), owner, "FAILED", null, null, "the worker hit an internal error", Instant.now());
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    public boolean runOne() {
        Optional<Rows.Job> claimed = jobs.claimNext(owner, properties.leaseDuration(), Instant.now());
        claimed.ifPresent(this::execute);
        return claimed.isPresent();
    }

    void execute(Rows.Job job) {
        Optional<ExecutionProvider> found = providers.find(job.executionProvider());
        Optional<Rows.Project> project = projects.find(job.projectId());
        if (found.isEmpty() || project.isEmpty()) {
            jobs.finish(job.id(), owner, "FAILED", null, null, "the execution provider or the project is no longer available", Instant.now());
            return;
        }
        ExecutionProvider provider = found.get();
        Path workDir = properties.dataDir().resolve("work").resolve(job.id()).toAbsolutePath();
        String handle = null;
        try {
            JsonNode request = Json.parse(job.requestJson());
            Path store = properties.dataDir().resolve("snapshots").toAbsolutePath();
            ExecutionProvider.JobSpec spec = new ExecutionProvider.JobSpec(job.id(), job.operation(), project.get().ecosystem(), slug(project.get()), Path.of(project.get().path()),
                    request.path("package").asText(null), request.path("from").asText(null), request.path("to").asText(null), store, request.path("snapshotId").asText(null), workDir);
            jobs.heartbeat(job.id(), owner, properties.leaseDuration(), Instant.now(), "starting");
            handle = provider.submit(spec);
            jobs.setRemoteId(job.id(), handle);
            watch(job, provider, handle, spec);
        } catch (IOException | RuntimeException failure) {
            jobs.finish(job.id(), owner, "FAILED", null, null, String.valueOf(failure.getMessage()), Instant.now());
            LOG.warn("job {} failed: {}", job.id(), failure.getMessage());
        } finally {
            if (handle != null) {
                try {
                    provider.cleanup(handle);
                } catch (IOException cleanupProblem) {
                    LOG.warn("could not clean up job {}: {}", job.id(), cleanupProblem.getMessage());
                }
            }
        }
    }

    private void watch(Rows.Job job, ExecutionProvider provider, String handle, ExecutionProvider.JobSpec spec) throws IOException {
        Instant deadline = Instant.now().plus(properties.jobTimeout());
        int failures = 0;
        boolean cancelSent = false;
        while (true) {
            if (!jobs.heartbeat(job.id(), owner, properties.leaseDuration(), Instant.now(), "running")) {
                return;
            }
            if (!cancelSent && jobs.cancelRequested(job.id())) {
                provider.cancel(handle);
                cancelSent = true;
            }
            if (Instant.now().isAfter(deadline)) {
                provider.cancel(handle);
                jobs.finish(job.id(), owner, "TIMED_OUT", null, null, "the job did not finish within " + properties.jobTimeout(), Instant.now());
                return;
            }
            ExecutionProvider.ProviderStatus status;
            try {
                status = provider.status(handle);
                failures = 0;
            } catch (ExecutionProvider.UnknownHandleException lost) {
                jobs.finish(job.id(), owner, "INTERRUPTED", null, null, lost.getMessage(), Instant.now());
                return;
            } catch (IOException unreachable) {
                if (++failures > POLL_FAILURES_ALLOWED) {
                    jobs.finish(job.id(), owner, "INTERRUPTED", null, null, "lost contact with the execution host: " + unreachable.getMessage(), Instant.now());
                    return;
                }
                sleep();
                continue;
            }
            if (status.finished()) {
                conclude(job, provider, handle, spec, status, cancelSent);
                return;
            }
            sleep();
        }
    }

    private void conclude(Rows.Job job, ExecutionProvider provider, String handle, ExecutionProvider.JobSpec spec, ExecutionProvider.ProviderStatus status, boolean cancelled)
            throws IOException {
        if (cancelled) {
            jobs.finish(job.id(), owner, "CANCELLED", null, null, null, Instant.now());
            return;
        }
        if (!status.state().equals(ExecutionProvider.ProviderStatus.SUCCEEDED)) {
            jobs.finish(job.id(), owner, "FAILED", null, null, status.message(), Instant.now());
            return;
        }
        Path collected = properties.dataDir().resolve("collected").resolve(job.id()).toAbsolutePath();
        deleteTree(collected);
        provider.collect(handle, collected);
        if (job.operation().equals("SNAPSHOT")) {
            storeSnapshot(job, collected);
            return;
        }
        JsonNode evidence = EvidenceVerifier.verify(collected);
        Path kept = properties.dataDir().resolve("evidence").resolve(job.id()).toAbsolutePath();
        deleteTree(kept);
        Files.createDirectories(kept.getParent());
        Files.move(collected, kept);
        List<Rows.Finding> findings = new ArrayList<>();
        evidence.path("verdict").path("findings").forEach(item -> findings.add(new Rows.Finding(item.path("code").asText(), item.path("severity").asText(), item.path("message").asText())));
        jobs.replaceFindings(job.id(), findings);
        jobs.finish(job.id(), owner, "SUCCEEDED", evidence.path("verdict").path("decision").asText(), job.id() + "/evidence.json", null, Instant.now());
    }

    private void storeSnapshot(Rows.Job job, Path collected) throws IOException {
        Path root = collected.resolve("snapshots");
        if (!Files.isDirectory(root)) {
            jobs.finish(job.id(), owner, "FAILED", null, null, "the snapshot job produced no snapshot", Instant.now());
            return;
        }
        try (var projectsDirs = Files.list(root)) {
            for (Path projectDir : projectsDirs.toList()) {
                try (var versions = Files.list(projectDir)) {
                    for (Path folder : versions.filter(Files::isDirectory).filter(path -> !path.getFileName().toString().startsWith(".")).toList()) {
                        JsonNode record = Json.read(folder.resolve("snapshot.json"));
                        Path target = properties.dataDir().resolve("snapshots").resolve(projectDir.getFileName()).resolve(folder.getFileName()).toAbsolutePath();
                        if (!Files.exists(target)) {
                            Files.createDirectories(target.getParent());
                            Files.move(folder, target);
                        }
                        snapshots.insertIfAbsent(new Rows.Snapshot(record.path("snapshot_id").asText(), job.projectId(), record.path("eligibility").asText(),
                                record.path("profile_id").asText(), record.path("source").path("manifest_sha256").asText(), target.toString(), Instant.now()));
                        jobs.finish(job.id(), owner, "SUCCEEDED", null, null, null, Instant.now());
                        return;
                    }
                }
            }
        }
        jobs.finish(job.id(), owner, "FAILED", null, null, "the snapshot job produced no snapshot", Instant.now());
    }

    private static String slug(Rows.Project project) {
        return org.upgradeguard.sentry.maven.EvidenceBuilder.slug(Path.of(project.path()).getFileName().toString());
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
