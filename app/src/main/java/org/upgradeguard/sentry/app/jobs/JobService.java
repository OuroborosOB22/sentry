package org.upgradeguard.sentry.app.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.app.store.SnapshotRepository;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.Versions;
import org.upgradeguard.sentry.snapshot.ComparisonEngine;

@Service
public class JobService {
    public static final Set<String> OPERATIONS = Set.of("SNAPSHOT", "REHEARSE_UPGRADE", "REHEARSE_RECOVERY");

    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    private final JobRepository jobs;
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final ProviderRegistry providers;
    private final SentryProperties properties;

    public JobService(JobRepository jobs, ProjectRepository projects, SnapshotRepository snapshots, ProviderRegistry providers, SentryProperties properties) {
        this.jobs = jobs;
        this.projects = projects;
        this.snapshots = snapshots;
        this.providers = providers;
        this.properties = properties;
    }

    public record Submission(String operation, String packageName, String fromVersion, String toVersion, String snapshotId, String execution) {
    }

    public String submit(String projectId, Submission submission) {
        try {
            return queue(projectId, submission);
        } catch (IllegalArgumentException problem) {
            throw new Rejected(problem.getMessage());
        }
    }

    private String queue(String projectId, Submission submission) {
        Rows.Project project = projects.find(projectId).orElseThrow(() -> new Rejected("there is no project " + projectId));
        if (!OPERATIONS.contains(submission.operation())) {
            throw new Rejected("operation must be one of " + OPERATIONS);
        }
        String execution = submission.execution() == null || submission.execution().isBlank() ? properties.defaultExecution() : submission.execution();
        if (providers.find(execution).isEmpty()) {
            throw new Rejected("execution must be one of " + providers.ids());
        }
        if (execution.equals("LOCAL") && !properties.auth().tokens().isEmpty() && !properties.allowLocalInHosted()) {
            throw new Rejected("local execution runs project code next to the hub, so it is switched off while access tokens are configured; use another execution provider");
        }
        ObjectNode request = Json.object();
        request.put("ecosystem", project.ecosystem());
        if (!submission.operation().equals("SNAPSHOT")) {
            request.put("package", validPackage(project.ecosystem(), submission.packageName()));
        }
        if (submission.operation().equals("REHEARSE_UPGRADE")) {
            request.put("from", Versions.require(submission.fromVersion()));
            request.put("to", Versions.require(submission.toVersion()));
        }
        if (submission.operation().equals("REHEARSE_RECOVERY")) {
            if (!project.ecosystem().equals("MAVEN")) {
                throw new Rejected("recovery rehearsal is available for Maven projects");
            }
            if (submission.snapshotId() == null || snapshots.find(projectId, submission.snapshotId()).isEmpty()) {
                throw new Rejected("choose a snapshot that belongs to this project");
            }
            request.put("snapshotId", submission.snapshotId());
        }
        String id = "job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jobs.insert(new Rows.Job(id, projectId, submission.operation(), "QUEUED", Json.text(request), execution, null, null, null, 0, 2, null, null, false,
                null, null, Instant.now(), null, null));
        return id;
    }

    private static String validPackage(String ecosystem, String name) {
        if (name == null || name.isBlank()) {
            throw new Rejected("a package is required");
        }
        try {
            if (ecosystem.equals("MAVEN")) {
                return MavenCoordinate.parse(name).text();
            }
        } catch (IllegalArgumentException problem) {
            throw new Rejected(problem.getMessage());
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new Rejected("the package name may only use letters, digits, dots, dashes and underscores");
        }
        return name;
    }

    public Optional<Rows.Job> find(String id) {
        return jobs.find(id);
    }

    public boolean cancel(String id) {
        return jobs.requestCancel(id, Instant.now());
    }

    public List<Rows.Job> forProject(String projectId) {
        return jobs.forProject(projectId, 50);
    }

    public Optional<JsonNode> evidence(String id) throws IOException {
        Optional<Rows.Job> job = jobs.find(id);
        if (job.isEmpty() || job.get().evidencePath() == null) {
            return Optional.empty();
        }
        Path file = properties.dataDir().resolve("evidence").resolve(job.get().evidencePath()).toAbsolutePath().normalize();
        if (!file.startsWith(properties.dataDir().resolve("evidence").toAbsolutePath().normalize()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(Json.read(file));
    }

    public JsonNode compare(String projectId, String oldId, String newId) throws IOException {
        Rows.Project project = projects.find(projectId).orElseThrow(() -> new Rejected("there is no project " + projectId));
        Rows.Snapshot older = oldId == null ? snapshots.forProject(projectId).stream().filter(item -> item.eligibility().equals("PASSING")).reduce((first, second) -> second)
                .orElseThrow(() -> new Rejected("there is no passing snapshot to compare against yet")) : snapshots.find(projectId, oldId).orElseThrow(() -> new Rejected("unknown snapshot " + oldId));
        Rows.Snapshot newer = newId == null ? snapshots.forProject(projectId).stream().findFirst().orElseThrow(() -> new Rejected("there is no snapshot yet"))
                : snapshots.find(projectId, newId).orElseThrow(() -> new Rejected("unknown snapshot " + newId));
        return ComparisonEngine.compare(Path.of(older.folder()), Path.of(newer.folder()));
    }
}
