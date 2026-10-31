"""Valida CSV locales de PipaLab sin subir datos. Python 3.11+, RAM acotada por flujo."""
from __future__ import annotations
import argparse
import csv
import gzip
import json
import math
import statistics
from pathlib import Path

IMU = ("acelerometro", "giroscopio")
STREAMS = (*IMU, "heart_rate", "ppg", "temperatura", "eda", "offbody", "bateria", "pantalla", "reloj", "marcadores", "huecos", "errores")


def read_rows(folder: Path, stream: str):
    files = sorted({*folder.glob(f"{stream}.csv"), *folder.glob(f"{stream}_*.csv"), *folder.glob(f"{stream}.csv.gz"), *folder.glob(f"{stream}_*.csv.gz")})
    for path in files:
        opener = gzip.open if path.suffix == ".gz" else open
        with opener(path, "rt", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            if not {"t_elapsed_ns", "t_wall_ms"}.issubset(reader.fieldnames or []):
                raise ValueError(f"Encabezado incompleto: {path.name}")
            for row in reader:
                if None in row or any(v is None for v in row.values()):
                    raise ValueError(f"Fila incompleta/sobrante: {path.name}:{reader.line_num}")
                try:
                    row["t_elapsed_ns"] = int(row["t_elapsed_ns"])
                    row["t_wall_ms"] = int(row["t_wall_ms"])
                except (TypeError, ValueError) as e:
                    raise ValueError(f"Timestamp inválido: {path.name}:{reader.line_num}") from e
                yield row


def state_coverage(rows, key, start, end):
    """Duración por estado. Antes del primer evento y estado -1 son desconocidos."""
    last = start
    state = -1
    durations = {-1: 0, 0: 0, 1: 0}
    for row in sorted(rows, key=lambda r: r["t_elapsed_ns"]):
        t = max(start, min(end, row["t_elapsed_ns"]))
        durations[state] = durations.get(state, 0) + max(0, t - last)
        try:
            value = int(float(row[key]))
            state = value if value in (0, 1) else -1
        except (ValueError, KeyError):
            state = -1
        last = t
    durations[state] = durations.get(state, 0) + max(0, end - last)
    total = end - start
    return {"yes_pct": 100 * durations[1] / total if total else None,
            "no_pct": 100 * durations[0] / total if total else None,
            "unknown_pct": 100 * durations[-1] / total if total else None}


def event_intervals(markers, end):
    opened, intervals = {}, []
    for r in sorted(markers, key=lambda r: r["t_elapsed_ns"]):
        name, phase, key = r["event"], r["phase"], r["event_id"]
        if name in ("SESSION", "SYNC"):
            continue
        if phase == "START":
            opened[key] = (name, r["t_elapsed_ns"])
        elif phase == "END" and key in opened:
            label, t = opened.pop(key)
            intervals.append((label, t, r["t_elapsed_ns"], True))
    intervals.extend((name, t, end, False) for name, t in opened.values())
    return intervals


def scan_stream(folder, name, start, end, rest_intervals):
    n = noninc = missing = gap_count = ibi_count = ibi_valid = ibi_rest = outside = 0
    first = last = None
    deltas = []
    points = []
    thinning = 1
    clock_bases = set()
    missing_channels = {"green": 0, "ir": 0, "red": 0} if name == "ppg" else {}
    for row in read_rows(folder, name):
        t = row["t_elapsed_ns"]
        if name not in ("bateria", "errores", "reloj") and not start <= t <= end:
            # Conservar todos los datos y sus estadísticas, pero impedir aceptación.
            outside += 1
        if first is None:
            first = t
        if last is not None:
            dt = t - last
            if dt <= 0:
                noninc += 1
            elif len(deltas) < 4096:
                deltas.append(dt)
            if name in IMU and dt > 30_000_000:
                gap_count += 1
                missing += max(0, (dt + 10_000_000) // 20_000_000 - 1)
        last = t if last is None else max(last, t)
        if name == "heart_rate":
            ibis = json.loads(row.get("ibi_ms_json") or "[]")
            statuses = json.loads(row.get("ibi_status_json") or "[]")
            if not isinstance(ibis, list) or not isinstance(statuses, list):
                raise ValueError("IBI debe ser una lista JSON")
            ibi_count += len(ibis)
            good = sum(1 for v, q in zip(ibis, statuses) if q == 0 and isinstance(v, (float, int)) and v > 0)
            ibi_valid += good
            # El lote IBI no tiene timestamp por latido. Solo contar como reposo si TODO el lote cabe en REPOSO.
            batch_seconds = int(row.get("batch_size") or 1)
            if any(a <= t and t + max(0, batch_seconds - 1) * 1_000_000_000 <= b for _, a, b, _ in rest_intervals):
                ibi_rest += good
        if row.get("clock_basis"):
            clock_bases.add(row["clock_basis"])
        for channel in missing_channels:
            if row.get(channel, "") == "":
                missing_channels[channel] += 1
        if n % thinning == 0:
            # Guardar hasta ~20k puntos, incluso en 8h. Deltas para Hz no usan datos diezmados.
            points.append(row)
            if len(points) > 20000:
                points = points[::2]
                thinning *= 2
        n += 1
    duration = max(0, end - start)
    hz = (n - 1) * 1e9 / (last - first) if n > 1 and last > first else None
    result = {"samples": n, "measured_hz": hz, "first_elapsed_ns": first, "last_elapsed_ns": last,
              "non_increasing_timestamps": noninc, "clock_bases": sorted(clock_bases), "out_of_session_samples": outside}
    if "receipt_only_unknown" in clock_bases:
        result["measured_hz"] = None
        result["timestamp_warning"] = "Base Samsung desconocida: no calcular frecuencia del sensor con tiempos de recepción."
    if name in IMU:
        leading = max(0, ((first if first is not None else end) - start) // 20_000_000)
        trailing = max(0, (end - last) // 20_000_000) if last is not None else 0
        expected = duration / 20_000_000
        estimated = missing + leading + trailing
        result.update({"requested_hz": 50, "median_hz_first_4096": 1e9 / statistics.median(deltas) if deltas else None,
                       "internal_gap_count": gap_count, "estimated_internal_missing": missing,
                       "estimated_leading_missing": leading, "estimated_trailing_missing": trailing,
                       "estimated_loss_pct": 100 * estimated / (n + estimated) if n + estimated else 100,
                       "nominal_deficit_pct": max(0, 100 * (expected - n) / expected) if expected else None,
                       "note": "Estimación a 50 Hz; no es contador hardware de muestras perdidas."})
    if name == "heart_rate":
        result.update(ibi_values=ibi_count, ibi_quality_zero=ibi_valid, ibi_quality_zero_in_rest_batches=ibi_rest)
    if missing_channels:
        result["missing_channel_values"] = missing_channels
    return result, points


def analyze(folder: Path, expected_minutes=60):
    meta = json.loads((folder / "meta.json").read_text(encoding="utf-8-sig"))
    start = int(meta["start_elapsed_ns"])
    end = int(meta.get("end_elapsed_ns", meta.get("last_checkpoint_elapsed_ns", start)))
    if end <= start:
        raise ValueError("Intervalo de sesión inválido o vacío")
    report = {"session": folder.name, "duration_minutes": (end - start) / 60e9, "finalized": bool(meta.get("finalized")),
              "streams": {}, "errors": [], "expected_minutes": expected_minutes}
    def small(name):
        try:
            return list(read_rows(folder, name))
        except (ValueError, OSError) as e:
            report["errors"].append(str(e))
            return []
    markers = small("marcadores")
    intervals = event_intervals(markers, end)
    rest = [x for x in intervals if x[0] == "REPOSO" and x[3]]
    plotted = {}
    for stream in STREAMS:
        try:
            result, points = scan_stream(folder, stream, start, end, rest)
            report["streams"][stream] = result
            if stream in (*IMU, "heart_rate"):
                plotted[stream] = points
        except (ValueError, OSError, json.JSONDecodeError) as e:
            report["errors"].append(str(e))
    report["worn"] = state_coverage(small("offbody"), "worn", start, end)
    report["screen"] = state_coverage(small("pantalla"), "interactive", start, end)
    battery = small("bateria")
    valid_battery = [r for r in battery if int(r["percent"]) >= 0]
    charging = any(r.get("charging") == "1" for r in battery)
    report["battery"] = {"charging_seen": charging, "percentage_points_per_hour": None}
    if len(valid_battery) >= 2:
        a, b = valid_battery[0], valid_battery[-1]
        hours = (b["t_elapsed_ns"] - a["t_elapsed_ns"]) / 3.6e12
        report["battery"].update(start_pct=int(a["percent"]), end_pct=int(b["percent"]), measured_hours=hours)
        report["battery"]["short_window_estimate"] = hours < 1
        if hours > 0 and not charging:
            report["battery"]["percentage_points_per_hour"] = (int(a["percent"]) - int(b["percent"])) / hours
    errors = small("errores")
    report["device_errors"] = errors
    tracker_streams = {"HEART_RATE_CONTINUOUS": "heart_rate", "PPG_CONTINUOUS": "ppg",
                       "SKIN_TEMPERATURE_CONTINUOUS": "temperatura", "EDA_CONTINUOUS": "eda"}
    report["missing_started_samsung_streams"] = [tracker_streams[t] for t in meta.get("samsung_started_trackers", [])
        if t in tracker_streams and not report["streams"].get(tracker_streams[t], {}).get("samples")]
    checks = {
        "duration": (end - start) >= expected_minutes * 60e9,
        "closed_normally": bool(meta.get("finalized")) and meta.get("status") == "completed",
        "flush_complete": meta.get("flush_completed") is True,
        "csv_readable": not report["errors"],
        "timestamps_within_session": all(s.get("out_of_session_samples", 0) == 0 for s in report["streams"].values()),
        "screen_off_at_least_99pct": (report["screen"]["no_pct"] or 0) >= 99,
        "battery_measured_without_charging": report["battery"]["percentage_points_per_hour"] is not None,
        "ibi_in_labeled_rest": report["streams"].get("heart_rate", {}).get("ibi_quality_zero_in_rest_batches", 0) > 0,
        "samsung_clock_identified": bool(report["streams"].get("heart_rate", {}).get("clock_bases")) and "receipt_only_unknown" not in report["streams"].get("heart_rate", {}).get("clock_bases", []),
        "no_device_errors": not errors,
        "started_samsung_streams_have_data": not report["missing_started_samsung_streams"],
    }
    for stream in IMU:
        s = report["streams"].get(stream, {})
        checks[f"{stream}_loss_below_1pct"] = bool(s.get("samples")) and s.get("estimated_loss_pct", 100) < 1 and s.get("nominal_deficit_pct", 100) < 1 and s.get("non_increasing_timestamps", 1) == 0
    report["acceptance"] = {"passed": all(checks.values()), "checks": checks,
                            "note": "Los timestamps Samsung siguen siendo inferidos; confirmar su base con datos reales. IBI de lotes no equivale a tiempos por latido."}
    return report, plotted, intervals, markers, start, end


def plot(out: Path, report, points, intervals, markers, start, end):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(3, 1, figsize=(15, 9), sharex=True)
    colors = plt.get_cmap("tab10")
    for ax, name in zip(axes, (*IMU, "heart_rate")):
        rows = points.get(name, [])
        x = [(r["t_elapsed_ns"] - start) / 60e9 for r in rows]
        keys = ("x_m_s2", "y_m_s2", "z_m_s2") if name == "acelerometro" else ("x_rad_s", "y_rad_s", "z_rad_s") if name == "giroscopio" else ("bpm",)
        for key in keys:
            y = [float(r[key]) if r.get(key) and (name != "heart_rate" or r.get("status") == "1") else math.nan for r in rows]
            ax.plot(x, y, linewidth=.6, label=key)
        for i, (label, a, b, closed) in enumerate(intervals):
            ax.axvspan((a-start)/60e9, (b-start)/60e9, alpha=.13, color=colors(i % 10))
            if ax is axes[0]:
                ax.text((a-start)/60e9, 1.02, label + ("*" if not closed else ""), transform=ax.get_xaxis_transform(), fontsize=7, rotation=30)
        for r in markers:
            if r["event"] == "SYNC":
                ax.axvline((r["t_elapsed_ns"]-start)/60e9, color="black", linestyle=":", linewidth=.7)
        ax.set_ylabel({"acelerometro": "Aceleración (m/s²)", "giroscopio": "Giro (rad/s)", "heart_rate": "FC válida (lpm)"}[name])
        ax.grid(alpha=.2)
        if rows: ax.legend(loc="upper right", fontsize=8)
        else: ax.text(.5, .5, "SIN DATOS", transform=ax.transAxes, ha="center")
    axes[-1].set_xlabel("Minutos desde inicio de sesión · líneas punteadas: SYNC")
    axes[-1].set_xlim(0, (end-start)/60e9)
    fig.suptitle("PipaLab · " + report["session"] + " · vista reducida; cálculo con todas las muestras", fontsize=12)
    fig.tight_layout()
    fig.savefig(out / "senales.png", dpi=160)
    plt.close(fig)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("session", type=Path)
    p.add_argument("--out", type=Path)
    p.add_argument("--expected-minutes", type=float, default=60)
    p.add_argument("--no-plot", action="store_true")
    p.add_argument("--require-pass", action="store_true", help="Salir con código 2 si no pasa la aceptación")
    a = p.parse_args()
    if a.expected_minutes <= 0: p.error("La duración esperada debe ser positiva")
    out = a.out or a.session / "validacion"
    out.mkdir(parents=True, exist_ok=True)
    data = analyze(a.session, a.expected_minutes)
    report = data[0]
    (out / "informe.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf8")
    if not a.no_plot:
        plot(out, *data)
    print(json.dumps({"informe": str(out / "informe.json"), "duracion_min": report["duration_minutes"],
                      "aceptacion": report["acceptance"], "bateria": report["battery"]}, indent=2, ensure_ascii=False))
    return 2 if a.require_pass and not report["acceptance"]["passed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
