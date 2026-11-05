package de.osmtrailzustand;

import jakarta.json.JsonArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpxTrailTest {
    @Test
    void readsSeparateSegmentsInLongitudeLatitudeOrder() throws Exception {
        var segments = parse("""
                <gpx xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><name> Kammhöhe </name>
                    <trkseg><trkpt lat="47.71" lon="11.78"/><trkpt lat="47.72" lon="11.79"/></trkseg>
                    <trkseg><trkpt lat="47.73" lon="11.80"/><trkpt lat="47.74" lon="11.81"/></trkseg>
                  </trk>
                </gpx>
                """);

        assertEquals(2, segments.size());
        var firstPoint = segments.getJsonArray(0).getJsonArray(0);
        assertEquals(11.78, firstPoint.getJsonNumber(0).doubleValue());
        assertEquals(47.71, firstPoint.getJsonNumber(1).doubleValue());
        assertEquals(2, segments.getJsonArray(1).size());
    }

    @Test
    void fallsBackToRouteWithoutAUsableTrack() throws Exception {
        var segments = parse("""
                <gpx xmlns="http://www.topografix.com/GPX/1/1">
                  <metadata><name>Talroute</name></metadata>
                  <rte><rtept lat="47.70" lon="11.79"/><rtept lat="47.72" lon="11.81"/></rte>
                  <trk><trkseg><trkpt lat="47.71" lon="11.78"/></trkseg></trk>
                </gpx>
                """);

        assertEquals(1, segments.size());
        assertEquals(2, segments.getJsonArray(0).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<xml/>",
        "<gpx><wpt lat='47' lon='11'/></gpx>",
        "<gpx><rte><rtept lat='47' lon='11'/></rte></gpx>",
        "<gpx><rte><rtept lat='91' lon='11'/><rtept lat='47' lon='11'/></rte></gpx>"
    })
    void rejectsInvalidGpx(String xml) {
        assertThrows(IllegalArgumentException.class, () -> parse(xml));
    }

    @Test
    void rejectsXmlWithAnExternalEntity() {
        assertThrows(SAXException.class, () -> parse("""
                <!DOCTYPE gpx [<!ENTITY external SYSTEM "file:///not-a-test-input">]>
                <gpx><metadata><name>&external;</name></metadata></gpx>
                """));
    }

    private static JsonArray parse(String xml) throws Exception {
        return GpxTrail.parse(xml.getBytes(StandardCharsets.UTF_8));
    }
}
