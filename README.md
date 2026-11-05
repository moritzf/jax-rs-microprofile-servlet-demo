# OSM MTB Trailzustand

Interaktive Karte mit drei fiktiven MTB-Trails am Tegernsee. Wähle einen Trail, sieh dir die Regenvorhersage an und melde seinen Zustand mit einer optionalen Notiz. Meldungen werden mit Zeitstempel lokal in H2 gespeichert. Seeberg-Waldtrail und Kammspur starten mit je drei Beispielmeldungen.

## Starten

Du brauchst Java 17, Maven und eine Internetverbindung für Karte und Wetter.

```sh
mvn liberty:dev
```

Öffne [http://localhost:9080](http://localhost:9080). Mit `q` beendest du den Entwicklungsserver.

## Technik

- **Frontend:** Bootstrap, Leaflet und OpenStreetMap.
- **Backend:** Java mit JAX-RS auf Open Liberty. Der MicroProfile Rest Client ruft Wetterdaten von Open-Meteo ab.
- **CSV-Download:** Ein Jakarta Servlet exportiert die bis zu 20 neuesten Meldungen des gewählten Trails inklusive Notizen. Den Download findest du unter der Meldungsliste.
- **Daten:** Die Beispieltrails stehen in `src/main/resources/trails.geojson`.

## REST-API

| Methode und Pfad | Funktion |
| --- | --- |
| `GET /api/trails?bbox=11.72,47.69,11.81,47.75` | Trails im Kartenausschnitt als GeoJSON |
| `GET /api/trails/kammspur/forecast` | Regenvorhersage am Trail-Einstieg |
| `GET /api/trails/kammspur/reports` | Zustandsmeldungen abrufen |
| `POST /api/trails/kammspur/reports` | Zustand melden, zum Beispiel `{"condition":"muddy","note":"Matsch in der letzten Kurve."}` |
| `GET /exports/reports.csv?trail=kammspur` | Meldungen als CSV herunterladen |
