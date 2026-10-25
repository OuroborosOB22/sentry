package org.upgradeguard.sentry.app.store;

import java.time.Instant;

public final class Rows {
    private Rows() {
    }

    public record Project(String id, String name, String path, String ecosystem, Instant createdAt) {
    }

    public record Snapshot(String id, String projectId, String eligibility, String profileId, String manifestSha256, String folder, Instant createdAt) {
    }

    public record Job(String id, String projectId, String operation, String status, String requestJson, String executionProvider, String verdict,
                      String evidencePath, String error, int attempts, int maxAttempts, String leaseOwner, Instant leaseExpiresAt, boolean cancelRequested,
                      String stage, String remoteId, Instant createdAt, Instant startedAt, Instant finishedAt) {
        public boolean finished() {
            return switch (status) {
                case "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "INTERRUPTED" -> true;
                default -> false;
            };
        }
    }

    public record Finding(String code, String severity, String message) {
    }
}
