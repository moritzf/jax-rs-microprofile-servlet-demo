package de.osmtrailzustand;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.xml.sax.SAXException;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Path("/trails")
@Produces("application/json")
public class TrailResource {
    private static final Set<String> CONDITIONS = Set.of("dry", "damp", "muddy", "obstacle");
    private static final Set<String> DIFFICULTIES = Set.of("Leicht", "Mittel", "Schwer");
    private static final int MAX_GPX_BYTES = 2 * 1024 * 1024;

    @Inject TrailCatalog catalog;
    @Inject Reports reports;
    @Inject @RestClient WeatherClient weather;
    @Inject @ConfigProperty(name = "osm-mtb-trailzustand.demo.mode", defaultValue = "false")
    boolean demoMode;

    @GET
    @Path("/settings")
    public JsonObject settings() {
        return Json.createObjectBuilder().add("demoMode", demoMode).build();
    }

    @GET
    @Produces({"application/geo+json", "application/json"})
    public JsonObject trails(@QueryParam("bbox") String bbox) throws SQLException {
        return catalog.collection(bbox == null ? null : parseBounds(bbox));
    }

    @GET
    @Path("/{id}")
    @Produces({"application/geo+json", "application/json"})
    public JsonObject trail(@PathParam("id") String id) throws SQLException {
        return requireTrail(id);
    }

    @POST
    @Consumes({"application/gpx+xml", "application/xml", "text/xml"})
    @Produces({"application/geo+json", "application/json"})
    public Response importTrail(InputStream input, @QueryParam("name") String name,
                                @QueryParam("difficulty") String difficulty, @Context UriInfo uri)
            throws IOException, SQLException {
        requireWritable();
        if (difficulty == null || !DIFFICULTIES.contains(difficulty)) {
            throw error(400, "Bitte einen gültigen Schwierigkeitsgrad angeben.");
        }
        var trailName = name == null ? "" : name.strip();
        if (trailName.isEmpty()) {
            throw error(400, "Bitte einen Trailnamen angeben.");
        }
        if (trailName.length() > 100) {
            throw error(400, "Der Trailname darf höchstens 100 Zeichen lang sein.");
        }
        var contents = input.readNBytes(MAX_GPX_BYTES + 1);
        if (contents.length > MAX_GPX_BYTES) {
            throw error(413, "Die GPX-Datei darf höchstens 2 MiB groß sein.");
        }
        JsonArray segments;
        try {
            segments = GpxTrail.parse(contents);
        } catch (SAXException e) {
            throw error(400, "Die Datei enthält kein gültiges GPX-XML.");
        } catch (IllegalArgumentException e) {
            throw error(400, e.getMessage());
        }
        var trail = catalog.add(trailName, difficulty, segments);
        return Response.created(uri.getAbsolutePathBuilder().path(trail.getString("id")).build())
                .entity(trail).build();
    }

    @GET
    @Path("/{id}/reports")
    public Response reports(@PathParam("id") String id) throws SQLException {
        requireTrail(id);
        return Response.ok(reports.list(id)).build();
    }

    @GET
    @Path("/{id}/reports/{reportId}")
    public JsonObject report(@PathParam("id") String id, @PathParam("reportId") UUID reportId)
            throws SQLException {
        requireTrail(id);
        return reports.find(id, reportId).orElseThrow(() -> error(404, "Meldung nicht gefunden."));
    }

    @POST
    @Path("/{id}/reports")
    @Consumes("application/json")
    public Response addReport(@PathParam("id") String id, JsonObject request, @Context UriInfo uri)
            throws SQLException {
        requireWritable();
        requireTrail(id);
        if (request == null || !(request.get("condition") instanceof JsonString condition)
                || !CONDITIONS.contains(condition.getString())) {
            throw error(400, "Bitte einen gültigen Zustand angeben.");
        }
        var noteValue = request.get("note");
        if (noteValue != null && !(noteValue instanceof JsonString)) {
            throw error(400, "Die Notiz muss ein Text sein.");
        }
        var note = request.getString("note", "").strip();
        if (note.length() > 280) {
            throw error(400, "Die Notiz darf höchstens 280 Zeichen lang sein.");
        }
        var report = reports.add(id, condition.getString(), note);
        return Response.created(uri.getAbsolutePathBuilder().path(report.getString("id")).build())
                .entity(report).build();
    }

    @GET
    @Path("/{id}/forecast")
    public JsonObject forecast(@PathParam("id") String id) throws SQLException {
        var start = TrailCatalog.startCoordinates(requireTrail(id));
        JsonObject forecast;
        try {
            forecast = weather.forecast(start.getJsonNumber(1).doubleValue(), start.getJsonNumber(0).doubleValue(),
                    "precipitation_probability", 6, "unixtime");
        } catch (ProcessingException | WebApplicationException e) {
            throw error(503, "Wetter vorübergehend nicht verfügbar.");
        }

        var hourly = forecast == null ? null : forecast.getJsonObject("hourly");
        var times = hourly == null ? null : hourly.getJsonArray("time");
        var probabilities = hourly == null ? null : hourly.getJsonArray("precipitation_probability");
        if (times == null || probabilities == null || times.size() != probabilities.size()) {
            throw error(503, "Wetter vorübergehend nicht verfügbar.");
        }
        var hours = Json.createArrayBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i) instanceof JsonNumber time && probabilities.get(i) instanceof JsonNumber probability) {
                hours.add(Json.createObjectBuilder()
                        .add("time", Instant.ofEpochSecond(time.longValue()).toString())
                        .add("rainProbability", probability.intValue()));
            }
        }
        var result = hours.build();
        if (result.isEmpty()) {
            throw error(503, "Wetter vorübergehend nicht verfügbar.");
        }
        return Json.createObjectBuilder().add("hours", result).build();
    }

    private void requireWritable() {
        if (demoMode) {
            throw error(403, "Im Demo-Modus sind GPX-Uploads und neue Zustandsmeldungen deaktiviert.");
        }
    }

    private JsonObject requireTrail(String id) throws SQLException {
        return catalog.find(id).orElseThrow(() -> error(404, "Trail nicht gefunden."));
    }

    private static Rectangle2D parseBounds(String bbox) {
        var values = bbox.split(",", -1);
        if (values.length == 4) {
            try {
                double west = Double.parseDouble(values[0]);
                double south = Double.parseDouble(values[1]);
                double east = Double.parseDouble(values[2]);
                double north = Double.parseDouble(values[3]);
                if (-180 <= west && west < east && east <= 180
                        && -90 <= south && south < north && north <= 90) {
                    return new Rectangle2D.Double(west, south, east - west, north - south);
                }
            } catch (NumberFormatException ignored) {
                // Return the same client error for malformed and out-of-range coordinates.
            }
        }
        throw error(400, "bbox erwartet west,süd,ost,nord als gültige Dezimalgrade.");
    }

    private static WebApplicationException error(int status, String message) {
        return new WebApplicationException(Response.status(status)
                .type("application/json").entity(Json.createObjectBuilder().add("message", message).build()).build());
    }
}
