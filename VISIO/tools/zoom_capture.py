"""Visio: local, explicitly started reference recordings from a Zoom USB input.

Python 3.11+ with Tk; FFmpeg on PATH. No third-party Python packages or network.
Recordings are unreviewed human-labelled examples, never classifier output.
"""
from __future__ import annotations
import array
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import wave
import tkinter as tk
from tkinter import ttk, messagebox

DEVICE = "Microphone (H Series Stereo Track Usb Audio)"
TYPES = {"Ambiente / silencio": "background", "Encendedor": "lighter", "Chasquidos": "finger_snap",
         "Palmadas": "clap", "Llaves / objetos": "objects", "Voz": "speech", "Otra actividad": "other"}
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def save_json(path: Path, data: dict):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def inspect_wave(path: Path) -> dict:
    with wave.open(str(path), "rb") as audio:
        channels, width, rate, frames = audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()
        if width != 2 or channels != 1 or rate != 48000 or frames == 0:
            raise ValueError("WAV vacío o formato inesperado; conservar y revisar")
        peak = clipped = total = 0
        while block := audio.readframes(48000):
            samples = array.array("h", block)
            if sys.byteorder != "little": samples.byteswap()
            peak = max(peak, max(abs(x) for x in samples))
            clipped += sum(abs(x) >= 32760 for x in samples)
            total += len(samples)
    if total != frames:
        raise ValueError("El WAV está truncado")
    with path.open("rb") as source: digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"sample_rate_hz": rate, "channels": channels, "encoding": "PCM_S16LE",
            "frames": frames, "duration_seconds": frames / rate, "peak_normalized": peak / 32768,
            "clipped_samples": clipped, "sha256": digest}


