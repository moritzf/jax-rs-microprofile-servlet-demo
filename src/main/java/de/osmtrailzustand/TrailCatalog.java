package de.osmtrailzustand;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TrailCatalog {
    private final List<JsonObject> trails = loadTrails();

    private static List<JsonObject> loadTrails() {
        try (var reader = Json.createReader(TrailCatalog.class.getResourceAsStream("/trails.geojson"))) {
            return List.copyOf(reader.readObject().getJsonArray("features").getValuesAs(JsonObject.class));
        }
    }

    public Optional<JsonObject> find(String id) {
        return trails.stream().filter(trail -> trail.getString("id").equals(id)).findFirst();
    }

    public JsonObject collection(Rectangle2D bounds) {
        var features = Json.createArrayBuilder();
        for (var trail : trails) {
            if (bounds == null || intersects(coordinates(trail), bounds)) {
                features.add(trail);
            }
        }
        return Json.createObjectBuilder().add("type", "FeatureCollection").add("features", features).build();
    }

    static JsonArray coordinates(JsonObject trail) {
        return trail.getJsonObject("geometry").getJsonArray("coordinates");
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
