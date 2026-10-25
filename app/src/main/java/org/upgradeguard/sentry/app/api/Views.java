package org.upgradeguard.sentry.app.api;

import java.time.Instant;
import java.util.List;
import org.upgradeguard.sentry.app.store.Rows;

public final class Views {
    private Views() {
    }

    public record ProjectView(String id, String name, String ecosystem, Instant createdAt) {
        public static ProjectView of(Rows.Project project) {
            return new ProjectView(project.id(), project.name(), project.ecosystem(), project.createdAt());
        }
    }

    public record FindingView(String code, String severity, String message) {
    }

    public record JobView(String id, String projectId, String operation, String status, String verdict, String error, String stage, String executionProvider,
                          Instant createdAt, Instant finishedAt, List<FindingView> findings) {
        public static JobView of(Rows.Job job, List<Rows.Finding> findings) {
            return new JobView(job.id(), job.projectId(), job.operation(), job.status(), job.verdict(), job.error(), job.stage(), job.executionProvider(),
                    job.createdAt(), job.finishedAt(), findings.stream().map(item -> new FindingView(item.code(), item.severity(), item.message())).toList());
        }
    }

    public record SnapshotView(String id, String eligibility, String profileId, Instant createdAt) {
        public static SnapshotView of(Rows.Snapshot snapshot) {
            return new SnapshotView(snapshot.id(), snapshot.eligibility(), snapshot.profileId(), snapshot.createdAt());
        }
    }

    public record Registration(String name, String path) {
    }

    public record Submitted(String jobId) {
    }

    public record SubmissionBody(String operation, String packageName, String fromVersion, String toVersion, String snapshotId, String execution) {
    }
}