class Recorder:
    def __init__(self, root: tk.Tk):
        self.root = root
        root.title("Visio · Nemy | Captura Zoom")
        root.geometry("820x900")
        root.minsize(720, 850)
        root.configure(bg="#0b100f")
        self.out = Path.home() / "Downloads" / "Visio-Captura-Zoom" / "capturas"
        self.out.mkdir(parents=True, exist_ok=True)
        self.ffmpeg = shutil.which("ffmpeg")
        self.process = None
        self.log = None
        self.directory = None
        self.meta = {}
        self.counting = False
        self.stop_deadline = None
        self.last_saved = None
        self.device = tk.StringVar(value=DEVICE)
        self.category = tk.StringVar(value="Ambiente / silencio")
        self.duration = tk.StringVar(value="20")
        self.status = tk.StringVar(value="LISTO · El micrófono está apagado")
        self.counter = tk.StringVar(value="00:00")
        self.devices = []
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#0b100f")
        style.configure("TLabel", background="#0b100f", foreground="#e7eee9", font=("Segoe UI", 11))
        style.configure("TButton", background="#233329", foreground="#d5ffb0", padding=10, font=("Segoe UI", 11))
        style.configure("TCombobox", padding=6)
        body = ttk.Frame(root, padding=24)
        body.pack(fill="both", expand=True)
        ttk.Label(body, text="Visio / NEMY", font=("Segoe UI", 24, "bold"), foreground="#bcf369").pack(anchor="w")
        ttk.Label(body, text="Laboratorio de sonidos · archivos originales + etiquetas humanas").pack(anchor="w", pady=(4, 18))
        ttk.Label(body, text="Entrada USB (comprueba que sea la Zoom)").pack(anchor="w")
        row = ttk.Frame(body); row.pack(fill="x", pady=5)
        self.device_box = ttk.Combobox(row, textvariable=self.device, state="readonly")
        self.device_box.pack(side="left", fill="x", expand=True)
        self.refresh_button = ttk.Button(row, text="Detectar", command=self.refresh)
        self.refresh_button.pack(side="left", padx=(8, 0))
        row = ttk.Frame(body); row.pack(fill="x", pady=10)
        ttk.Label(row, text="Tipo").pack(side="left", padx=(0, 8))
        self.type_box = ttk.Combobox(row, textvariable=self.category, values=list(TYPES), state="readonly", width=27)
        self.type_box.pack(side="left")
        ttk.Label(row, text="Duración").pack(side="left", padx=10)
        self.duration_box = ttk.Combobox(row, textvariable=self.duration, values=["10", "20", "30", "60", "120"], state="readonly", width=6)
        self.duration_box.pack(side="left")
        ttk.Label(row, text="s").pack(side="left", padx=4)
        ttk.Label(body, text="Describe distancia, objeto, ambiente y lo que harás").pack(anchor="w", pady=(5, 5))
        self.description = tk.Text(body, height=4, wrap="word", bg="#18221e", fg="#e7eee9", insertbackground="#bcf369", font=("Segoe UI", 11), relief="flat", padx=10, pady=8)
        self.description.pack(fill="x")
        self.description.insert("1.0", "Zoom sobre la mesa; indicar distancia aproximada y número de repeticiones.")
        ttk.Label(body, textvariable=self.counter, font=("Consolas", 46), foreground="#bcf369").pack(pady=(14, 0))
        ttk.Label(body, textvariable=self.status, wraplength=740).pack(pady=5)
        row = ttk.Frame(body); row.pack(fill="x", pady=10)
        self.start_button = ttk.Button(row, text="● Grabar sesión", command=self.prepare)
        self.start_button.pack(side="left", fill="x", expand=True)
        self.stop_button = ttk.Button(row, text="■ Detener y guardar", command=self.stop, state="disabled")
        self.stop_button.pack(side="left", fill="x", expand=True, padx=(8, 0))
        row = ttk.Frame(body); row.pack(fill="x", pady=4)
        self.marker_button = ttk.Button(row, text="+ Marcar instante / Espacio", command=self.marker, state="disabled")
        self.marker_button.pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Abrir archivos", command=lambda: os.startfile(self.out)).pack(side="left", padx=(8, 0))
        row = ttk.Frame(body); row.pack(fill="x", pady=8)
        self.accept_button = ttk.Button(row, text="✓ Revisé: ejemplo válido", command=lambda: self.review("accepted"), state="disabled")
        self.accept_button.pack(side="left", fill="x", expand=True)
        self.exclude_button = ttk.Button(row, text="Excluir última grabación", command=lambda: self.review("excluded"), state="disabled")
        self.exclude_button.pack(side="left", fill="x", expand=True, padx=(8, 0))
        ttk.Label(body, text="Empieza con ambiente. Después graba por separado encendedor, chasquidos y sonidos que puedan confundirse. Deja 2–3 s entre repeticiones. Cambia distancias y ambientes. Para alinear con el reloj, inicia allí una sesión con audio y crea una referencia visible en ambas grabaciones.", wraplength=740, foreground="#a4b2a8").pack(anchor="w", pady=10)
        ttk.Label(body, text="Nada se graba hasta pulsar Grabar. La cuenta regresiva es visual y no produce sonidos. Las marcas de teclado incluyen latencia humana; no son etiquetas exactas de cada sonido. Los ejemplos de Zoom necesitan validación también con el micrófono del reloj.", wraplength=740, foreground="#a4b2a8", font=("Segoe UI", 9)).pack(anchor="w")
        root.bind("<space>", self.space)
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(200, self.refresh)
        root.after(100, self.poll)

    def refresh(self):
        if self.process or self.counting: return
        if not self.ffmpeg:
            self.status.set("Falta FFmpeg en PATH. Instálalo y vuelve a abrir."); return
        try:
            result = subprocess.run([self.ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"], capture_output=True, timeout=12, creationflags=FLAGS)
            text = result.stderr.decode("utf-8", "replace")
            self.devices = re.findall(r'"([^"\r\n]+)" \(audio\)', text)
            self.device_box["values"] = self.devices
            if DEVICE in self.devices: self.device.set(DEVICE)
            elif self.device.get() not in self.devices: self.device.set("")
            self.status.set("Zoom detectada · micrófono apagado" if DEVICE in self.devices else "Selecciona una entrada disponible; no se cambió a otro micrófono automáticamente.")
        except Exception as exc: self.status.set(str(exc))

    def lock(self, value):
        self.start_button["state"] = "disabled" if value else "normal"
        self.refresh_button["state"] = "disabled" if value else "normal"
        for box in (self.device_box, self.type_box, self.duration_box): box["state"] = "disabled" if value else "readonly"
        self.description["state"] = "disabled" if value else "normal"
        self.stop_button["state"] = "normal" if value else "disabled"
        self.marker_button["state"] = "normal" if self.process and value else "disabled"
        self.accept_button["state"] = self.exclude_button["state"] = "disabled" if value or not self.last_saved else "normal"

    def prepare(self):
        if not self.ffmpeg or self.device.get() not in self.devices:
            messagebox.showerror("Entrada no disponible", "Detecta y selecciona la Zoom antes de grabar."); return
        self.counting = True; self.lock(True)
        self.countdown(3)

    def countdown(self, n):
        if not self.counting: return
        if n == 0: self.start(); return
        self.counter.set(str(n)); self.status.set("Preparación · el micrófono sigue apagado")
        self.root.after(1000, lambda: self.countdown(n - 1))

    def start(self):
        self.counting = False
        ident = dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
        self.directory = self.out / ident; self.directory.mkdir()
        self.meta = {"schema": "visio-zoom-capture-v1", "id": ident, "type_id": TYPES[self.category.get()], "activity": self.category.get(),
                     "description": self.description.get("1.0", "end").strip(), "device": self.device.get(), "requested_seconds": int(self.duration.get()),
                     "status": "recording", "review": "unreviewed", "label_source": "human_session_description",
                     "start_process_wall_ms": time.time_ns() // 1000000, "start_process_monotonic_ns": time.monotonic_ns(),
                     "clock_policy": "PC process launch clock, not hardware acquisition. Audio begins after device initialization; align reference sounds offline with watch.",
                     "processing": "No denoise, AGC or normalization requested by Visio; FFmpeg outputs mono PCM16 at 48 kHz. Device/driver processing may apply.", "markers": [], "reviews": []}
        save_json(self.directory / "session.json", self.meta)
        try:
            self.log = (self.directory / "capture.log").open("wb")
            self.process = subprocess.Popen([self.ffmpeg, "-hide_banner", "-loglevel", "info", "-n", "-f", "dshow", "-i", "audio=" + self.device.get(),
                "-t", self.duration.get(), "-vn", "-ac", "1", "-ar", "48000", "-c:a", "pcm_s16le", str(self.directory / "audio.wav")],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=self.log, creationflags=FLAGS)
            self.lock(True); self.status.set("● GRABANDO / inicializando Zoom · no cierres esta ventana")
        except Exception as exc:
            self.meta.update(status="failed", error=str(exc)); save_json(self.directory / "session.json", self.meta)
            if self.log: self.log.close(); self.log = None
            self.lock(False); self.status.set("No se pudo iniciar: " + str(exc))

    def space(self, event):
        if self.process and self.root.focus_get() is not self.description:
            self.marker(); return "break"

    def marker(self):
        if not self.process: return
        self.meta["markers"].append({"wall_ms": time.time_ns() // 1000000, "offset_from_process_start_ns": time.monotonic_ns() - self.meta["start_process_monotonic_ns"], "label": "manual_reference", "source": "PC_button"})
        save_json(self.directory / "session.json", self.meta)
        self.status.set(f"● GRABANDO · {len(self.meta['markers'])} marcas manuales")

    def stop(self):
        if self.counting:
            self.counting = False; self.lock(False); self.counter.set("00:00"); self.status.set("Cancelado · no se grabó audio"); return
        if self.process and self.stop_deadline is None:
            try: self.process.stdin.write(b"q\n"); self.process.stdin.flush()
            except (OSError, BrokenPipeError): pass
            self.stop_deadline = time.monotonic() + 8
            self.status.set("Cerrando WAV y guardando metadatos…")

    def poll(self):
        if self.process:
            elapsed = (time.monotonic_ns() - self.meta["start_process_monotonic_ns"]) / 1e9
            self.counter.set(f"{int(elapsed)//60:02d}:{int(elapsed)%60:02d}")
            if self.stop_deadline and time.monotonic() > self.stop_deadline:
                self.meta["forced_stop"] = True; self.process.kill(); self.stop_deadline = None
            code = self.process.poll()
            if code is not None:
                self.process.stdin.close(); self.process = None
                self.log.close(); self.log = None; self.stop_deadline = None
                self.meta.update(stop_process_wall_ms=time.time_ns() // 1000000, ffmpeg_exit_code=code)
                try:
                    self.meta["audio"] = inspect_wave(self.directory / "audio.wav")
                    self.meta["status"] = "completed" if code == 0 and not self.meta.get("forced_stop") else "interrupted"
                    self.last_saved = self.directory
                    duration = self.meta["audio"]["duration_seconds"]
                    self.counter.set(f"{duration:.1f} s")
                    clipped = self.meta['audio']['clipped_samples']
                    self.status.set(f"Guardado · saturación en {clipped} muestras: baja ganancia o aumenta distancia" if clipped else f"Guardado · {self.meta['status']} · revisa el audio antes de aceptarlo")
                except Exception as exc:
                    self.meta.update(status="failed", error=str(exc)); self.last_saved = None
                    self.status.set("Captura inválida. Se conservó el archivo y capture.log para revisión.")
                save_json(self.directory / "session.json", self.meta); self.lock(False)
        self.root.after(100, self.poll)

    def review(self, decision):
        if not self.last_saved or self.process: return
        path = self.last_saved / "session.json"
        meta = json.loads(path.read_text(encoding="utf-8"))
        if decision == "accepted" and meta["status"] != "completed":
            messagebox.showinfo("Revisar interrupción", "Esta captura no terminó correctamente. Consérvala para investigar o exclúyela."); return
        meta["review"] = decision
        meta["reviews"].append({"wall_ms": time.time_ns() // 1000000, "decision": decision})
        save_json(path, meta); self.status.set("Revisión guardada: " + decision)

    def close(self):
        if self.process or self.counting:
            self.stop(); self.status.set("Terminando la captura. Cierra de nuevo cuando se haya guardado.")
        else: self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk(); app = Recorder(root); root.mainloop()
