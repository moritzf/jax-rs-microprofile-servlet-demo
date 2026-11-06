const byId = (id) => document.getElementById(id);
const colors = { Leicht: "#207c4b", Mittel: "#155dae", Schwer: "#ae3d30" };
const conditions = { dry: "Trocken", damp: "Feucht", muddy: "Matschig", obstacle: "Hindernis" };
const region = [[47.693, 11.721], [47.741, 11.803]];
const hourFormat = new Intl.DateTimeFormat("de-DE", {
    hour: "2-digit", minute: "2-digit", timeZone: "Europe/Berlin"
});
const reportFormat = new Intl.DateTimeFormat("de-DE", {
    dateStyle: "medium", timeStyle: "short", timeZone: "Europe/Berlin"
});

let selectedId;
let mapRequest;
let detailRequest;
let reportsRequest;
let storedReports = [];
const demoReports = new Map();
const pendingReports = new Set();

async function request(path, options = {}) {
    const response = await fetch(`api/trails${path}`, options);
    if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.message ?? "Anfrage fehlgeschlagen.");
    }
    return response.json();
}

async function loadSettings() {
    try {
        const { demoMode } = await request("/settings", { cache: "no-store" });
        byId("report-demo-hint").hidden = demoMode !== true;
        byId("import-demo-hint").hidden = demoMode !== true;
        byId("report-form").hidden = typeof demoMode !== "boolean";
        const importPanel = byId("import-panel");
        byId("import-fields").disabled = demoMode !== false;
        importPanel.hidden = typeof demoMode !== "boolean";
    } catch {
        byId("settings-error").hidden = false;
    }
}

function trailStyle(feature) {
    return {
        color: colors[feature.properties.difficulty],
        weight: feature.id === selectedId ? 8 : 5,
        opacity: 0.95
    };
}

function renderSelection(trailsLayer) {
    trailsLayer.setStyle(trailStyle);
    for (const button of byId("trails").querySelectorAll("button")) {
        const selected = button.dataset.trail === selectedId;
        button.setAttribute("aria-pressed", String(selected));
        button.classList.toggle("list-group-item-success", selected);
    }
}

function showReport(report) {
    const item = document.createElement("li");
    item.className = "list-group-item";
    const summary = document.createElement("div");
    summary.className = "d-flex align-items-baseline justify-content-between gap-2";
    const heading = document.createElement("strong");
    heading.textContent = conditions[report.condition];
    const timestamp = document.createElement("time");
    timestamp.className = "small text-body-secondary";
    timestamp.dateTime = report.reportedAt;
    timestamp.textContent = reportFormat.format(new Date(report.reportedAt));
    summary.append(heading, timestamp);
    item.append(summary);
    if (report.note) {
        const note = document.createElement("p");
        note.className = "report-note small mt-1 mb-0";
        note.textContent = report.note;
        item.append(note);
    }
    return item;
}

function renderReports() {
    const reports = [...(demoReports.get(selectedId) ?? []), ...storedReports].slice(0, 20);
    byId("reports").replaceChildren(...reports.map(showReport));
    byId("report-history").hidden = reports.length === 0;
    byId("reports-export").hidden = storedReports.length === 0;
}

async function loadReports(id) {
    reportsRequest?.abort();
    reportsRequest = new AbortController();
    const { signal } = reportsRequest;
    byId("reports-status").textContent = "Meldungen werden geladen …";
    try {
        const reports = await request(`/${id}/reports`, { signal });
        if (signal.aborted) return;
        storedReports = reports;
        renderReports();
        byId("reports-status").textContent = "";
    } catch {
        if (!signal.aborted) byId("reports-status").textContent = "Meldungen konnten nicht geladen werden.";
    }
}

async function loadForecast(id, signal) {
    byId("forecast-status").textContent = "Vorhersage wird geladen …";
    try {
        const forecast = await request(`/${id}/forecast`, { signal });
        if (signal.aborted) return;
        for (const hour of forecast.hours) {
            const item = document.createElement("li");
            item.className = "col";
            const tile = document.createElement("div");
            tile.className = "bg-info-subtle rounded text-center p-2";
            const time = document.createElement("time");
            time.className = "d-block small text-body-secondary";
            time.dateTime = hour.time;
            time.textContent = hourFormat.format(new Date(hour.time));
            const probability = document.createElement("strong");
            probability.textContent = `${hour.rainProbability} %`;
            tile.append(time, probability);
            item.append(tile);
            byId("forecast").append(item);
        }
        byId("forecast-status").textContent = "";
    } catch {
        if (!signal.aborted) byId("forecast-status").textContent = "Wetter vorübergehend nicht verfügbar.";
    }
}

function selectTrail(feature, trailsLayer, map) {
    selectedId = feature.id;
    detailRequest?.abort();
    detailRequest = new AbortController();
    renderSelection(trailsLayer);

    byId("trail-name").textContent = feature.properties.name;
    byId("trail-description").textContent = feature.properties.description;
    byId("forecast").replaceChildren();
    byId("reports-export").href = `exports/reports.csv?trail=${encodeURIComponent(selectedId)}`;
    storedReports = [];
    renderReports();
    byId("report-form").reset();
    byId("form-status").textContent = "";
    byId("submit-report").disabled = pendingReports.has(selectedId);
    byId("details").hidden = false;
    map.fitBounds(L.geoJSON(feature).getBounds(), { padding: [20, 20], maxZoom: 16, animate: false });

    void loadReports(selectedId);
    void loadForecast(selectedId, detailRequest.signal);
}

