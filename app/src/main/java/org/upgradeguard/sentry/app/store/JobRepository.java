package org.upgradeguard.sentry.app.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Rows.Job job) {
        jdbc.update("INSERT INTO jobs (id, project_id, operation, status, request_json, execution_provider, attempts, max_attempts, cancel_requested, created_at) "
                        + "VALUES (?, ?, ?, 'QUEUED', ?, ?, 0, ?, FALSE, ?)",
                job.id(), job.projectId(), job.operation(), job.requestJson(), job.executionProvider(), job.maxAttempts(), Db.stamp(job.createdAt()));
    }

    public Optional<Rows.Job> find(String id) {
        return jdbc.query("SELECT * FROM jobs WHERE id = ?", (rows, number) -> map(rows), id).stream().findFirst();
    }

    public List<Rows.Job> forProject(String projectId, int limit) {
        return jdbc.query("SELECT * FROM jobs WHERE project_id = ? ORDER BY created_at DESC LIMIT ?", (rows, number) -> map(rows), projectId, limit);
    }

    public List<Rows.Job> recent(int limit) {
        return jdbc.query("SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?", (rows, number) -> map(rows), limit);
    }

    public int countRunning(String provider) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE status = 'RUNNING' AND execution_provider = ?", Integer.class, provider);
        return count == null ? 0 : count;
    }

    public Optional<Rows.Job> claimNext(String owner, Duration lease, Instant now) {
        List<String> candidates = jdbc.queryForList("SELECT id FROM jobs WHERE status = 'QUEUED' AND cancel_requested = FALSE ORDER BY created_at LIMIT 10", String.class);
        for (String id : candidates) {
            int claimed = jdbc.update("UPDATE jobs SET status = 'RUNNING', lease_owner = ?, lease_expires_at = ?, heartbeat_at = ?, "
                            + "started_at = COALESCE(started_at, ?), attempts = attempts + 1, stage = 'starting' WHERE id = ? AND status = 'QUEUED'",
                    owner, Db.stamp(now.plus(lease)), Db.stamp(now), Db.stamp(now), id);
            if (claimed == 1) {
                return find(id);
            }
        }
        return Optional.empty();
    }

    public boolean heartbeat(String id, String owner, Duration lease, Instant now, String stage) {
        return jdbc.update("UPDATE jobs SET lease_expires_at = ?, heartbeat_at = ?, stage = ? WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?",
                Db.stamp(now.plus(lease)), Db.stamp(now), stage, id, owner) == 1;
    }

    public void setRemoteId(String id, String remoteId) {
        jdbc.update("UPDATE jobs SET remote_id = ? WHERE id = ?", remoteId, id);
    }

    public boolean finish(String id, String owner, String status, String verdict, String evidencePath, String error, Instant now) {
        return jdbc.update("UPDATE jobs SET status = ?, verdict = ?, evidence_path = ?, error = ?, finished_at = ?, lease_owner = NULL, lease_expires_at = NULL, stage = NULL "
                        + "WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?",
                status, verdict, evidencePath, error == null ? null : cut(error, 1000), Db.stamp(now), id, owner) == 1;
    }

    public boolean requestCancel(String id, Instant now) {
        int queued = jdbc.update("UPDATE jobs SET status = 'CANCELLED', cancel_requested = TRUE, finished_at = ? WHERE id = ? AND status = 'QUEUED'", Db.stamp(now), id);
        if (queued == 1) {
            return true;
        }
        return jdbc.update("UPDATE jobs SET cancel_requested = TRUE WHERE id = ? AND status = 'RUNNING'", id) == 1;
    }

    public boolean cancelRequested(String id) {
        Boolean flag = jdbc.queryForObject("SELECT cancel_requested FROM jobs WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(flag);
    }

    public int recoverExpired(Instant now) {
        int cancelled = jdbc.update("UPDATE jobs SET status = 'CANCELLED', finished_at = ?, lease_owner = NULL, lease_expires_at = NULL, stage = NULL "
                + "WHERE status = 'RUNNING' AND lease_expires_at < ? AND cancel_requested = TRUE", Db.stamp(now), Db.stamp(now));
        int requeued = jdbc.update("UPDATE jobs SET status = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL, stage = NULL "
                + "WHERE status = 'RUNNING' AND lease_expires_at < ? AND attempts < max_attempts AND remote_id IS NULL", Db.stamp(now));
        int interrupted = jdbc.update("UPDATE jobs SET status = 'INTERRUPTED', error = 'the worker stopped before this job finished', finished_at = ?, lease_owner = NULL, "
                + "lease_expires_at = NULL, stage = NULL WHERE status = 'RUNNING' AND lease_expires_at < ?", Db.stamp(now), Db.stamp(now));
        return cancelled + requeued + interrupted;
    }

    public void replaceFindings(String jobId, List<Rows.Finding> findings) {
        jdbc.update("DELETE FROM findings WHERE job_id = ?", jobId);
        int position = 0;
        for (Rows.Finding finding : findings) {
            jdbc.update("INSERT INTO findings (job_id, ordinal, code, severity, message) VALUES (?, ?, ?, ?, ?)",
                    jobId, position++, finding.code(), finding.severity(), cut(finding.message(), 1000));
        }
    }

    public List<Rows.Finding> findings(String jobId) {
        return jdbc.query("SELECT code, severity, message FROM findings WHERE job_id = ? ORDER BY ordinal",
                (rows, number) -> new Rows.Finding(rows.getString("code"), rows.getString("severity"), rows.getString("message")), jobId);
    }

    private static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static Rows.Job map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Rows.Job(rows.getString("id"), rows.getString("project_id"), rows.getString("operation"), rows.getString("status"),
                rows.getString("request_json"), rows.getString("execution_provider"), rows.getString("verdict"), rows.getString("evidence_path"),
                rows.getString("error"), rows.getInt("attempts"), rows.getInt("max_attempts"), rows.getString("lease_owner"), Db.instant(rows, "lease_expires_at"),
                rows.getBoolean("cancel_requested"), rows.getString("stage"), rows.getString("remote_id"), Db.instant(rows, "created_at"),
                Db.instant(rows, "started_at"), Db.instant(rows, "finished_at"));
    }
}
