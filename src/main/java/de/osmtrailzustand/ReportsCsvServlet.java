package de.osmtrailzustand;

import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.Normalizer;

@WebServlet("/exports/reports.csv")
public class ReportsCsvServlet extends HttpServlet {
    @Inject TrailCatalog catalog;
    @Inject Reports reports;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        var trailId = request.getParameter("trail");
        if (trailId == null || trailId.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Bitte einen Trail angeben.");
            return;
        }
        JsonArray history;
        try {
            if (catalog.find(trailId).isEmpty()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Trail nicht gefunden.");
                return;
            }
            history = reports.list(trailId);
        } catch (SQLException e) {
            throw new ServletException("Zustandsmeldungen konnten nicht geladen werden.", e);
        }

        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + trailId + "-reports.csv\"");
        var writer = response.getWriter();
        writer.write("reportedAt,condition,note\r\n");
        for (var report : history.getValuesAs(JsonObject.class)) {
            writer.write(report.getString("reportedAt") + "," + report.getString("condition") + ","
                    + csvNote(report.getString("note")) + "\r\n");
        }
    }

    private static String csvNote(String note) {
        var leading = Normalizer.normalize(note, Normalizer.Form.NFKC).stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) {
            note = "Notiz: " + note;
        }
        return "\"" + note.replace("\"", "\"\"") + "\"";
    }
}
