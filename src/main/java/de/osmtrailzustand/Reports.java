package de.osmtrailzustand;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class Reports {
    @Inject
    @ConfigProperty(name = "osm-mtb-trailzustand.db.path")
    String databasePath;

    private final JdbcDataSource dataSource = new JdbcDataSource();

    @PostConstruct
    void initialize() {
        dataSource.setURL("jdbc:h2:file:" + Path.of(databasePath).toAbsolutePath());
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reports (
                        id UUID PRIMARY KEY,
                        trail_id VARCHAR(64) NOT NULL,
                        condition VARCHAR(16) NOT NULL,
                        reported_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
                    )
                    """);
            // Allow inserts without a note in databases created by the previous version.
            statement.executeUpdate("ALTER TABLE reports ALTER COLUMN IF EXISTS note DROP NOT NULL");
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS reports_by_trail ON reports (trail_id, reported_at DESC, id)
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Datenbank konnte nicht initialisiert werden.", e);
        }
    }

    public JsonObject add(String trailId, String condition) throws SQLException {
        var id = UUID.randomUUID();
        var time = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO reports (id, trail_id, condition, reported_at) VALUES (?, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setString(2, trailId);
            statement.setString(3, condition);
            statement.setObject(4, time);
            statement.executeUpdate();
        }
        return report(id, condition, time);
    }

    public JsonArray list(String trailId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, condition, reported_at FROM reports
                     WHERE trail_id = ? ORDER BY reported_at DESC, id LIMIT 20
                     """)) {
            statement.setString(1, trailId);
            try (var result = statement.executeQuery()) {
                var reports = Json.createArrayBuilder();
                while (result.next()) {
                    reports.add(report(result.getObject("id", UUID.class), result.getString("condition"),
                            result.getObject("reported_at", Instant.class)));
                }
                return reports.build();
            }
        }
    }

    public Optional<JsonObject> find(String trailId, UUID reportId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT condition, reported_at FROM reports WHERE trail_id = ? AND id = ?
                     """)) {
            statement.setString(1, trailId);
            statement.setObject(2, reportId);
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(report(reportId, result.getString("condition"),
                            result.getObject("reported_at", Instant.class)));
                }
                return Optional.empty();
            }
        }
    }

    private static JsonObject report(UUID id, String condition, Instant time) {
        return Json.createObjectBuilder().add("id", id.toString()).add("condition", condition)
                .add("reportedAt", time.toString()).build();
    }
}