byId("report-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const id = selectedId;
    if (pendingReports.has(id)) return;

    const view = detailRequest;
    const form = event.currentTarget;
    pendingReports.add(id);
    byId("submit-report").disabled = true;
    byId("form-status").textContent = "Meldung wird gespeichert …";
    try {
        const report = await request(`/${id}/reports`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(Object.fromEntries(new FormData(form)))
        });
        const isDemoReport = report.persisted === false;
        if (isDemoReport) {
            demoReports.set(id, [report, ...(demoReports.get(id) ?? [])].slice(0, 20));
        }
        if (view === detailRequest) {
            form.reset();
            byId("form-status").textContent = isDemoReport
                ? "Meldung für diese Seite hinzugefügt."
                : "Meldung gespeichert.";
        }
        if (id === selectedId) {
            if (isDemoReport) renderReports();
            else void loadReports(id);
        }
    } catch (error) {
        if (view === detailRequest) byId("form-status").textContent = error.message;
    } finally {
        pendingReports.delete(id);
        if (id === selectedId) byId("submit-report").disabled = false;
    }
});

function startMap(container) {
    const map = L.map(container, { minZoom: 3, maxZoom: 19, trackResize: false }).fitBounds(region);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
    }).addTo(map);
    const trailsLayer = L.geoJSON(null, { style: trailStyle, interactive: false }).addTo(map);
    const trailHitboxes = L.geoJSON(null, {
        style: { className: "trail-hitbox", weight: 32, opacity: 0 },
        onEachFeature(feature, layer) {
            layer.on("click", () => selectTrail(feature, trailsLayer, map));
        }
    }).addTo(map);

    async function updateMap() {
        mapRequest?.abort();
        mapRequest = new AbortController();
        const { signal } = mapRequest;
        const bounds = map.getBounds();
        const bbox = [
            Math.max(-180, Math.min(bounds.getWest(), 179.999)),
            Math.max(-90, bounds.getSouth()),
            Math.min(180, Math.max(bounds.getEast(), -179.999)),
            Math.min(90, bounds.getNorth())
        ];
        try {
            const trails = await request(`?bbox=${bbox.join(",")}`, { signal });
            if (signal.aborted) return;
            trailsLayer.clearLayers();
            trailsLayer.addData(trails);
            trailHitboxes.clearLayers();
            trailHitboxes.addData(trails);
            const items = trails.features.map((feature) => {
                const button = document.createElement("button");
                button.className = "list-group-item list-group-item-action d-flex justify-content-between align-items-center gap-2";
                button.type = "button";
                button.dataset.trail = feature.id;
                button.textContent = feature.properties.name;
                const difficulty = document.createElement("span");
                difficulty.className = "small fw-semibold";
                difficulty.style.color = colors[feature.properties.difficulty];
                difficulty.textContent = feature.properties.difficulty;
                button.append(difficulty);
                button.addEventListener("click", () => selectTrail(feature, trailsLayer, map));
                return button;
            });
            byId("trails").replaceChildren(...items);
            renderSelection(trailsLayer);
            byId("map-status").textContent = items.length ? "" : "In diesem Ausschnitt liegen keine Trails.";
        } catch {
            if (!signal.aborted) byId("map-status").textContent = "Trails konnten nicht geladen werden.";
        }
    }

    map.on("moveend", updateMap);
    byId("reset-map").addEventListener("click", () => map.fitBounds(region));
    byId("import-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const form = event.currentTarget;
        const submit = byId("submit-import");
        if (byId("import-fields").disabled || submit.disabled) return;
        const fields = new FormData(form);
        const file = fields.get("gpx");
        const status = byId("import-status");
        if (file.size > 2 * 1024 * 1024) {
            status.textContent = "Die GPX-Datei darf höchstens 2 MiB groß sein.";
            return;
        }
        const params = new URLSearchParams({ name: fields.get("name"), difficulty: fields.get("difficulty") });
        submit.disabled = true;
        status.textContent = "Trail wird importiert …";
        try {
            const trail = await request(`?${params}`, {
                method: "POST",
                headers: { "Content-Type": "application/gpx+xml" },
                body: file
            });
            selectTrail(trail, trailsLayer, map);
            form.reset();
            status.textContent = "Trail importiert.";
        } catch (error) {
            status.textContent = error.message;
        } finally {
            submit.disabled = false;
        }
    });
    void updateMap();
    return map;
}

void loadSettings();
if (typeof L === "undefined") {
    byId("map-status").textContent = "Die Kartenbibliothek konnte nicht geladen werden.";
} else {
    const container = byId("map");
    let map;
    // Fit the initial region after layout has sized the map panel.
    new ResizeObserver(([{ contentRect }]) => {
        if (!contentRect.width || !contentRect.height) return;
        if (map) {
            map.invalidateSize({ debounceMoveend: true });
        } else {
            map = startMap(container);
        }
    }).observe(container);
}
