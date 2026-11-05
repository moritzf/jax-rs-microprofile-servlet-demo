package de.osmtrailzustand;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class Reports {
    @Inject TrailDatabase database;

    @PostConstruct
    void initialize() {
        try (var connection = database.connect();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reports (
                        id UUID PRIMARY KEY,
                        trail_id VARCHAR(64) NOT NULL,
                        condition VARCHAR(16) NOT NULL,
                        note VARCHAR(280),
                        reported_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.executeUpdate("ALTER TABLE reports ADD COLUMN IF NOT EXISTS note VARCHAR(280)");
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS reports_by_trail ON reports (trail_id, reported_at DESC, id)
                    """);
            statement.executeUpdate("""
                    MERGE INTO reports AS target USING (VALUES
                        (UUID '00000000-0000-4000-8000-000000000001', 'waldrunde', 'dry',
                         'Beispielmeldung: Der Waldboden ist trocken.', CURRENT_TIMESTAMP - INTERVAL '3' HOUR),
                        (UUID '00000000-0000-4000-8000-000000000002', 'waldrunde', 'damp',
                         'Beispielmeldung: Wurzeln im Schatten noch feucht.', CURRENT_TIMESTAMP - INTERVAL '1' DAY),
                        (UUID '00000000-0000-4000-8000-000000000003', 'waldrunde', 'muddy',
                         'Beispielmeldung: Matsch in der letzten Kurve.', CURRENT_TIMESTAMP - INTERVAL '3' DAY),
                        (UUID '00000000-0000-4000-8000-000000000004', 'kammspur', 'damp',
                         'Beispielmeldung: Der Einstieg ist feucht.', CURRENT_TIMESTAMP - INTERVAL '5' HOUR),
                        (UUID '00000000-0000-4000-8000-000000000005', 'kammspur', 'obstacle',
                         'Beispielmeldung: Ein Ast liegt in der zweiten Kurve.', CURRENT_TIMESTAMP - INTERVAL '2' DAY),
                        (UUID '00000000-0000-4000-8000-000000000006', 'kammspur', 'dry',
                         'Beispielmeldung: Der gesamte Trail ist trocken.', CURRENT_TIMESTAMP - INTERVAL '4' DAY)
                    ) AS seed(id, trail_id, condition, note, reported_at)
                    ON target.id = seed.id
                    WHEN NOT MATCHED THEN
                        INSERT (id, trail_id, condition, note, reported_at)
                        VALUES (seed.id, seed.trail_id, seed.condition, seed.note, seed.reported_at)
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Datenbank konnte nicht initialisiert werden.", e);
        }
    }

    public JsonObject add(String trailId, String condition, String note) throws SQLException {
        var id = UUID.randomUUID();
        var time = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try (var connection = database.connect();
             var statement = connection.prepareStatement("""
                     INSERT INTO reports (id, trail_id, condition, note, reported_at) VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setString(2, trailId);
            statement.setString(3, condition);
            statement.setString(4, note);
            statement.setObject(5, time);
            statement.executeUpdate();
        }
        return report(id, condition, note, time);
    }

    public JsonArray list(String trailId) throws SQLException {
        try (var connection = database.connect();
             var statement = connection.prepareStatement("""
                     SELECT id, condition, note, reported_at FROM reports
                     WHERE trail_id = ? ORDER BY reported_at DESC, id LIMIT 20
                     """)) {
            statement.setString(1, trailId);
            try (var result = statement.executeQuery()) {
                var reports = Json.createArrayBuilder();
                while (result.next()) {
                    reports.add(report(result.getObject("id", UUID.class), result.getString("condition"),
                            result.getString("note"), result.getObject("reported_at", Instant.class)));
                }
                return reports.build();
            }
        }
    }

    public Optional<JsonObject> find(String trailId, UUID reportId) throws SQLException {
        try (var connection = database.connect();
             var statement = connection.prepareStatement("""
                     SELECT condition, note, reported_at FROM reports WHERE trail_id = ? AND id = ?
                     """)) {
            statement.setString(1, trailId);
            statement.setObject(2, reportId);
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(report(reportId, result.getString("condition"),
                            result.getString("note"), result.getObject("reported_at", Instant.class)));
                }
                return Optional.empty();
            }
        }
    }

    private static JsonObject report(UUID id, String condition, String note, Instant time) {
        return Json.createObjectBuilder().add("id", id.toString()).add("condition", condition)
                .add("note", note == null ? "" : note).add("reportedAt", time.toString()).build();
    }
}
