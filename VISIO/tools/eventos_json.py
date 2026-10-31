"""Convierte una exportación JSON de Visio (visio-consumos-crudos-v1) en CSV por evento para ReplayDetector.

Solo lee el archivo local; no sube datos. Python 3.11+, sin dependencias.
"""
from __future__ import annotations
import argparse
import csv
import json
import statistics
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Separa el acelerómetro de cada evento revisado en CSV.")
    parser.add_argument("json", type=Path, help="Exportación Visio-*.json")
    parser.add_argument("salida", type=Path, help="Carpeta destino (fuera de Git, p. ej. datos/)")
    args = parser.parse_args()
    data = json.loads(args.json.read_text(encoding="utf-8"))
    if data.get("formato") != "visio-consumos-crudos-v1":
        raise SystemExit("Formato no reconocido: se esperaba visio-consumos-crudos-v1")
    samples: dict[str, dict[int, tuple[int, str, str, str]]] = {}
    offsets = []
    for row in data["muestras_crudas"]:
        if row["flujo"] != "acelerometro":
            continue
        v = row["valores_originales"]
        t = int(v["t_elapsed_ns"])
        offsets.append(int(v["t_wall_ms"]) * 1_000_000 - t)
        for event in row["eventos"]:
            samples.setdefault(event, {})[t] = (t, v["x_m_s2"], v["y_m_s2"], v["z_m_s2"])
    if not offsets:
        raise SystemExit("La exportación no contiene acelerómetro.")
    # Reloj de pared del reloj menos su reloj monotónico: convierte la marca manual a t_elapsed_ns.
    offset = statistics.median(offsets)
    args.salida.mkdir(parents=True, exist_ok=True)
    written = 0
    with open(args.salida / "labels.csv", "w", newline="", encoding="utf-8") as f:
        labels = csv.writer(f)
        labels.writerow(["event", "positive", "kind", "origin", "simulation", "mark_elapsed_ns", "start_elapsed_ns", "end_elapsed_ns"])
        for e in data["eventos"]:
            rows = samples.get(e["id"])
            if not e.get("tiene_senal_cruda") or not rows:
                continue
            automatic = e["origin"] == "automatic"
            features = e.get("features") or {}
            labels.writerow([e["id"], int(e["categoria"] == "consumo_confirmado"), e["kind"], e["origin"], int(bool(e.get("es_simulacion"))),
                             round(e["inicio_reloj_estimado_ms"] * 1_000_000 - offset),
                             features.get("start_elapsed_ns", -1) if automatic else -1, features.get("end_elapsed_ns", -1) if automatic else -1])
            with open(args.salida / f"{e['id']}.csv", "w", newline="", encoding="utf-8") as g:
                out = csv.writer(g)
                out.writerow(["t_elapsed_ns", "x_m_s2", "y_m_s2", "z_m_s2"])
                out.writerows(rows[t] for t in sorted(rows))
            written += 1
    print(f"{written} eventos con acelerómetro en {args.salida}")


if __name__ == "__main__":
    main()
