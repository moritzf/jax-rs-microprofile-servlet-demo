package de.osmtrailzustand;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class GpxTrail {
    private GpxTrail() {
    }

    static JsonArray parse(byte[] contents) throws IOException, SAXException {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 64);
        Element root;
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler());
            root = parser.parse(new ByteArrayInputStream(contents)).getDocumentElement();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Der GPX-Parser konnte nicht initialisiert werden.", e);
        }
        var namespace = root.getNamespaceURI();
        if (!"gpx".equals(root.getLocalName()) || (namespace != null
                && !namespace.equals("http://www.topografix.com/GPX/1/0")
                && !namespace.equals("http://www.topografix.com/GPX/1/1"))) {
            throw new IllegalArgumentException("Die Datei ist kein GPX-Dokument.");
        }

        var segments = new ArrayList<JsonArray>();
        for (var track : children(root, "trk")) {
            for (var segment : children(track, "trkseg")) {
                var line = line(segment, "trkpt");
                if (line.size() >= 2) {
                    segments.add(line);
                }
            }
        }
        if (segments.isEmpty()) {
            for (var route : children(root, "rte")) {
                var line = line(route, "rtept");
                if (line.size() >= 2) {
                    segments.add(line);
                }
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Die GPX-Datei enthält keinen Track oder keine Route mit mindestens zwei Punkten.");
        }
        return Json.createArrayBuilder(segments).build();
    }

    private static JsonArray line(Element parent, String pointName) {
        var line = Json.createArrayBuilder();
        for (var point : children(parent, pointName)) {
            var latitude = coordinate(point, "lat", 90);
            var longitude = coordinate(point, "lon", 180);
            line.add(Json.createArrayBuilder().add(longitude).add(latitude));
        }
        return line.build();
    }

    private static double coordinate(Element point, String attribute, int limit) {
        try {
            double value = Double.parseDouble(point.getAttribute(attribute));
            if (-limit <= value && value <= limit) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Missing or malformed coordinates are reported the same way as out-of-range coordinates.
        }
        throw new IllegalArgumentException("Die GPX-Datei enthält ungültige Koordinaten.");
    }

    private static List<Element> children(Element parent, String name) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())
                    && Objects.equals(parent.getNamespaceURI(), element.getNamespaceURI())) {
                elements.add(element);
            }
        }
        return elements;
    }
}
