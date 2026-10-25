package org.upgradeguard.sentry.app.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {
    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Rows.Project project) {
        jdbc.update("INSERT INTO projects (id, name, path, ecosystem, created_at) VALUES (?, ?, ?, ?, ?)",
                project.id(), project.name(), project.path(), project.ecosystem(), Db.stamp(project.createdAt()));
    }

    public Optional<Rows.Project> find(String id) {
        return jdbc.query("SELECT * FROM projects WHERE id = ?", (rows, number) -> map(rows, 0), id).stream().findFirst();
    }

    public List<Rows.Project> all() {
        return jdbc.query("SELECT * FROM projects ORDER BY name", (rows, number) -> map(rows, number));
    }

    public Optional<Rows.Project> findByPath(String path) {
        return jdbc.query("SELECT * FROM projects WHERE path = ?", (rows, number) -> map(rows, number), path).stream().findFirst();
    }

    private static Rows.Project map(java.sql.ResultSet rows, int number) throws java.sql.SQLException {
        Instant created = Db.instant(rows, "created_at");
        return new Rows.Project(rows.getString("id"), rows.getString("name"), rows.getString("path"), rows.getString("ecosystem"), created);
    }
}
