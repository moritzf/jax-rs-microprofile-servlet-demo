# Deployment auf OCI mit Cloudflare Tunnel

GitHub Actions baut das Image, testet den Container mit der H2-Datenbank
und veröffentlicht `production` und einen Tag mit dem Git-Commit in GHCR.
Die VM prüft alle zwei Minuten auf neue Images und startet sie per Digest.
Sie übernimmt die Version erst, wenn die Meldungs-API erreichbar und der
Demo-Modus aktiv ist. Bei einem Fehler startet sie wieder das vorherige Image.
Beim Austausch des einzelnen App-Containers entsteht eine kurze Unterbrechung.

## Einmalige Einrichtung

1. **OCI.** Verwende eine Linux-VM mit systemd, Docker Engine 28 oder neuer,
   dem Compose-Plugin, Bash, curl und flock. Das Image unterstützt x86-64 und
   Ampere Arm64. Eine VM in einem privaten Subnetz kann das Internet über ein
   NAT-Gateway erreichen. Erlaube ausgehend DNS, HTTPS für Registries und
   Open-Meteo sowie UDP/TCP 7844 für Cloudflare. Lass eingehend 80, 443 und
   9080 geschlossen. Für die Einrichtung kannst du einen OCI-Bastion-Zugang
   verwenden und SSH auf dessen private Adresse beschränken. GitHub Actions
   braucht weder einen SSH-Schlüssel noch OCI-Zugangsdaten.
2. **GitHub.** Veröffentliche das erste Image über den Workflow
   *Publish production image* auf `main`. Das Paket
   `ghcr.io/moritzf/jax-rs-microprofile-servlet-demo` kann privat bleiben.
   Melde in diesem Fall Docker auf der VM mit einem GitHub-Token mit
   `read:packages` und Zugriff auf das Paket an. Verwende
   `sudo docker login ghcr.io --username <GitHub-Benutzer> --password-stdin`
   und übergib das Token über stdin. Der Timer läuft als root und nutzt
   dessen Docker-Konfiguration. Bei einem bewusst öffentlichen Paket
   entfällt die Anmeldung. Beschränke Änderungen an `main` und Schreibrechte
   auf das Paket auf vertrauenswürdige Personen und Workflows.
3. **Cloudflare.** Lege einen remote verwalteten Tunnel an und füge eine
   öffentliche Hostname-Route vom gewünschten Hostnamen auf
   `http://app:9080` hinzu. Der Name `app` wird im Compose-Netz aufgelöst.
   Die Compose-Datei aktiviert den Demo-Modus. Die App zeigt bestehende
   Trails und Meldungen an und lehnt GPX-Importe und neue Meldungen ab.
   Falls nur eingeladene Personen Zugriff erhalten sollen, richte vor der
   Freigabe eine Cloudflare-Access-Richtlinie für den Hostnamen ein.
4. **VM.** Übertrage diesen Ordner aus einem geprüften Checkout auf die VM.
   Führe im übertragenen Ordner aus:

   ```sh
   sudo install -d -o root -g root -m 0755 /opt/trailzustand
   sudo install -d -o 1001 -g 0 -m 0750 /opt/trailzustand/data
   sudo install -d -o root -g root -m 0700 /opt/trailzustand/secrets
   sudo install -o root -g root -m 0644 compose.yaml /opt/trailzustand/
   sudo install -o root -g root -m 0755 deploy.sh /opt/trailzustand/
   sudo install -o root -g root -m 0644 trailzustand-deploy.service trailzustand-deploy.timer /etc/systemd/system/
   ```

   Speichere das Connector-Token aus dem Cloudflare-Dashboard als einzige
   Zeile in `/opt/trailzustand/secrets/tunnel-token`, etwa mit
   `sudoedit /opt/trailzustand/secrets/tunnel-token`. Setze die Dateirechte
   und starte das Deployment:

   ```sh
   sudo chown root:65532 /opt/trailzustand/secrets/tunnel-token
   sudo chmod 0440 /opt/trailzustand/secrets/tunnel-token
   sudo systemctl daemon-reload
   sudo systemctl enable --now docker.service trailzustand-deploy.timer
   sudo systemctl start trailzustand-deploy.service
   ```

   Prüfe den ersten Start mit
   `sudo journalctl -u trailzustand-deploy.service -n 80`,
   `curl --fail http://127.0.0.1:9080/api/trails/kammspur/reports`
   und einem Aufruf des HTTPS-Hostnamens von außerhalb der VM.

## Betrieb

- Die H2-Datei liegt unter `/opt/trailzustand/data/trails.mv.db`. Stoppe
  vor einer Dateisicherung zuerst den Timer und den Deployment-Dienst und
  dann den App-Container. Verwende aus `/opt/trailzustand`
  `sudo env APP_IMAGE="$(sudo cat state/current)" docker compose stop app`.
  Starte nach der Sicherung Dienst und Timer erneut. Sichere die Daten auch
  außerhalb der VM.
- Ein abgelehnter Digest wird bis zum nächsten Image übersprungen. Für
  einen erneuten Versuch halte den Timer an, warte auf einen laufenden
  Deployment-Dienst, lösche `/opt/trailzustand/state/rejected` und starte
  Dienst und Timer erneut. Laufzeitfehler nach der Startprüfung erfordern
  eine neue Veröffentlichung, etwa einen Revert auf `main`.
- Für ein neues Tunnel-Token ersetze die Datei mit den oben angegebenen
  Rechten und erstelle den Connector neu. Verwende aus `/opt/trailzustand`
  `sudo env APP_IMAGE="$(sudo cat state/current)" docker compose up -d --no-deps --force-recreate cloudflared`.
  Aktualisiere die Versionsangaben der Basisimages und von cloudflared
  regelmäßig. Änderungen an Compose und systemd werden auf die VM übertragen.

[OCI Bastion](https://docs.oracle.com/en/solutions/use-bastion-service/index.html) ·
[GHCR-Pakete](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry) ·
[Cloudflare Tunnel](https://developers.cloudflare.com/tunnel/get-started/)
