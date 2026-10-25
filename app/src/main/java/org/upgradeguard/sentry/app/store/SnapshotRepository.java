package org.upgradeguard.sentry.app.store;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SnapshotRepository {
    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(Rows.Snapshot snapshot) {
        if (find(snapshot.projectId(), snapshot.id()).isPresent()) {
            return false;
        }
        jdbc.update("INSERT INTO snapshots (id, project_id, eligibility, profile_id, manifest_sha256, folder, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.id(), snapshot.projectId(), snapshot.eligibility(), snapshot.profileId(), snapshot.manifestSha256(), snapshot.folder(), Db.stamp(snapshot.createdAt()));
        return true;
    }

    public Optional<Rows.Snapshot> find(String projectId, String id) {
        return jdbc.query("SELECT * FROM snapshots WHERE project_id = ? AND id = ?", (rows, number) -> map(rows), projectId, id).stream().findFirst();
    }

    public List<Rows.Snapshot> forProject(String projectId) {
        return jdbc.query("SELECT * FROM snapshots WHERE project_id = ? ORDER BY created_at DESC", (rows, number) -> map(rows), projectId);
    }

    public Optional<Rows.Snapshot> latestPassing(String projectId, String profileId) {
        return jdbc.query("SELECT * FROM snapshots WHERE project_id = ? AND eligibility = 'PASSING' AND profile_id = ? ORDER BY created_at DESC LIMIT 1",
                (rows, number) -> map(rows), projectId, profileId).stream().findFirst();
    }

    private static Rows.Snapshot map(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Rows.Snapshot(rows.getString("id"), rows.getString("project_id"), rows.getString("eligibility"), rows.getString("profile_id"),
                rows.getString("manifest_sha256"), rows.getString("folder"), Db.instant(rows, "created_at"));
    }
}
