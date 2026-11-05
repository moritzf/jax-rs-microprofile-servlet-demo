package de.osmtrailzustand;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.awt.geom.Rectangle2D;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class TrailCatalog {
    @Inject TrailDatabase database;

    private final List<JsonObject> demoTrails = loadTrails();

    @PostConstruct
    void initialize() {
        try (var connection = database.connect();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS imported_trails (
                        id VARCHAR(64) PRIMARY KEY,
                        feature CHARACTER LARGE OBJECT NOT NULL
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Der Trail-Katalog konnte nicht initialisiert werden.", e);
        }
    }

    private static List<JsonObject> loadTrails() {
        try (var reader = Json.createReader(TrailCatalog.class.getResourceAsStream("/trails.geojson"))) {
            return List.copyOf(reader.readObject().getJsonArray("features").getValuesAs(JsonObject.class));
        }
    }

    public Optional<JsonObject> find(String id) throws SQLException {
        var demo = demoTrails.stream().filter(trail -> trail.getString("id").equals(id)).findFirst();
        if (demo.isPresent()) {
            return demo;
        }
        try (var connection = database.connect();
             var statement = connection.prepareStatement("SELECT feature FROM imported_trails WHERE id = ?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(readFeature(result.getString(1))) : Optional.empty();
            }
        }
    }

    public JsonObject collection(Rectangle2D bounds) throws SQLException {
        var trails = new ArrayList<>(demoTrails);
        try (var connection = database.connect();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT feature FROM imported_trails ORDER BY id")) {
            while (result.next()) {
                trails.add(readFeature(result.getString(1)));
            }
        }
        var features = Json.createArrayBuilder();
        for (var trail : trails) {
            if (bounds == null || segments(trail).stream().anyMatch(line -> intersects(line, bounds))) {
                features.add(trail);
            }
        }
        return Json.createObjectBuilder().add("type", "FeatureCollection").add("features", features).build();
    }

    public JsonObject add(String name, String difficulty, JsonArray segments) throws SQLException {
        var id = UUID.randomUUID().toString();
        boolean single = segments.size() == 1;
        var feature = Json.createObjectBuilder()
                .add("type", "Feature")
                .add("id", id)
                .add("properties", Json.createObjectBuilder().add("name", name).add("difficulty", difficulty)
                        .add("description", "Aus GPX importiert."))
                .add("geometry", Json.createObjectBuilder().add("type", single ? "LineString" : "MultiLineString")
                        .add("coordinates", single ? segments.getJsonArray(0) : segments))
                .build();
        try (var connection = database.connect();
             var statement = connection.prepareStatement("INSERT INTO imported_trails (id, feature) VALUES (?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, feature.toString());
            statement.executeUpdate();
        }
        return feature;
    }

    static JsonArray startCoordinates(JsonObject trail) {
        return segments(trail).get(0).getJsonArray(0);
    }

    private static List<JsonArray> segments(JsonObject trail) {
        var geometry = trail.getJsonObject("geometry");
        var coordinates = geometry.getJsonArray("coordinates");
        return geometry.getString("type").equals("LineString")
                ? List.of(coordinates) : coordinates.getValuesAs(JsonArray.class);
    }

    private static JsonObject readFeature(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }

    private static boolean intersects(JsonArray coordinates, Rectangle2D bounds) {
        for (int i = 1; i < coordinates.size(); i++) {
            var start = coordinates.getJsonArray(i - 1);
            var end = coordinates.getJsonArray(i);
            if (bounds.intersectsLine(
                    start.getJsonNumber(0).doubleValue(), start.getJsonNumber(1).doubleValue(),
                    end.getJsonNumber(0).doubleValue(), end.getJsonNumber(1).doubleValue())) {
                return true;
            }
        }
        return false;
    }
}
