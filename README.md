# OSM MTB Trailzustand

Interaktive Karte mit drei fiktiven MTB-Trails am Tegernsee. Wähle einen Trail, sieh dir die Regenvorhersage an und melde seinen Zustand mit einer optionalen Notiz. Meldungen werden mit Zeitstempel lokal in H2 gespeichert. Seeberg-Waldtrail und Kammspur starten mit je drei Beispielmeldungen.

## Starten

Du brauchst Java 17, Maven und eine Internetverbindung für Karte und Wetter.

```sh
mvn liberty:dev
```

Öffne [http://localhost:9080](http://localhost:9080). Mit `q` beendest du den Entwicklungsserver.

## GPX-Trail importieren

Öffne **GPX-Trail importieren**, wähle eine Datei bis 2 MiB und lege den Schwierigkeitsgrad fest. Ein eigener Name ist optional. Pro Datei wird ein Trail in H2 gespeichert. Der Import verwendet GPX-Tracks und greift bei Bedarf auf GPX-Routen zurück. Getrennte Segmente bleiben getrennt. Die Karte springt nach dem Import zum neuen Trail.

## Technik

- **Frontend:** Bootstrap, Leaflet und OpenStreetMap.
- **Backend:** Java mit JAX-RS auf Open Liberty. Der MicroProfile Rest Client ruft Wetterdaten von Open-Meteo ab.
- **CSV-Download:** Ein Jakarta Servlet exportiert die bis zu 20 neuesten Meldungen des gewählten Trails inklusive Notizen. Den Download findest du unter der Meldungsliste.
- **Daten:** Die Beispieltrails stehen in `src/main/resources/trails.geojson`.

## REST-API

| Methode und Pfad | Funktion |
| --- | --- |
| `GET /api/trails?bbox=11.72,47.69,11.81,47.75` | Trails im Kartenausschnitt als GeoJSON |
| `POST /api/trails?difficulty=Mittel` | GPX-Datei mit `Content-Type: application/gpx+xml` importieren. Optional `name` angeben. |
| `GET /api/trails/{id}` | Einzelnen Trail als GeoJSON abrufen |
| `GET /api/trails/kammspur/forecast` | Regenvorhersage am Trail-Einstieg |
| `GET /api/trails/kammspur/reports` | Zustandsmeldungen abrufen |
| `POST /api/trails/kammspur/reports` | Zustand melden, zum Beispiel `{"condition":"muddy","note":"Matsch in der letzten Kurve."}` |
| `GET /exports/reports.csv?trail=kammspur` | Meldungen als CSV herunterladen |
