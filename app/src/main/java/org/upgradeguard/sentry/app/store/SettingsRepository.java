package org.upgradeguard.sentry.app.store;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {
    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String name) {
        return jdbc.queryForList("SELECT setting_value FROM settings WHERE name = ?", String.class, name).stream().findFirst();
    }

    public void put(String name, String value) {
        if (jdbc.update("UPDATE settings SET setting_value = ? WHERE name = ?", value, name) == 0) {
            jdbc.update("INSERT INTO settings (name, setting_value) VALUES (?, ?)", name, value);
        }
    }
}
