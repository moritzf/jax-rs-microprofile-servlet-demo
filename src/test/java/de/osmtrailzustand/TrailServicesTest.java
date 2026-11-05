package de.osmtrailzustand;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonValue;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrailServicesTest {
    @TempDir Path directory;

    private TrailDatabase database;

    @BeforeEach
    void connectToTestDatabase() {
        database = new TrailDatabase();
        database.databasePath = directory.resolve("trails").toString();
        database.initialize();
    }

    @Test
    void seedsOnlyKammspurAndHanglinieAndKeepsNotesOnReopen() throws Exception {
        var reports = reports();
        assertTrue(reports.list("waldrunde").isEmpty());
        var kammspurSamples = reports.list("kammspur");
        var hanglinieSamples = reports.list("hanglinie");
        assertEquals(3, kammspurSamples.size());
        assertEquals(3, hanglinieSamples.size());

        var added = reports.add("waldrunde", "muddy", "Matsch an der Wurzel.");
        connectToTestDatabase();
        var reopened = reports();

        assertEquals(kammspurSamples, reopened.list("kammspur"));
        assertEquals(hanglinieSamples, reopened.list("hanglinie"));
        assertEquals(Json.createArrayBuilder().add(added).build(), reopened.list("waldrunde"));
        var id = UUID.fromString(added.getString("id"));
        assertEquals(added, reopened.find("waldrunde", id).orElseThrow());
        assertTrue(reopened.find("kammspur", id).isEmpty());
    }

    @Test
    void movesPreviouslySeededSeebergReportsWithoutMovingUserReports() throws Exception {
        var reports = reports();
        var samples = reports.list("hanglinie");
        assertEquals(3, samples.size());
        try (var connection = database.connect();
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE reports SET trail_id = 'waldrunde' WHERE trail_id = 'hanglinie'");
        }
        var userReport = reports.add("waldrunde", "dry", "");

        var migrated = reports();

        assertEquals(samples, migrated.list("hanglinie"));
        assertEquals(Json.createArrayBuilder().add(userReport).build(), migrated.list("waldrunde"));
    }

    @Test
    void reopensLegacyReportsWithoutANoteColumn() throws Exception {
        var legacyId = UUID.fromString("11f04265-93f6-4107-b080-3654733bddf2");
        try (var connection = database.connect();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE reports (
                        id UUID PRIMARY KEY,
                        trail_id VARCHAR(64) NOT NULL,
                        condition VARCHAR(16) NOT NULL,
                        reported_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reports VALUES (
                        UUID '11f04265-93f6-4107-b080-3654733bddf2', 'waldrunde', 'dry', CURRENT_TIMESTAMP
                    )
                    """);
        }

        var migrated = reports();

        assertEquals("", migrated.find("waldrunde", legacyId).orElseThrow().getString("note"));
        assertEquals("Feuchte Kurve", migrated.add("waldrunde", "damp", "Feuchte Kurve").getString("note"));
    }

    @Test
    void reopensImportedTrailAndFiltersByActualSegments() throws Exception {
        var trail = catalog().add("Importierter Testtrail", "Mittel", twoSegments());
        var id = trail.getString("id");
        connectToTestDatabase();
        var reopened = catalog();

        assertEquals(trail, reopened.find(id).orElseThrow());
        assertEquals("MultiLineString", trail.getJsonObject("geometry").getString("type"));
        assertEquals(2.0, TrailCatalog.startCoordinates(trail).getJsonNumber(0).doubleValue());
        assertTrue(reopened.collection(new Rectangle2D.Double(3.4, 42.4, 0.2, 0.2))
                .getJsonArray("features").isEmpty());
        assertEquals(Json.createArrayBuilder().add(trail).build(),
                reopened.collection(new Rectangle2D.Double(4.5, 43.5, 1, 1)).getJsonArray("features"));
    }

    @Test
    void requestsWeatherAtTheImportedTrailStartAndMapsAvailableHours() throws Exception {
        var resource = new TrailResource();
        resource.catalog = catalog();
        var id = resource.catalog.add("Wettertest", "Leicht", twoSegments()).getString("id");
        resource.weather = (latitude, longitude, hourly, hours, timeFormat) -> {
            assertEquals(41.0, latitude);
            assertEquals(2.0, longitude);
            assertEquals("precipitation_probability", hourly);
            assertEquals(6, hours);
            assertEquals("unixtime", timeFormat);
            return Json.createObjectBuilder().add("hourly", Json.createObjectBuilder()
                    .add("time", Json.createArrayBuilder().add(0).add(3600))
                    .add("precipitation_probability", Json.createArrayBuilder().add(25).add(JsonValue.NULL)))
                    .build();
        };

        var forecast = resource.forecast(id).getJsonArray("hours");

        assertEquals(1, forecast.size());
        assertEquals("1970-01-01T00:00:00Z", forecast.getJsonObject(0).getString("time"));
        assertEquals(25, forecast.getJsonObject(0).getInt("rainProbability"));
    }

    @Test
    void exportsUtf8CsvWithDownloadHeadersAndEscapedNotes() throws Exception {
        var servlet = csvServlet();
        var report = servlet.reports.add("waldrunde", "damp", "=1+2, \"Wurzeln\"\nNähe Einstieg");
        var request = request("waldrunde");
        var response = mock(HttpServletResponse.class);
        var csv = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(csv));

        servlet.doGet(request, response);

        verify(response).setContentType("text/csv");
        verify(response).setCharacterEncoding("UTF-8");
        verify(response).setHeader("Content-Disposition", "attachment; filename=\"waldrunde-reports.csv\"");
        assertEquals("reportedAt,condition,note\r\n" + report.getString("reportedAt")
                + ",damp,\"Notiz: =1+2, \"\"Wurzeln\"\"\nNähe Einstieg\"\r\n", csv.toString());
    }

    @Test
    void rejectsCsvRequestsWithoutAKnownTrail() throws Exception {
        var servlet = csvServlet();
        var missing = mock(HttpServletResponse.class);
        var unknown = mock(HttpServletResponse.class);

        servlet.doGet(request(null), missing);
        servlet.doGet(request("unbekannt"), unknown);

        verify(missing).sendError(400, "Bitte einen Trail angeben.");
        verify(unknown).sendError(404, "Trail nicht gefunden.");
    }

    private Reports reports() {
        var reports = new Reports();
        reports.database = database;
        reports.initialize();
        return reports;
    }

    private TrailCatalog catalog() {
        var catalog = new TrailCatalog();
        catalog.database = database;
        catalog.initialize();
        return catalog;
    }

    private ReportsCsvServlet csvServlet() {
        var servlet = new ReportsCsvServlet();
        servlet.catalog = catalog();
        servlet.reports = reports();
        return servlet;
    }

    private static HttpServletRequest request(String trailId) {
        var request = mock(HttpServletRequest.class);
        when(request.getParameter("trail")).thenReturn(trailId);
        return request;
    }

    private static JsonArray twoSegments() {
        try (var reader = Json.createReader(new StringReader("[[[2,41],[3,42]],[[4,43],[5,44]]]"))) {
            return reader.readArray();
        }
    }
}
