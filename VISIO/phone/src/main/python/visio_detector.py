#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VISIO v1 — Implementación de referencia (fuente de verdad numérica)
===================================================================

Detector jerárquico de EPISODIOS de gestos mano-rostro con manipulación de
objeto (patrón de dos manos: pipa + encendedor) a partir de un reloj
inteligente (IMU de 6 ejes, FC/IBI, eventos del SO; audio opcional).

    L0  Ingesta y remuestreo a rejilla uniforme (faltantes = NaN + máscara)
    L1  Calidad de señal (saturación, validez de FC/IBI)
    L2  Orientación: vector gravedad (filtro complementario) -> marco anatómico
    L3  Segmentación: máquina de estados ELEVAR -> PERMANENCIA -> BAJAR
    L4  Features por segmento (cinemática, giro, microdinámica, impulsos, DTW)
    L5  Clasificador de gesto calibrado + compuerta de novedad
    L6  Episodio: CUSUM con olvido sobre razones de log-verosimilitud
    L7  Corroboración fisiológica lenta (APAGADA hasta validarse)
    L8  Política de decisión con restricciones duras

Uso
    python visio_detector.py --selftest               prueba sintética end-to-end
    python visio_detector.py --golden ./golden        vectores dorados de paridad
    python visio_detector.py --dump-config            parámetros por defecto (JSON)
    python visio_detector.py --visio Visio-Entrenamiento.zip --out ./salida   exportación VISIO completa
    python visio_detector.py --acc acc.csv [--gyr gyr.csv] --wrist L --out ./salida
                                                       candidatos de una sesión real

Reglas para portar (Kotlin / Wear OS / Android)
    * Doble precisión en todo el núcleo.
    * Todos los filtros son CAUSALES y se definen con las fórmulas de este
      archivo. Prohibido filtfilt / fase cero en la ruta de features: rompe la
      paridad entrenamiento <-> inferencia.
    * Dato faltante = NaN + máscara. Nunca rellenar con ceros como si fuera dato.
    * Las salidas intermedias deben coincidir con los vectores dorados dentro
      de PARITY_TOLERANCES.

NO es un dispositivo diagnóstico. La salida máxima del sistema es
"posible patrón asociado"; la confirmación solo la da el usuario.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
from collections import deque
from dataclasses import asdict, dataclass
from typing import Callable, Dict, List, Optional, Tuple

import numpy as np

VERSION = "visio-ref-1.1.0"
G = 9.80665
NAN = float("nan")


# =============================================================================
# Configuración
# =============================================================================
@dataclass
class Config:
    """Parámetros por defecto. Son PUNTOS DE PARTIDA: se re-ajustan con datos
    reales etiquetados (ver protocolo de validación en la especificación)."""
    # ---- L0: rejilla y sincronía ----
    fs: float = 25.0                  # Hz. Medido en el Watch7 (40.0 ms); basta para la mano de la pipa
    max_interp_gap_s: float = 0.12    # huecos mayores -> NaN + máscara 0
    # ---- L1: calidad ----
    acc_range_ms2: float = 78.45      # ±8 g; leer Sensor.getMaximumRange()
    sat_margin_ms2: float = 0.5
    hr_min: float = 35.0
    hr_max: float = 210.0
    hr_max_jump: float = 25.0         # bpm vs mediana de las últimas 10
    ibi_min_ms: float = 300.0
    ibi_max_ms: float = 2000.0
    ibi_max_rel_dev: float = 0.25     # vs mediana de los últimos 5
    # ---- L2: orientación ----
    tau_comp_s: float = 0.8           # constante del filtro complementario
    acc_trust_band: float = 1.5       # m/s²: confianza en acc cae a 0 si | |a|-g | >= esto
    lp_gravity_hz: float = 0.8        # gravedad sin giroscopio (LP 2º orden)
    lp_theta_hz: float = 3.0          # suavizado de θ para derivadas
    gyro_bias_tau_s: float = 5.0
    bias_still_gyro: float = 0.10     # rad/s
    bias_still_acc: float = 0.30      # m/s²
    bias_still_hold_s: float = 1.0
    still_lin_acc: float = 0.35       # m/s²
    still_gyro: float = 0.25          # rad/s
    still_win_s: float = 0.4
    # ---- L3: segmentación ----
    base_tau_s: float = 4.0
    base_quasistatic_dps: float = 20.0
    theta_base_cap_deg: float = 10.0
    lambda_orient: float = 0.7
    de_tau_s: float = 0.10
    e_start_deg: float = 8.0
    v_start_dps: float = 18.0
    quiet_dps: float = 10.0
    max_onset_lookback_s: float = 1.5
    e_on_deg: float = 25.0
    rise_min_deg: float = 12.0
    theta_dwell_min_deg: float = -30.0
    v_stable_dps: float = 35.0
    stable_hold_s: float = 0.2
    raise_max_s: float = 3.0
    drop_deg: float = 12.0
    v_drop_dps: float = 25.0
    near_peak_deg: float = 4.0
    reapproach_deg: float = 8.0
    reapproach_window_s: float = 1.5
    return_margin_deg: float = 6.0
    lower_max_s: float = 4.0
    settle_dps: float = 10.0
    settle_hold_s: float = 0.5
    settle_min_drop_deg: float = 20.0
    hold_release_s: float = 2.0
    orient_drop_margin_deg: float = 5.0   # la ruta por orientación no cuenta si el brazo BAJA
    plateau_s: float = 3.0
    chunk_s: float = 15.0                 # permanencias largas -> fragmentos de evidencia cada 15 s
    frozen_deg: float = 8.0               # orientación inmóvil dentro de este cono...
    frozen_s: float = 30.0                # ...durante tanto tiempo = postura: emitir y re-basar
    dwell_max_s: float = 120.0
    min_dwell_s: float = 0.0
    min_total_s: float = 0.6
    max_gap_in_segment_s: float = 0.5
    # ---- L4: features ----
    pre_ctx_s: float = 3.0
    post_ctx_s: float = 2.0
    act_pre_s: float = 30.0
    still_pre_s: float = 5.0
    spike_z: float = 6.0
    spike_min_sep_s: float = 0.15
    spike_pad_s: float = 0.3
    min_spectral_dwell_s: float = 1.0
    band_lo_hz: float = 0.5
    band_hi_hz: float = 6.0
    split_hz: float = 4.0
    rr_min_wphi: float = 0.3          # θ <= ~72°: requerido para features de giro con acc crudo
    roll_step_deg: float = 10.0       # escalón de giro de la pipa: >= 10° ...
    roll_step_win_s: float = 1.5      # ... en <= 1.5 s
    roll_step_sep_s: float = 1.0
    micro_gyro_thr: float = 0.6
    micro_acc_thr: float = 0.8
    dtw_points: int = 32
    dtw_band: float = 0.2
    # ---- L5: clasificador ----
    novelty_k: int = 5
    novelty_quantile: float = 0.99
    novelty_llr_factor: float = 0.5
    continued_llr_factor: float = 0.5  # fragmentos 2..n de una misma permanencia: evidencia correlacionada
    # ---- L6: episodio ----
    llr_clip_lo: float = -3.0
    llr_clip_hi: float = 5.0
    cusum_kappa: float = 0.5
    cusum_tau_mem_s: float = 300.0
    h_candidate: float = 3.0
    h_alert: float = 6.0
    kofn_k: int = 2
    kofn_p: float = 0.5
    kofn_window_s: float = 600.0
    episode_end_s: float = 600.0
    refractory_s: float = 2700.0
    # ---- L7: corroboración lenta ----
    physio_enabled: bool = False      # NO activar hasta validar
    physio_weight: float = 0.0
    # ---- L8: decisión ----
    prompts_enabled: bool = True
    min_prompt_interval_s: float = 2700.0
    max_prompts_per_day: int = 4

    def to_dict(self) -> dict:
        return asdict(self)


PARITY_TOLERANCES = {
    "g_dev_abs": 1e-4,
    "theta_deg_abs": 0.05,
    "phi_deg_abs": 0.2,          # comparar solo donde w_phi >= 0.2
    "E_deg_abs": 0.1,
    "state_mismatch_frac": 0.002,
    "segment_index_abs": 1,
    "feature_rel": 1e-3,
    "feature_abs": 1e-3,
    "p_target_abs": 1e-4,
}


# =============================================================================
# Utilidades numéricas
# =============================================================================
def _finite(x) -> np.ndarray:
    x = np.asarray(x, dtype=float)
    return x[np.isfinite(x)]


def _nanmean(x) -> float:
    v = _finite(x)
    return float(v.mean()) if v.size else NAN


def _nanstd(x) -> float:
    v = _finite(x)
    return float(v.std()) if v.size >= 2 else NAN


def _nanmax(x) -> float:
    v = _finite(x)
    return float(v.max()) if v.size else NAN


def _nanmin(x) -> float:
    v = _finite(x)
    return float(v.min()) if v.size else NAN


def _nanpct(x, q) -> float:
    v = _finite(x)
    return float(np.percentile(v, q)) if v.size else NAN


def _rms(x) -> float:
    v = _finite(x)
    return float(np.sqrt(np.mean(v * v))) if v.size else NAN


def _logit(p: float) -> float:
    p = min(max(p, 1e-6), 1 - 1e-6)
    return math.log(p / (1.0 - p))


def _slope(y, fs) -> float:
    """Pendiente por mínimos cuadrados (unidades/s)."""
    y = np.asarray(y, float)
    m = np.isfinite(y)
    if m.sum() < 3:
        return NAN
    tt = np.arange(len(y))[m] / fs
    yy = y[m]
    tt = tt - tt.mean()
    den = float((tt * tt).sum())
    return float((tt * (yy - yy.mean())).sum() / den) if den > 0 else NAN


def _wrap180(a: float) -> float:
    return (a + 180.0) % 360.0 - 180.0


def _interp_nans(x) -> np.ndarray:
    x = np.asarray(x, float).copy()
    m = np.isfinite(x)
    if m.all() or not m.any():
        return x
    idx = np.arange(len(x))
    x[~m] = np.interp(idx[~m], idx[m], x[m])
    return x


def _unwrap_deg(x) -> np.ndarray:
    x = _interp_nans(x)
    if not np.isfinite(x).any():
        return x
    return np.degrees(np.unwrap(np.radians(x)))


def _circ_mean(phi_deg, w=None) -> Tuple[float, float, float]:
    """Media circular ponderada -> (sin, cos, R) de la dirección media."""
    phi = np.asarray(phi_deg, float)
    w = np.ones_like(phi) if w is None else np.asarray(w, float)
    m = np.isfinite(phi) & np.isfinite(w) & (w > 0)
    if not m.any():
        return NAN, NAN, NAN
    r = np.radians(phi[m])
    ww = w[m]
    S = float((ww * np.sin(r)).sum())
    C = float((ww * np.cos(r)).sum())
    R = math.hypot(S, C) / float(ww.sum())
    if R < 1e-12:
        return NAN, NAN, 0.0
    ang = math.atan2(S, C)
    return math.sin(ang), math.cos(ang), R


def _circ_angle(phi_deg, w=None) -> float:
    s, c, _ = _circ_mean(phi_deg, w)
    return math.degrees(math.atan2(s, c)) if np.isfinite(s) else NAN


def _circ_std_deg(phi_deg) -> float:
    _, _, R = _circ_mean(phi_deg)
    if not np.isfinite(R) or R <= 0:
        return NAN
    return math.degrees(math.sqrt(max(0.0, -2.0 * math.log(min(1.0, R)))))


def _circ_range_deg(phi_deg) -> float:
    u = _unwrap_deg(phi_deg)
    u = u[np.isfinite(u)]
    return float(np.percentile(u, 90) - np.percentile(u, 10)) if u.size >= 3 else NAN


def causal_moving_mean(x, k: int) -> np.ndarray:
    """Media móvil causal de k muestras ignorando NaN (NaN si la ventana está vacía)."""
    x = np.asarray(x, float)
    v = np.isfinite(x)
    xv = np.where(v, x, 0.0)
    cs = np.concatenate([[0.0], np.cumsum(xv)])
    cn = np.concatenate([[0], np.cumsum(v.astype(np.int64))])
    idx = np.arange(len(x))
    lo = np.maximum(0, idx - k + 1)
    s = cs[idx + 1] - cs[lo]
    c = cn[idx + 1] - cn[lo]
    return np.where(c > 0, s / np.maximum(c, 1), np.nan)


def _peaks(x, thr: float, min_sep: int) -> List[int]:
    """Máximos locales >= thr separados al menos min_sep muestras (causal-friendly)."""
    x = np.asarray(x, float)
    out: List[int] = []
    last = -10 ** 9
    for k in range(1, len(x) - 1):
        v = x[k]
        if not np.isfinite(v) or v < thr:
            continue
        lft = x[k - 1] if np.isfinite(x[k - 1]) else -np.inf
        rgt = x[k + 1] if np.isfinite(x[k + 1]) else -np.inf
        if v >= lft and v > rgt and (k - last) >= min_sep:
            out.append(k)
            last = k
    return out


def _zcr(x, fs: float, hyst: float) -> float:
    """Cruces por cero por segundo con histéresis ±hyst alrededor de la media."""
    x = _finite(x)
    if x.size < 3:
        return NAN
    x = x - x.mean()
    s, c = 0, 0
    for v in x:
        if v > hyst:
            q = 1
        elif v < -hyst:
            q = -1
        else:
            continue
        if s != 0 and q != s:
            c += 1
        s = q
    return c / (x.size / fs)


def _spectrum(x, fs: float):
    """Espectro de potencia: NaN internos interpolados, sin tendencia lineal,
    ventana Hann simétrica w[k] = 0.5 - 0.5·cos(2πk/(N-1)). Las features que
    salen de aquí son razones -> invariantes a la escala de la FFT usada."""
    x = np.asarray(x, float)
    m = np.isfinite(x)
    if m.sum() < 8:
        return None, None
    idx = np.arange(len(x))
    x = np.interp(idx, idx[m], x[m])
    tt = idx / fs
    A = np.column_stack([tt, np.ones_like(tt)])
    coef, *_ = np.linalg.lstsq(A, x, rcond=None)
    x = x - A @ coef
    P = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    fq = np.fft.rfftfreq(len(x), 1.0 / fs)
    return fq, P


def _dom_peak(fq, P, lo: float, hi: float) -> Tuple[float, float]:
    """(frecuencia dominante, prominencia = potencia del pico ±1 bin / potencia de banda)."""
    if fq is None:
        return NAN, NAN
    band = (fq >= lo) & (fq <= hi)
    if band.sum() < 2:
        return NAN, NAN
    tot = float(P[band].sum())
    if tot <= 0:
        return NAN, NAN
    j = int(np.argmax(np.where(band, P, -1.0)))
    sl = slice(max(0, j - 1), j + 2)
    num = float(P[sl][band[sl]].sum())
    return float(fq[j]), num / tot


def _spec_entropy(P) -> float:
    P = _finite(P)
    s = float(P.sum())
    if s <= 0 or P.size < 2:
        return NAN
    p = P / s
    p = p[p > 0]
    return float(-(p * np.log(p)).sum() / math.log(P.size))


# =============================================================================
# Filtros causales
# =============================================================================
def butter2_lowpass(fc: float, fs: float):
    """Butterworth pasabajas de 2º orden (bilineal con prewarp = RBJ con Q=1/√2).
    Devuelve (b0,b1,b2), (1,a1,a2). Coincide con scipy.signal.butter(2, fc, fs=fs)."""
    if not 0.0 < fc < fs / 2.0:
        raise ValueError(f"fc={fc} fuera de (0, fs/2)")
    w0 = 2.0 * math.pi * fc / fs
    c, s = math.cos(w0), math.sin(w0)
    alpha = s / math.sqrt(2.0)
    a0 = 1.0 + alpha
    b = ((1.0 - c) / (2.0 * a0), (1.0 - c) / a0, (1.0 - c) / (2.0 * a0))
    a = (1.0, (-2.0 * c) / a0, (1.0 - alpha) / a0)
    return b, a


class Biquad:
    """Sección bicuadrática, forma directa II transpuesta (causal)."""
    __slots__ = ("b0", "b1", "b2", "a1", "a2", "z1", "z2")

    def __init__(self, b, a):
        self.b0, self.b1, self.b2 = (float(v) for v in b)
        self.a1, self.a2 = float(a[1]), float(a[2])
        self.z1 = self.z2 = 0.0

    def reset(self, x0: float) -> None:
        """Estado estacionario para entrada constante x0 (sin transitorio inicial)."""
        y = x0 * (self.b0 + self.b1 + self.b2) / (1.0 + self.a1 + self.a2)
        self.z2 = self.b2 * x0 - self.a2 * y
        self.z1 = self.b1 * x0 - self.a1 * y + self.z2

    def step(self, x: float) -> float:
        y = self.b0 * x + self.z1
        self.z1 = self.b1 * x - self.a1 * y + self.z2
        self.z2 = self.b2 * x - self.a2 * y
        return y


# =============================================================================
# Montaje y marco anatómico
# =============================================================================
@dataclass(frozen=True)
class Mounting:
    """Montaje del reloj -> marco anatómico A = (f, l, d).
    f: eje del antebrazo hacia la mano (distal)
    l: lado cubital (apunta ABAJO en la pose 'saludo, pulgar arriba')
    d: dorso de la muñeca (= eje z, saliendo de la pantalla)
    a_A = s ⊙ a_dev ;  ω_A = det · (s ⊙ ω_dev) ;  s = (sx, sy, 1) ; det = sx·sy
    det = +1 en muñeca izquierda y -1 en derecha (espejo): gestos espejo producen
    señales anatómicas IDÉNTICAS en ambas muñecas (ω es pseudovector)."""
    sx: int = 1
    sy: int = 1

    @property
    def s(self) -> np.ndarray:
        return np.array([float(self.sx), float(self.sy), 1.0])

    @property
    def det(self) -> float:
        return float(self.sx * self.sy)

    @staticmethod
    def from_config(wrist: str, crown_toward_hand: Optional[bool] = None) -> "Mounting":
        """Supone corona/botones en el lado +x (las 3 h). Uso estándar: izquierda con
        corona hacia la mano; derecha con corona hacia el codo.
        VALIDAR UNA VEZ por modelo de reloj con from_calibration()."""
        w = wrist.strip().upper()[:1]
        if w not in ("L", "R", "I", "D"):
            raise ValueError("wrist debe ser L/R (o I/D)")
        left = w in ("L", "I")
        if crown_toward_hand is None:
            crown_toward_hand = left
        sx = 1 if crown_toward_hand else -1
        sy = sx if left else -sx
        return Mounting(sx, sy)

    @staticmethod
    def from_calibration(acc_hang, acc_handshake) -> Tuple["Mounting", List[str]]:
        """Pose A (5 s): brazo colgando relajado, palma hacia el muslo -> a·f ≈ -g.
        Pose B (5 s): antebrazo horizontal al frente, pulgar arriba -> a·l ≈ -g."""
        ah = np.nanmean(np.asarray(acc_hang, float), axis=0)
        ak = np.nanmean(np.asarray(acc_handshake, float), axis=0)
        sx = -1 if ah[0] > 0 else 1
        sy = -1 if ak[1] > 0 else 1
        problems = []
        if abs(ah[0]) < 0.7 * G:
            problems.append("pose A: |a_x| < 0.7 g — ¿brazo realmente vertical?")
        if abs(ak[1]) < 0.7 * G:
            problems.append("pose B: |a_y| < 0.7 g — ¿pulgar arriba y antebrazo horizontal?")
        if abs(ah[2]) > 0.35 * G or abs(ak[2]) > 0.35 * G:
            problems.append("gravedad en z durante la calibración — ¿reloj girado en la correa?")
        return Mounting(sx, sy), problems


# =============================================================================
# L0 — Ingesta y remuestreo
# =============================================================================
@dataclass
class Streams:
    """Entradas crudas en convención Android: aceleración específica en m/s²
    (+9.81 en z con el reloj boca arriba y quieto), giroscopio en rad/s,
    tiempos en ns de un reloj MONOTÓNICO común (elapsedRealtimeNanos).
    iOS/watchOS: a_android = -a_coremotion · 9.80665."""
    acc_t_ns: np.ndarray
    acc: np.ndarray
    gyr_t_ns: Optional[np.ndarray] = None
    gyr: Optional[np.ndarray] = None
    hr_t_ns: Optional[np.ndarray] = None
    hr_bpm: Optional[np.ndarray] = None
    hr_ok: Optional[np.ndarray] = None
    ibi_t_ns: Optional[np.ndarray] = None
    ibi_ms: Optional[np.ndarray] = None
    ibi_ok: Optional[np.ndarray] = None
    screen_on_t_ns: Optional[np.ndarray] = None


def resample_uniform(t_ns, x, fs: float, t0_ns: int, t1_ns: int, max_gap_s: float):
    """Interpolación lineal a la rejilla t_k = k/fs (s desde t0_ns).
    Devuelve (t_grid_s, y, ok). Puntos dentro de huecos > max_gap_s -> NaN, ok=False."""
    t_ns = np.asarray(t_ns, dtype=np.int64)
    x = np.asarray(x, dtype=float)
    if x.ndim == 1:
        x = x[:, None]
    n = int(math.floor((t1_ns - t0_ns) * 1e-9 * fs + 1e-9)) + 1
    tg = np.arange(n) / fs
    y = np.full((n, x.shape[1]), np.nan)
    ok = np.zeros(n, dtype=bool)
    if len(t_ns) < 2:
        return tg, y, ok
    order = np.argsort(t_ns, kind="stable")
    t_ns, x = t_ns[order], x[order]
    keep = np.concatenate([[True], np.diff(t_ns) > 0]) & np.all(np.isfinite(x), axis=1)
    t_ns, x = t_ns[keep], x[keep]
    if len(t_ns) < 2:
        return tg, y, ok
    ts = (t_ns - t0_ns) * 1e-9
    i1 = np.clip(np.searchsorted(ts, tg, side="right"), 1, len(ts) - 1)
    i0 = i1 - 1
    ta, tb = ts[i0], ts[i1]
    gap = tb - ta
    w = np.clip((tg - ta) / np.where(gap > 0, gap, 1.0), 0.0, 1.0)
    inside = (tg >= ts[0]) & (tg <= ts[-1])
    exact = np.abs(tg - ta) < 1e-9
    ok = inside & ((gap <= max_gap_s + 1e-9) | exact)
    y = x[i0] * (1.0 - w)[:, None] + x[i1] * w[:, None]
    y[~ok] = np.nan
    return tg, y, ok


# =============================================================================
# L1 — Calidad de señal fisiológica
# =============================================================================
def hr_valid_mask(bpm, ok, cfg: Config) -> np.ndarray:
    """FC válida: estado del SDK válido, rango fisiológico y salto <= hr_max_jump
    respecto a la mediana de las últimas 10 lecturas plausibles."""
    bpm = np.asarray(bpm, float)
    ok = np.asarray(ok, bool)
    out = np.zeros(len(bpm), bool)
    hist: deque = deque(maxlen=10)
    for i in range(len(bpm)):
        b = bpm[i]
        if not ok[i] or not np.isfinite(b) or b < cfg.hr_min or b > cfg.hr_max:
            continue
        if len(hist) >= 3 and abs(b - float(np.median(hist))) > cfg.hr_max_jump:
            hist.append(b)
            continue
        hist.append(b)
        out[i] = True
    return out


def ibi_valid_mask(ibi_ms, ok, cfg: Config) -> np.ndarray:
    x = np.asarray(ibi_ms, float)
    ok = np.asarray(ok, bool)
    out = np.zeros(len(x), bool)
    hist: deque = deque(maxlen=5)
    for i in range(len(x)):
        v = x[i]
        if not ok[i] or not np.isfinite(v) or v < cfg.ibi_min_ms or v > cfg.ibi_max_ms:
            continue
        if len(hist) >= 3:
            med = float(np.median(hist))
            if abs(v - med) > cfg.ibi_max_rel_dev * med:
                hist.append(v)
                continue
        hist.append(v)
        out[i] = True
    return out


def rmssd(ibi_ms, valid) -> float:
    """RMSSD solo con pares consecutivos válidos (>= 10 pares)."""
    x = np.asarray(ibi_ms, float)
    v = np.asarray(valid, bool)
    pair = v[1:] & v[:-1]
    if pair.sum() < 10:
        return NAN
    d = np.diff(x)[pair]
    return float(np.sqrt(np.mean(d * d)))


# =============================================================================
# L2 — Orientación (vector gravedad) y señales derivadas
# =============================================================================
class GravityEstimator:
    """Vector unitario 'arriba' ĝ en el marco del dispositivo.
    Con giroscopio: propagación exacta (Rodrigues) dĝ/dt = -ω×ĝ, corrección por
    acelerómetro con ganancia k = (1 - e^{-dt/τ})·confianza, y sesgo del
    giroscopio estimado en quietud. Sin giroscopio: LP Butterworth 2º orden por
    eje y normalización. Implementación con escalares (igual que en Kotlin)."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.dt = 1.0 / cfg.fs
        self.k_comp = 1.0 - math.exp(-self.dt / cfg.tau_comp_s)
        self.k_bias = self.dt / cfg.gyro_bias_tau_s
        self.still_req = int(round(cfg.bias_still_hold_s * cfg.fs))
        b, a = butter2_lowpass(cfg.lp_gravity_hz, cfg.fs)
        self.lp = (Biquad(b, a), Biquad(b, a), Biquad(b, a))
        self.bx = self.by = self.bz = 0.0
        self.still_n = 0
        self.g: Optional[Tuple[float, float, float]] = None

    def invalidate(self) -> None:
        self.g = None
        self.still_n = 0

    def update(self, ax: float, ay: float, az: float, w=None):
        cfg = self.cfg
        an = math.sqrt(ax * ax + ay * ay + az * az)
        if an < 1e-6:
            return None
        if self.g is None:
            self.g = (ax / an, ay / an, az / an)
            self.lp[0].reset(ax)
            self.lp[1].reset(ay)
            self.lp[2].reset(az)
        lx, ly, lz = self.lp[0].step(ax), self.lp[1].step(ay), self.lp[2].step(az)
        if w is not None:
            wx, wy, wz = float(w[0]), float(w[1]), float(w[2])
            cx, cy, cz = wx - self.bx, wy - self.by, wz - self.bz
            wn = math.sqrt(cx * cx + cy * cy + cz * cz)
            gx, gy, gz = self.g
            ang = wn * self.dt
            if ang > 1e-12:
                kx, ky, kz = -cx / wn, -cy / wn, -cz / wn
                ca, sa = math.cos(ang), math.sin(ang)
                kdg = kx * gx + ky * gy + kz * gz
                crx, cry, crz = ky * gz - kz * gy, kz * gx - kx * gz, kx * gy - ky * gx
                gx, gy, gz = (gx * ca + crx * sa + kx * kdg * (1.0 - ca),
                              gy * ca + cry * sa + ky * kdg * (1.0 - ca),
                              gz * ca + crz * sa + kz * kdg * (1.0 - ca))
            err = abs(an - G)
            kk = self.k_comp * max(0.0, 1.0 - err / cfg.acc_trust_band)
            gx = (1.0 - kk) * gx + kk * ax / an
            gy = (1.0 - kk) * gy + kk * ay / an
            gz = (1.0 - kk) * gz + kk * az / an
            gn = math.sqrt(gx * gx + gy * gy + gz * gz)
            gx, gy, gz = gx / gn, gy / gn, gz / gn
            if wn < cfg.bias_still_gyro and err < cfg.bias_still_acc:
                self.still_n += 1
            else:
                self.still_n = 0
            if self.still_n >= self.still_req:
                self.bx += self.k_bias * (wx - self.bx)
                self.by += self.k_bias * (wy - self.by)
                self.bz += self.k_bias * (wz - self.bz)
            self.g = (gx, gy, gz)
            return gx, gy, gz, 1
        ln = math.sqrt(lx * lx + ly * ly + lz * lz)
        if ln > 1e-6:
            self.g = (lx / ln, ly / ln, lz / ln)
        return self.g[0], self.g[1], self.g[2], 0


@dataclass
class Derived:
    t: np.ndarray            # s desde t0
    valid: np.ndarray        # bool
    gyro_valid: np.ndarray   # bool
    g_dev: np.ndarray        # (n,3) ĝ en marco del dispositivo
    mode: np.ndarray         # 1 = con giroscopio, 0 = solo acelerómetro
    a_A: np.ndarray          # (n,3) aceleración en marco anatómico
    g_A: np.ndarray          # (n,3) ĝ en marco anatómico
    w_A: np.ndarray          # (n,3) ω en marco anatómico (NaN si no hay)
    theta: np.ndarray        # deg: elevación del antebrazo (+ = mano arriba del codo)
    theta_s: np.ndarray      # deg: θ suavizado (LP causal)
    dtheta: np.ndarray       # deg/s
    phi: np.ndarray          # deg: giro sobre el antebrazo (0 = dorso arriba)
    w_phi: np.ndarray        # confianza de φ (= cos θ)
    roll_raw: np.ndarray     # deg: giro con acc crudo (ve oscilaciones > LP de gravedad)
    alin: np.ndarray         # m/s²: |a - g·ĝ|
    jerk: np.ndarray         # m/s³: |Δa|·fs
    wnorm: np.ndarray        # rad/s
    still: np.ndarray        # bool
    sat: np.ndarray          # bool


def derive(t, acc, acc_ok, gyr, gyr_ok, cfg: Config, mount: Mounting) -> Derived:
    fs = cfg.fs
    n = len(t)
    acc = np.asarray(acc, float)
    acc_ok = np.asarray(acc_ok, bool)
    has_g = gyr is not None
    gyr_ok = np.asarray(gyr_ok, bool) if has_g else np.zeros(n, bool)
    est = GravityEstimator(cfg)
    g_dev = np.full((n, 3), np.nan)
    mode = np.zeros(n, np.int8)
    for i in range(n):
        if not acc_ok[i]:
            est.invalidate()
            continue
        w = gyr[i] if (has_g and gyr_ok[i]) else None
        r = est.update(acc[i, 0], acc[i, 1], acc[i, 2], w)
        if r is None:
            continue
        g_dev[i, 0], g_dev[i, 1], g_dev[i, 2], mode[i] = r
    s = mount.s
    a_A = acc * s
    g_A = g_dev * s
    gyro_valid = gyr_ok & acc_ok
    if has_g:
        w_A = mount.det * (np.asarray(gyr, float) * s)
        w_A[~gyro_valid] = np.nan
        wnorm = np.linalg.norm(np.asarray(gyr, float), axis=1)
        wnorm[~gyro_valid] = np.nan
    else:
        w_A = np.full((n, 3), np.nan)
        wnorm = np.full(n, np.nan)
    theta = np.degrees(np.arcsin(np.clip(g_A[:, 0], -1.0, 1.0)))
    phi = np.degrees(np.arctan2(g_A[:, 1], g_A[:, 2]))
    w_phi = np.hypot(g_A[:, 1], g_A[:, 2])
    roll_raw = np.degrees(np.arctan2(a_A[:, 1], a_A[:, 2]))
    alin = np.linalg.norm(acc - G * g_dev, axis=1)
    jerk = np.full(n, np.nan)
    jerk[1:] = np.linalg.norm(np.diff(acc, axis=0), axis=1) * fs
    b, a = butter2_lowpass(cfg.lp_theta_hz, fs)
    bq = Biquad(b, a)
    theta_s = np.full(n, np.nan)
    prev_ok = False
    for i in range(n):
        if not np.isfinite(theta[i]):
            prev_ok = False
            continue
        if not prev_ok:
            bq.reset(float(theta[i]))
        theta_s[i] = bq.step(float(theta[i]))
        prev_ok = True
    dtheta = np.full(n, np.nan)
    dtheta[1:] = np.diff(theta_s) * fs
    k = max(1, int(round(cfg.still_win_s * fs)))
    alin_ma = causal_moving_mean(alin, k)
    w_ma = causal_moving_mean(wnorm, k)
    still = (alin_ma < cfg.still_lin_acc) & np.where(np.isfinite(w_ma), w_ma < cfg.still_gyro, True) & acc_ok
    with np.errstate(invalid="ignore"):
        sat = np.any(np.abs(acc) >= cfg.acc_range_ms2 - cfg.sat_margin_ms2, axis=1) & acc_ok
    valid = acc_ok & np.isfinite(theta)
    return Derived(t=np.asarray(t, float), valid=valid, gyro_valid=gyro_valid, g_dev=g_dev, mode=mode,
                   a_A=a_A, g_A=g_A, w_A=w_A, theta=theta, theta_s=theta_s, dtheta=dtheta, phi=phi,
                   w_phi=w_phi, roll_raw=roll_raw, alin=alin, jerk=jerk, wnorm=wnorm, still=still, sat=sat)


# =============================================================================
# L3 — Segmentación de gestos (máquina de estados causal)
# =============================================================================
IDLE, RISING, DWELL, FALLING, HOLD = 0, 1, 2, 3, 4
STATE_NAMES = ("IDLE", "RISING", "DWELL", "FALLING", "HOLD")


@dataclass
class Segment:
    i_on: int
    i_dw0: int
    i_dw1: int
    i_end: int
    t_on: float
    t_dw0: float
    t_dw1: float
    t_end: float
    e_onset: float
    e_peak: float
    theta_base: float
    n_reapproach: int = 0
    truncated: bool = False
    route_orient: bool = False
    quick: bool = False
    hold_id: int = 0
    chunk: int = 0
    frozen: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


class Segmenter:
    """Excursión E = max(θs − θ_ref, λ·∠(ĝ, ĝ_base)·[θs ≥ θ_ref − margen]),  θ_ref = min(θ_base, cap).
    θ_base, ĝ_base: medias exponenciales (τ = base_tau_s) actualizadas solo en IDLE y en
    cuasi-reposo. El tope 'cap' detecta gestos que parten de una postura elevada; la ruta por
    orientación cubre posturas donde la elevación cambia poco (recostado de lado) pero se apaga
    cuando el brazo BAJA (bajar la mano no es un gesto hacia la cara).

    v1.1 (calibrado con sesiones reales): la mano de la pipa permanece en la cara 10–70 s con
    giros discretos. Por eso:
      * nada se descarta en silencio por la altura (recostado, la cara puede estar a θ ≈ 10°);
      * una permanencia larga se emite en fragmentos de chunk_s (evidencia temprana, latencia baja);
      * postura = orientación congelada (≤ frozen_deg) durante frozen_s: se emite y se re-basa;
      * nueva elevación desde una meseta quieta (plateau_s): la meseta se emite y arranca otro gesto."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.dt = 1.0 / cfg.fs
        self.k_base = 1.0 - math.exp(-self.dt / cfg.base_tau_s)
        self.k_de = 1.0 - math.exp(-self.dt / cfg.de_tau_s)
        self.hist: deque = deque(maxlen=int(math.ceil(
            (cfg.raise_max_s + cfg.max_onset_lookback_s + 1.0) * cfg.fs)))
        self.n_hold = 0
        self._reset_all()

    # ---------------------------------------------------------------- estado
    def _reset_all(self) -> None:
        self.state = IDLE
        self.theta_b: Optional[float] = None
        self.g_b: Optional[Tuple[float, float, float]] = None
        self.e_prev: Optional[float] = None
        self.de = 0.0
        self.quiet: Optional[Tuple[int, float, float]] = None
        self.gap_n = 0
        self.hold_n = 0
        self.hist.clear()
        self._clear_seg()

    def _clear_seg(self) -> None:
        self.i_on, self.t_on, self.e_on, self.th_base = -1, NAN, 0.0, NAN
        self.stable_n = 0
        self.i_dw0, self.t_dw0, self.i_dw1, self.t_dw1 = -1, NAN, -1, NAN
        self.e_peak, self.i_peak, self.th_at_peak = -math.inf, -1, NAN
        self.near_i, self.near_t = -1, NAN
        self.e_min_fall, self.settle_n = math.inf, 0
        self.q_run, self.q_last, self.q = 0, 0, None
        self.n_re, self.route_orient, self.quick = 0, False, False
        self.chunk, self.t_hold0 = 0, NAN
        self.fz_ref, self.fz_t0 = None, NAN
        self.th_dw_peak = -math.inf

    def _rebase(self, theta_s: float, g) -> None:
        self.theta_b = float(theta_s)
        self.g_b = (float(g[0]), float(g[1]), float(g[2]))
        self.e_prev = None
        self.de = 0.0

    @staticmethod
    def _angle(g, h) -> float:
        dot = g[0] * h[0] + g[1] * h[1] + g[2] * h[2]
        return math.degrees(math.acos(max(-1.0, min(1.0, dot))))

    def _excursion(self, theta_s: float, g):
        cfg = self.cfg
        ref = min(self.theta_b, cfg.theta_base_cap_deg)
        exc_elev = theta_s - ref
        if theta_s >= ref - cfg.orient_drop_margin_deg:
            e_or = cfg.lambda_orient * self._angle(g, self.g_b)
        else:
            e_or = 0.0
        return (exc_elev if exc_elev >= e_or else e_or), exc_elev, e_or

    def _start_rising(self, i_on, t_on, e_on, E, i, theta_s, route) -> None:
        self._clear_seg()
        self.n_hold += 1
        self.state = RISING
        self.i_on, self.t_on, self.e_on = i_on, t_on, e_on
        self.th_base = self.theta_b
        self.e_peak, self.i_peak, self.th_at_peak = E, i, theta_s
        self.route_orient = bool(route)

    def _enter_dwell(self, i0: int, t0: float, i: int, t: float, g, theta_s: float) -> None:
        self.state = DWELL
        self.th_dw_peak = theta_s
        self.i_dw0, self.t_dw0 = i0, t0
        self.t_hold0 = t0
        self.near_i, self.near_t = i, t
        self.fz_ref, self.fz_t0 = (float(g[0]), float(g[1]), float(g[2])), t
        self.q_run, self.q_last, self.q = 0, 0, None

    def _make(self, i_end: int, t_end: float, truncated: bool = False,
              frozen: bool = False) -> Optional[Segment]:
        cfg = self.cfg
        if ((self.t_dw1 - self.t_dw0) >= cfg.min_dwell_s - 1e-9
                and (t_end - self.t_on) >= cfg.min_total_s - 1e-9):
            return Segment(self.i_on, self.i_dw0, self.i_dw1, i_end, self.t_on, self.t_dw0, self.t_dw1,
                           t_end, self.e_on, self.e_peak, self.th_base, self.n_re, truncated,
                           self.route_orient, self.quick, self.n_hold, self.chunk, frozen)
        return None

    def _emit(self, i_end: int, t_end: float, truncated: bool = False,
              frozen: bool = False) -> Optional[Segment]:
        seg = self._make(i_end, t_end, truncated, frozen)
        self._clear_seg()
        return seg

    def _chunk(self, i: int, t: float) -> Optional[Segment]:
        """Emite el fragmento en curso de una permanencia larga y continúa LA MISMA permanencia."""
        self.i_dw1, self.t_dw1 = i, t
        seg = self._make(i, t, truncated=True)
        self.chunk += 1
        self.i_on, self.t_on = i, t
        self.i_dw0, self.t_dw0 = i, t
        self.n_re, self.quick = 0, False
        return seg

    def _near_peak_region(self):
        items = [h for h in self.hist if h[0] >= self.i_on]
        if not items:
            return self.i_peak, NAN, self.i_peak, NAN
        k = max(range(len(items)), key=lambda j: items[j][2])
        thr = items[k][2] - self.cfg.near_peak_deg
        a = k
        while a - 1 >= 0 and items[a - 1][2] >= thr:
            a -= 1
        b = k
        while b + 1 < len(items) and items[b + 1][2] >= thr:
            b += 1
        return items[a][0], items[a][1], items[b][0], items[b][1]

    def flush(self, i: int, t: float) -> Optional[Segment]:
        """Fin de datos: emite el gesto en curso si ya tenía permanencia."""
        if self.state == FALLING:
            return self._emit(i, t)
        if self.state == DWELL:
            self.i_dw1, self.t_dw1 = i, t
            return self._emit(i, t, truncated=True)
        return None

    # ---------------------------------------------------------------- paso
    def step(self, i: int, t: float, valid: bool, theta_s: float, dtheta: float, g):
        cfg = self.cfg
        if (not valid) or (not np.isfinite(theta_s)) or g is None or not np.isfinite(g[0]):
            self.gap_n += 1
            if self.gap_n * self.dt > cfg.max_gap_in_segment_s and (
                    self.state != IDLE or self.theta_b is not None):
                gap = self.gap_n
                self._reset_all()
                self.gap_n = gap
            return None, NAN, self.state
        self.gap_n = 0
        if self.theta_b is None:
            self._rebase(theta_s, g)
        E, exc_elev, e_or = self._excursion(theta_s, g)
        if self.e_prev is not None:
            self.de += self.k_de * ((E - self.e_prev) / self.dt - self.de)
        self.e_prev = E
        de = self.de
        self.hist.append((i, t, E))
        seg = None
        st = self.state

        if st == IDLE:
            if abs(de) < cfg.quiet_dps:
                self.quiet = (i, t, E)
            if np.isfinite(dtheta) and abs(dtheta) < cfg.base_quasistatic_dps:
                self.theta_b += self.k_base * (theta_s - self.theta_b)
                gx = self.g_b[0] + self.k_base * (g[0] - self.g_b[0])
                gy = self.g_b[1] + self.k_base * (g[1] - self.g_b[1])
                gz = self.g_b[2] + self.k_base * (g[2] - self.g_b[2])
                gn = math.sqrt(gx * gx + gy * gy + gz * gz)
                self.g_b = (gx / gn, gy / gn, gz / gn)
            if E >= cfg.e_start_deg and de >= cfg.v_start_dps:
                if self.quiet is not None and (t - self.quiet[1]) <= cfg.max_onset_lookback_s:
                    i_on, t_on, e_on = self.quiet
                else:
                    lb = int(round(0.2 * cfg.fs))
                    i_on, t_on, e_on = max(0, i - lb), t - lb * self.dt, max(0.0, E - de * 0.2)
                self._start_rising(i_on, t_on, e_on, E, i, theta_s, e_or > exc_elev)

        elif st == RISING:
            if E > self.e_peak:
                self.e_peak, self.i_peak, self.th_at_peak = E, i, theta_s
                self.route_orient = e_or > exc_elev
            rise = E - self.e_on
            ok_level = (rise >= cfg.rise_min_deg and E >= cfg.e_on_deg
                        and theta_s >= cfg.theta_dwell_min_deg)
            self.stable_n = self.stable_n + 1 if (ok_level and abs(de) < cfg.v_stable_dps) else 0
            peak_ok = ((self.e_peak - self.e_on) >= cfg.rise_min_deg and self.e_peak >= cfg.e_on_deg
                       and self.th_at_peak >= cfg.theta_dwell_min_deg)
            if self.stable_n * self.dt >= cfg.stable_hold_s - 1e-9:
                k0 = self.stable_n - 1
                self._enter_dwell(i - k0, t - k0 * self.dt, i, t, g, theta_s)
            elif peak_ok and (self.e_peak - E) >= cfg.drop_deg and de <= -cfg.v_drop_dps:
                # gesto rápido (toque y retirada): permanencia = meseta alrededor del pico
                ia, ta, ib, tb = self._near_peak_region()
                self.i_dw0, self.t_dw0, self.i_dw1, self.t_dw1 = ia, ta, ib, tb
                self.t_hold0 = ta
                self.quick = True
                self.state = FALLING
                self.e_min_fall, self.settle_n = E, 0
            elif (t - self.t_on) > cfg.raise_max_s:
                if ok_level:
                    self._enter_dwell(i, t, i, t, g, theta_s)
                else:
                    self.state = IDLE
                    self._clear_seg()
                    self.quiet = None
            elif de < -cfg.v_start_dps and rise < cfg.rise_min_deg:
                self.state = IDLE
                self._clear_seg()
                self.quiet = None

        elif st == DWELL:
            if E > self.e_peak:
                self.e_peak, self.i_peak, self.th_at_peak = E, i, theta_s
                self.route_orient = e_or > exc_elev
            # el fin de la permanencia se decide por ELEVACIÓN: los giros de la pipa (rotación sobre
            # el antebrazo) cambian ĝ pero no θ, y recostado dominan E; no deben cortar la permanencia
            if theta_s > self.th_dw_peak:
                self.th_dw_peak = theta_s
            if theta_s >= self.th_dw_peak - cfg.near_peak_deg:
                self.near_i, self.near_t = i, t
            th_drop = self.th_dw_peak - theta_s
            if abs(de) < cfg.settle_dps:
                self.q_run += 1
                self.q = (i, t, E, theta_s, (float(g[0]), float(g[1]), float(g[2])))
            else:
                if self.q_run > 0:
                    self.q_last = self.q_run
                self.q_run = 0
            if self._angle(g, self.fz_ref) > cfg.frozen_deg:
                self.fz_ref, self.fz_t0 = (float(g[0]), float(g[1]), float(g[2])), t
            if (t - self.fz_t0) >= cfg.frozen_s:
                # postura: orientación congelada (sin giros) -> se emite y se re-basa
                self.i_dw1, self.t_dw1 = i, t
                seg = self._emit(i, t, truncated=True, frozen=True)
                self._rebase(theta_s, g)
                self.state = IDLE
                self.quiet = None
            elif (self.q is not None and self.q_last * self.dt >= cfg.plateau_s
                  and de >= cfg.v_start_dps and (E - self.q[2]) >= cfg.rise_min_deg):
                # nueva elevación desde una meseta quieta: la meseta se emite y arranca otro gesto
                qi, qt, qe, qth, qg = self.q
                self.i_dw1, self.t_dw1 = qi, qt
                seg = self._make(qi, qt, truncated=True)
                self._rebase(qth, qg)
                E2, ee2, eo2 = self._excursion(theta_s, g)
                e_on2 = self._excursion(qth, qg)[0]
                self.e_prev, self.de = E2, de
                self._start_rising(qi, qt, e_on2, E2, i, theta_s, eo2 > ee2)
                self.quiet = None
            elif ((th_drop >= cfg.drop_deg and np.isfinite(dtheta) and dtheta <= -cfg.v_drop_dps)
                  or ((self.e_peak - E) >= cfg.drop_deg and de <= -cfg.v_drop_dps
                      and th_drop >= 0.5 * cfg.drop_deg)):
                self.state = FALLING
                self.i_dw1, self.t_dw1 = self.near_i, self.near_t
                self.e_min_fall, self.settle_n = E, 0
            elif (t - self.t_hold0) >= cfg.dwell_max_s:
                self.i_dw1, self.t_dw1 = i, t
                seg = self._emit(i, t, truncated=True)
                self.state = HOLD
                self.hold_n = 0
            elif (t - self.t_dw0) >= cfg.chunk_s:
                seg = self._chunk(i, t)

        elif st == FALLING:
            self.e_min_fall = min(self.e_min_fall, E)
            if E <= max(self.e_on + cfg.return_margin_deg, cfg.e_start_deg):
                seg = self._emit(i, t)
                self.state = IDLE
                self.quiet = None
            elif ((E - self.e_min_fall) >= cfg.reapproach_deg and de >= cfg.v_start_dps
                  and (t - self.t_dw1) <= cfg.reapproach_window_s):
                self.state = DWELL
                self.n_re += 1
                self.quick = False
                self.q_run, self.q_last, self.q = 0, 0, None
                self.fz_ref, self.fz_t0 = (float(g[0]), float(g[1]), float(g[2])), t
                self.near_i, self.near_t = i, t
            else:
                self.settle_n = self.settle_n + 1 if abs(de) < cfg.settle_dps else 0
                if ((t - self.t_dw1) >= cfg.lower_max_s or
                        (self.settle_n * self.dt >= cfg.settle_hold_s
                         and (self.e_peak - E) >= cfg.settle_min_drop_deg)):
                    seg = self._emit(i, t)
                    self._rebase(theta_s, g)
                    self.state = IDLE
                    self.quiet = None

        elif st == HOLD:
            # tras una permanencia que llegó a dwell_max: re-basar al quedar quieto
            self.hold_n = self.hold_n + 1 if abs(de) < cfg.settle_dps else 0
            if E <= cfg.e_start_deg or self.hold_n * self.dt >= cfg.hold_release_s:
                self._rebase(theta_s, g)
                self.state = IDLE
                self.quiet = None
        return seg, E, self.state


def segment_all(D: Derived, cfg: Config):
    sg = Segmenter(cfg)
    n = len(D.t)
    E = np.full(n, np.nan)
    S = np.zeros(n, np.int8)
    dE = np.full(n, np.nan)
    segs: List[Segment] = []
    for i in range(n):
        g = D.g_dev[i]
        seg, e, st = sg.step(i, float(D.t[i]), bool(D.valid[i]), float(D.theta_s[i]),
                             float(D.dtheta[i]) if np.isfinite(D.dtheta[i]) else NAN, g)
        E[i], S[i], dE[i] = e, st, sg.de
        if seg is not None:
            segs.append(seg)
    if n:
        last = sg.flush(n - 1, float(D.t[-1]))
        if last is not None:
            segs.append(last)
    return segs, {"E": E, "state": S, "dE": dE}


# =============================================================================
# L4 — Features por segmento
# =============================================================================
TARGET_CLASSES = ("TARGET_PIPE_HAND", "TARGET_LIGHTER_HAND")
CONFOUNDER_CLASSES = ("DRINK", "EAT", "SMOKE_OTHER", "FACE_TOUCH", "WATCH_CHECK", "PHONE")
ALL_CLASSES = TARGET_CLASSES + CONFOUNDER_CLASSES + ("OTHER",)
DTW_CLASSES = TARGET_CLASSES + CONFOUNDER_CLASSES
AUDIO_CLASSES = ("lighter_click", "flame", "inhale", "exhale", "cough", "speech", "chew",
                 "swallow", "object")

BASE_FEATURES = (
    "t_raise", "t_dwell", "t_lower", "t_total", "dwell_frac", "n_reapproach", "truncated", "quick",
    "route_orient", "continued", "frozen", "valid_frac", "gyro_frac",
    "th_base", "th_peak", "th_dw_mean", "th_dw_std", "th_dw_slope", "d_th", "e_peak", "e_rise",
    "v_up", "v_down",
    "phi_dw_sin", "phi_dw_cos", "phi_dw_R", "phi_dw_range", "d_phi_raise", "w_phi_dw",
    "alin_rms_dw", "alin_p95_dw", "still_frac_dw",
    "n_roll_steps", "roll_step_rate", "roll_step_amp", "roll_path_rate", "th_dw_iqr", "roll_decouple",
    "rr_std_dw", "rr_dom_f", "rr_prom",
    "gyr_rms_dw", "wf_rms_dw", "wf_zcr_dw", "wf_dom_f", "wf_prom",
    "alin_bp_lo", "alin_bp_hi", "alin_spec_ent", "micro_rate",
    "n_spikes", "spike_rate", "jerk_zmax", "t_first_spike_rel",
    "act_pre", "still_pre", "screen_on",
)
FEATURE_NAMES = (BASE_FEATURES + tuple(f"dtw_{c}" for c in DTW_CLASSES) + ("audio_avail",)
                 + tuple(f"au_{c}" for c in AUDIO_CLASSES))


def roll_track(D: Derived, sl: slice, cfg: Config) -> Optional[np.ndarray]:
    """Ángulo de giro sobre el antebrazo (deg) dentro de sl: ∫ω_f con sesgo residual quitado si hay
    giroscopio; si no, φ de gravedad (solo si w_φ >= rr_min_wphi). None si no es observable."""
    fs = cfg.fs
    if sl.stop - sl.start < 3:
        return None
    gv = D.gyro_valid[sl]
    if gv.size and gv.mean() >= 0.8:
        wf = np.where(np.isfinite(D.w_A[sl, 0]), D.w_A[sl, 0], 0.0)
        st = D.still[sl] & gv
        bias = float(np.median(wf[st])) if st.sum() >= int(fs) else 0.0
        return np.degrees(np.cumsum(wf - bias) / fs)
    w = _nanmean(D.w_phi[sl])
    if np.isfinite(w) and w >= cfg.rr_min_wphi:
        return _unwrap_deg(D.phi[sl])
    return None


def roll_steps(r: np.ndarray, fs: float, thr: float, win_s: float, sep_s: float):
    """Escalones de giro: picos de |r(t) − r(t − win)| >= thr separados >= sep. Devuelve (índices, amplitudes)."""
    k = max(1, int(round(win_s * fs)))
    if len(r) <= k:
        return [], []
    d = np.abs(r[k:] - r[:-k])
    pk = _peaks(np.concatenate([[0.0], d, [0.0]]), thr, max(1, int(round(sep_s * fs))))
    return [p - 1 + k for p in pk], [float(d[p - 1]) for p in pk]


def segment_trajectory(seg: Segment, D: Derived, npts: int) -> np.ndarray:
    """Trayectoria (θ/90, w·sinφ, w·cosφ) del gesto, re-muestreada a npts puntos."""
    sl = slice(seg.i_on, seg.i_end + 1)
    ph = np.radians(D.phi[sl])
    w = D.w_phi[sl]
    X = np.column_stack([D.theta_s[sl] / 90.0, w * np.sin(ph), w * np.cos(ph)])
    X = np.column_stack([_interp_nans(X[:, k]) for k in range(3)])
    X = np.nan_to_num(X, nan=0.0)
    m = len(X)
    if m == 0:
        return np.zeros((npts, 3))
    if m == 1:
        return np.repeat(X, npts, axis=0)
    src = np.linspace(0.0, 1.0, m)
    dst = np.linspace(0.0, 1.0, npts)
    return np.column_stack([np.interp(dst, src, X[:, k]) for k in range(3)])


def dtw_distance(A, B, band_frac: float = 0.2) -> float:
    """DTW con banda Sakoe-Chiba; distancia euclídea por punto; normalizada por (n+m)."""
    A = np.asarray(A, float)
    B = np.asarray(B, float)
    n, m = len(A), len(B)
    w = max(int(math.ceil(band_frac * max(n, m))), abs(n - m))
    C = np.sqrt(((A[:, None, :] - B[None, :, :]) ** 2).sum(-1))
    D = np.full((n + 1, m + 1), np.inf)
    D[0, 0] = 0.0
    for i in range(1, n + 1):
        for j in range(max(1, i - w), min(m, i + w) + 1):
            D[i, j] = C[i - 1, j - 1] + min(D[i - 1, j], D[i, j - 1], D[i - 1, j - 1])
    return float(D[n, m] / (n + m))


class TemplateBank:
    """Plantillas por clase. Confusores: del propio usuario (calibración neutra).
    Clases objetivo: SOLO de datos poblacionales (simulación con utilería por
    personas sin historial de consumo) — nunca pedir al usuario recrear el ritual."""

    def __init__(self, max_per_class: int = 3):
        self.max_per_class = max_per_class
        self.t: Dict[str, List[np.ndarray]] = {}

    def add(self, cls: str, traj) -> None:
        lst = self.t.setdefault(cls, [])
        if len(lst) < self.max_per_class:
            lst.append(np.asarray(traj, float))

    def min_distance(self, cls: str, traj, band: float) -> float:
        lst = self.t.get(cls)
        if not lst:
            return NAN
        return min(dtw_distance(traj, T, band) for T in lst)

    def to_json(self) -> dict:
        return {k: [v.tolist() for v in vs] for k, vs in self.t.items()}


def extract_features(seg: Segment, D: Derived, cfg: Config, templates: Optional[TemplateBank] = None,
                     audio: Optional[Callable[[Segment], Optional[dict]]] = None,
                     screen_on_s: Optional[np.ndarray] = None) -> Dict[str, float]:
    fs = cfg.fs
    n = len(D.t)
    f: Dict[str, float] = {}
    i_on, i0, i1, ie = seg.i_on, seg.i_dw0, seg.i_dw1, seg.i_end
    SEG = slice(i_on, ie + 1)
    DW = slice(i0, i1 + 1)
    RA = slice(i_on, i0 + 1)
    LO = slice(i1, ie + 1)

    def win(a, b):
        a, b = max(0, a), min(n, b)
        return slice(a, max(a, b))

    # --- tiempos ---
    f["t_raise"] = (i0 - i_on) / fs
    f["t_dwell"] = (i1 - i0) / fs
    f["t_lower"] = (ie - i1) / fs
    f["t_total"] = (ie - i_on) / fs
    f["dwell_frac"] = f["t_dwell"] / max(f["t_total"], 1e-9)
    f["n_reapproach"] = float(seg.n_reapproach)
    f["truncated"] = float(seg.truncated)
    f["quick"] = float(seg.quick)
    f["route_orient"] = float(seg.route_orient)
    f["continued"] = float(seg.chunk > 0)
    f["frozen"] = float(seg.frozen)
    f["valid_frac"] = float(np.mean(D.valid[SEG]))
    f["gyro_frac"] = float(np.mean(D.gyro_valid[SEG]))
    # --- elevación ---
    th = D.theta_s
    f["th_base"] = float(seg.theta_base)
    f["th_peak"] = _nanmax(th[SEG])
    f["th_dw_mean"] = _nanmean(th[DW])
    f["th_dw_std"] = _nanstd(th[DW])
    f["th_dw_slope"] = _slope(th[DW], fs)
    f["d_th"] = f["th_peak"] - f["th_base"]
    f["e_peak"] = float(seg.e_peak)
    f["e_rise"] = float(seg.e_peak - seg.e_onset)
    f["v_up"] = _nanmax(D.dtheta[RA])
    vmin = _nanmin(D.dtheta[LO])
    f["v_down"] = -vmin if np.isfinite(vmin) else NAN
    # --- giro sobre el antebrazo (φ) ---
    wmean = _nanmean(D.w_phi[DW])
    ok_phi = np.isfinite(wmean) and wmean >= 0.2
    s_, c_, R_ = _circ_mean(D.phi[DW], D.w_phi[DW]) if ok_phi else (NAN, NAN, NAN)
    f["phi_dw_sin"], f["phi_dw_cos"], f["phi_dw_R"] = s_, c_, R_
    f["w_phi_dw"] = wmean
    f["phi_dw_range"] = _circ_range_deg(D.phi[DW]) if ok_phi else NAN
    k5 = int(round(0.5 * fs))
    a1 = _circ_angle(D.phi[win(i0, i0 + k5 + 1)], D.w_phi[win(i0, i0 + k5 + 1)])
    a0 = _circ_angle(D.phi[win(i_on - k5, i_on + 1)], D.w_phi[win(i_on - k5, i_on + 1)])
    f["d_phi_raise"] = _wrap180(a1 - a0) if (ok_phi and np.isfinite(a1) and np.isfinite(a0)) else NAN
    # --- microdinámica de la permanencia ---
    al = D.alin[DW]
    f["alin_rms_dw"] = _rms(al)
    f["alin_p95_dw"] = _nanpct(al, 95)
    f["still_frac_dw"] = float(np.mean(D.still[DW])) if i1 >= i0 else NAN
    long_dw = f["t_dwell"] >= cfg.min_spectral_dwell_s
    # escalones de giro con la elevación estable (firma medida en las sesiones reales)
    thd = _finite(th[DW])
    f["th_dw_iqr"] = float(np.percentile(thd, 75) - np.percentile(thd, 25)) if thd.size >= 3 else NAN
    rt = roll_track(D, DW, cfg) if f["t_dwell"] >= cfg.roll_step_win_s else None
    if rt is not None:
        _, amps = roll_steps(rt, fs, cfg.roll_step_deg, cfg.roll_step_win_s, cfg.roll_step_sep_s)
        f["n_roll_steps"] = float(len(amps))
        f["roll_step_rate"] = len(amps) / max(f["t_dwell"], 1e-9) * 60.0
        f["roll_step_amp"] = float(np.median(amps)) if amps else 0.0
        k2 = max(1, int(round(0.5 * fs)))
        rs = rt[::k2]
        path = float(np.sum(np.maximum(0.0, np.abs(np.diff(rs)) - 2.0))) if rs.size > 1 else 0.0
        f["roll_path_rate"] = path / max(f["t_dwell"], 1e-9)
        th_rng = (float(np.percentile(thd, 90) - np.percentile(thd, 10)) if thd.size >= 3 else 0.0)
        f["roll_decouple"] = float(np.percentile(rt, 90) - np.percentile(rt, 10)) / (th_rng + 5.0)
    else:
        for k in ("n_roll_steps", "roll_step_rate", "roll_step_amp", "roll_path_rate", "roll_decouple"):
            f[k] = NAN
    # giro con acc crudo: solo si la gravedad proyecta lo suficiente sobre el plano (l,d);
    # con antebrazo casi vertical la aceleración lineal domina y el ángulo pierde sentido
    ok_rr = np.isfinite(wmean) and wmean >= cfg.rr_min_wphi
    f["rr_std_dw"] = _circ_std_deg(D.roll_raw[DW]) if ok_rr else NAN
    if long_dw and ok_rr:
        fq, P = _spectrum(_unwrap_deg(D.roll_raw[DW]), fs)
        f["rr_dom_f"], f["rr_prom"] = _dom_peak(fq, P, cfg.band_lo_hz, cfg.band_hi_hz)
    else:
        f["rr_dom_f"] = f["rr_prom"] = NAN
    gfrac_dw = float(np.mean(D.gyro_valid[DW])) if i1 >= i0 else 0.0
    if gfrac_dw >= 0.8:
        wf = D.w_A[DW, 0]
        f["gyr_rms_dw"] = _rms(D.wnorm[DW])
        f["wf_rms_dw"] = _nanstd(wf)
        f["wf_zcr_dw"] = _zcr(wf, fs, 0.05)
        if long_dw:
            fq, P = _spectrum(wf, fs)
            f["wf_dom_f"], f["wf_prom"] = _dom_peak(fq, P, cfg.band_lo_hz, cfg.band_hi_hz)
        else:
            f["wf_dom_f"] = f["wf_prom"] = NAN
    else:
        for k in ("gyr_rms_dw", "wf_rms_dw", "wf_zcr_dw", "wf_dom_f", "wf_prom"):
            f[k] = NAN
    if long_dw:
        fq, P = _spectrum(al, fs)
        if fq is not None:
            tot_m = fq >= cfg.band_lo_hz
            tot = float(P[tot_m].sum())
            if tot > 0:
                f["alin_bp_lo"] = float(P[(fq >= cfg.band_lo_hz) & (fq < cfg.split_hz)].sum()) / tot
                f["alin_bp_hi"] = float(P[fq >= cfg.split_hz].sum()) / tot
            else:
                f["alin_bp_lo"] = f["alin_bp_hi"] = NAN
            f["alin_spec_ent"] = _spec_entropy(P[tot_m])
        else:
            f["alin_bp_lo"] = f["alin_bp_hi"] = f["alin_spec_ent"] = NAN
    else:
        f["alin_bp_lo"] = f["alin_bp_hi"] = f["alin_spec_ent"] = NAN
    sep = max(1, int(round(0.2 * fs)))
    pk = (_peaks(D.wnorm[DW], cfg.micro_gyro_thr, sep) if gfrac_dw >= 0.8
          else _peaks(al, cfg.micro_acc_thr, sep))
    f["micro_rate"] = len(pk) / max(f["t_dwell"], 1.0 / fs)
    # --- impulsos (jerk robusto) ---
    ctx = win(i_on - int(cfg.pre_ctx_s * fs), ie + int(cfg.post_ctx_s * fs) + 1)
    jc = _finite(D.jerk[ctx])
    if jc.size >= 5:
        med = float(np.median(jc))
        mad = float(np.median(np.abs(jc - med))) * 1.4826 + 1e-6
        pad = int(round(cfg.spike_pad_s * fs))
        w0, w1 = max(0, i0 - pad), min(n, i1 + pad + 1)
        z = (D.jerk[w0:w1] - med) / mad
        spk = _peaks(z, cfg.spike_z, max(1, int(round(cfg.spike_min_sep_s * fs))))
        f["n_spikes"] = float(len(spk))
        f["spike_rate"] = len(spk) / max((w1 - w0) / fs, 1e-9)
        f["jerk_zmax"] = _nanmax(z)
        f["t_first_spike_rel"] = (((w0 + spk[0]) - i0) / max(i1 - i0, 1)) if spk else NAN
    else:
        f["n_spikes"] = f["spike_rate"] = f["jerk_zmax"] = f["t_first_spike_rel"] = NAN
    # --- contexto ---
    f["act_pre"] = _nanmean(D.alin[win(i_on - int(cfg.act_pre_s * fs), i_on)])
    sp = win(i_on - int(cfg.still_pre_s * fs), i_on)
    f["still_pre"] = float(np.mean(D.still[sp])) if (sp.stop - sp.start) > 0 else NAN
    if screen_on_s is None:
        f["screen_on"] = NAN
    else:
        so = np.asarray(screen_on_s, float)
        f["screen_on"] = float(np.any((so >= seg.t_on) & (so <= seg.t_dw1 + 0.5)))
    # --- plantillas (DTW) ---
    traj = segment_trajectory(seg, D, cfg.dtw_points) if templates is not None else None
    for c in DTW_CLASSES:
        f[f"dtw_{c}"] = templates.min_distance(c, traj, cfg.dtw_band) if templates is not None else NAN
    # --- audio opcional (clasificador externo; solo probabilidades, nunca audio crudo) ---
    au = audio(seg) if audio is not None else None
    f["audio_avail"] = 1.0 if au else 0.0
    for c in AUDIO_CLASSES:
        f[f"au_{c}"] = float(au[c]) if (au and c in au) else NAN
    return f


# =============================================================================
# L5 — Clasificador de gesto
# =============================================================================
class BootstrapScorer:
    """Prior heurístico SIN VALIDAR para el arranque en frío, recalibrado (v1.1) con la firma medida
    en las sesiones reales: permanencia larga, elevación estable, giros discretos, a_lin baja y
    ganancia de elevación desde el reposo. No usa la altura absoluta (recostado la cara está a θ ≈ 10°).
    Permitido: ordenar candidatos para revisión con video / muestreo de experiencia.
    PROHIBIDO: disparar avisos al usuario (DecisionPolicy lo bloquea: released=False)."""
    released = False
    name = "bootstrap-heuristic-v1.1"

    def predict(self, f: Dict[str, float]):
        def g(k):
            v = f.get(k, NAN)
            return v if np.isfinite(v) else None
        z = -3.2
        td = g("t_dwell")
        if td is not None:
            z += 1.0 * min(1.5, max(0.0, (td - 3.0) / 8.0))
        ns = g("n_roll_steps")
        if ns is not None:
            z += 0.6 * min(2.0, ns)
        iqr = g("th_dw_iqr")
        if iqr is not None and iqr < 4.0:
            z += 0.4
        ar = g("alin_rms_dw")
        if ar is not None:
            z += 0.5 if ar < 0.6 else (-0.8 if ar > 1.5 else 0.0)
        dth = g("d_th")
        if dth is not None:
            z += 0.6 if dth >= 20.0 else (-0.6 if dth < 8.0 else 0.0)
        th = g("th_dw_mean")
        if th is not None and th < -30.0:
            z -= 1.5                  # brazo colgando
        pc = g("phi_dw_cos")
        if th is not None and pc is not None and th < 25.0 and pc > 0.85 and (td or 0.0) < 5.0:
            z -= 1.5                  # patrón "ver el reloj"
        if g("screen_on") == 1.0:
            z -= 1.5
        if g("frozen") == 1.0:
            z -= 1.0                  # orientación congelada: postura
        p = 1.0 / (1.0 + math.exp(-z))
        return p, _logit(p), False


class NoveltyGate:
    """Distancia al k-ésimo vecino (espacio estandarizado). Umbral = cuantil q de
    las distancias leave-one-out del entrenamiento. Novedoso -> evidencia positiva x0.5."""

    def __init__(self, k: int = 5, q: float = 0.99):
        self.k, self.q = k, q
        self.thr = math.inf

    def fit(self, X):
        X = np.asarray(X, float)
        self.cols = np.where(np.isfinite(X).any(axis=0))[0]
        Xc = X[:, self.cols]
        self.med = np.nanmedian(Xc, axis=0)
        Xi = np.where(np.isfinite(Xc), Xc, self.med)
        self.mu = Xi.mean(axis=0)
        self.sd = Xi.std(axis=0) + 1e-6
        self.Z = (Xi - self.mu) / self.sd
        n = len(self.Z)
        if n <= self.k:
            return self
        sq = (self.Z ** 2).sum(1)
        D2 = np.maximum(sq[:, None] + sq[None, :] - 2.0 * self.Z @ self.Z.T, 0.0)
        np.fill_diagonal(D2, np.inf)
        dk = np.sqrt(np.partition(D2, self.k - 1, axis=1)[:, self.k - 1])
        self.thr = float(np.quantile(dk, self.q))
        return self

    def score(self, x) -> float:
        x = np.asarray(x, float)[self.cols]
        z = (np.where(np.isfinite(x), x, self.med) - self.mu) / self.sd
        d = np.sqrt(((self.Z - z) ** 2).sum(1))
        k = min(self.k, len(d))
        return float(np.partition(d, k - 1)[k - 1])

    def is_novel(self, x) -> bool:
        return self.score(x) > self.thr


class GestureModel:
    """GBDT binario (objetivo vs resto) + calibración isotónica fuera de pliegue +
    compuerta de novedad. LLR = logit(p_cal) - logit(prior_entrenamiento):
    elimina el prior del entrenamiento para que L6 use evidencia pura.
    released=True SOLO cuando la versión pasó las compuertas de liberación."""
    name = "gbdt-binary-isotonic"

    def __init__(self, clf, iso, prior: float, names, novelty: NoveltyGate):
        self.clf, self.iso, self.prior = clf, iso, float(prior)
        self.names = tuple(names)
        self.novelty = novelty
        self.released = False

    def vector(self, f: Dict[str, float]) -> np.ndarray:
        return np.array([f.get(k, NAN) for k in self.names], float)

    def predict(self, f: Dict[str, float]):
        x = self.vector(f)
        raw = float(self.clf.predict_proba(x[None, :])[0, 1])
        p = float(np.clip(self.iso.predict(np.array([raw]))[0], 1e-3, 1 - 1e-3))
        return p, _logit(p) - _logit(self.prior), bool(self.novelty.is_novel(x))


def _ece(p, y, bins: int = 10) -> float:
    p = np.asarray(p, float)
    y = np.asarray(y, float)
    e = 0.0
    edges = np.linspace(0.0, 1.0, bins + 1)
    for j in range(bins):
        a, b = edges[j], edges[j + 1]
        m = (p >= a) & ((p < b) if j < bins - 1 else (p <= b))
        if m.any():
            e += m.mean() * abs(p[m].mean() - y[m].mean())
    return float(e)


def train_gesture_model(F: List[Dict[str, float]], labels: List[str], groups, cfg: Config,
                        names=FEATURE_NAMES, seed: int = 0):
    """Entrena con GroupKFold por SESIÓN (o por PERSONA cuando haya varias).
    Plantillas DTW deben venir de datos FUERA de estos pliegues."""
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.isotonic import IsotonicRegression
    from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score
    from sklearn.model_selection import GroupKFold

    X_all = np.array([[f.get(k, NAN) for k in names] for f in F], float)

    def usable(M):
        return np.array([np.unique(c[np.isfinite(c)]).size >= 2 for c in M.T], bool)

    keep = usable(X_all)                       # columnas sin información (p. ej. audio ausente) fuera
    names = tuple(n_ for n_, k in zip(names, keep) if k)
    X = X_all[:, keep]
    y = np.array([lbl in TARGET_CLASSES for lbl in labels], int)
    groups = np.asarray(groups)

    def make():
        return HistGradientBoostingClassifier(max_depth=4, learning_rate=0.06, max_iter=250,
                                              min_samples_leaf=8, l2_regularization=1.0,
                                              class_weight="balanced", random_state=seed)

    nf = max(2, min(5, len(np.unique(groups))))
    oof = np.full(len(y), np.nan)
    for tr, te in GroupKFold(n_splits=nf).split(X, y, groups):
        if y[tr].min() == y[tr].max():
            continue
        cu = usable(X[tr])
        oof[te] = make().fit(X[tr][:, cu], y[tr]).predict_proba(X[te][:, cu])[:, 1]
    ok = np.isfinite(oof)
    iso = IsotonicRegression(out_of_bounds="clip", y_min=1e-3, y_max=1 - 1e-3).fit(oof[ok], y[ok])
    clf = make().fit(X, y)
    model = GestureModel(clf, iso, float(y.mean()), names, NoveltyGate(cfg.novelty_k, cfg.novelty_quantile).fit(X))
    pc = iso.predict(oof[ok])
    lab = np.asarray(labels)[ok]
    fp_by_class = {c: int(((pc >= 0.5) & (lab == c)).sum()) for c in ALL_CLASSES if c not in TARGET_CLASSES}
    report = {
        "n": int(len(y)), "n_target": int(y.sum()), "folds": nf, "n_features_used": len(names),
        "oof_ap": float(average_precision_score(y[ok], pc)),
        "oof_roc_auc": float(roc_auc_score(y[ok], pc)) if len(np.unique(y[ok])) > 1 else NAN,
        "oof_brier": float(brier_score_loss(y[ok], pc)),
        "oof_ece": _ece(pc, y[ok]),
        "oof_false_pos_by_class_at_0.5": fp_by_class,
        "oof_recall_at_0.5": float(((pc >= 0.5) & (y[ok] == 1)).sum() / max(1, (y[ok] == 1).sum())),
    }
    return model, report


# =============================================================================
# L6 — Modelo de episodio (CUSUM con olvido)
# =============================================================================
@dataclass
class GestureEvent:
    t: float          # momento en que el gesto se conoce (fin del segmento) — causal
    p: float          # P(objetivo | x) calibrada
    llr: float        # log-razón de verosimilitud
    novel: bool
    seg: Segment


class EpisodeDetector:
    """S_i = max(0, S_{i-1}·exp(-(t_i - t_{i-1})/τ) + clip(LLR_i) - κ)
    CANDIDATO si S >= h_candidate; AVISO si S >= h_alert y al menos k PERMANENCIAS DISTINTAS
    (hold_id) con p >= kofn_p en la ventana; fin de episodio tras episode_end_s sin evidencia
    positiva. Refractario entre avisos. h se ajusta con horas de datos NEGATIVOS
    para cumplir el presupuesto de falsas alarmas por día.
    v1.1: los fragmentos de una misma permanencia no son evidencia independiente: su LLR positiva
    cuenta × continued_llr_factor y, para k-de-n, toda la permanencia cuenta como un solo gesto
    (si no, una sola permanencia larga —p. ej. una llamada— cumpliría k = 2 por sí sola)."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.S = 0.0
        self.t_last: Optional[float] = None
        self.recent: deque = deque()
        self.ep: Optional[dict] = None
        self.n_ep = 0
        self.last_alert_t = -math.inf

    def _event(self, state: str, t: float) -> dict:
        ep = self.ep
        return {
            "type": "episode", "state": state, "episode_id": ep["episode_id"], "t": float(t),
            "S": float(self.S), "t_start": ep["t_start"], "t_last": ep["t_last_pos"],
            "n_gestures": len(ep["gestures"]), "max_p": max([g["p"] for g in ep["gestures"]] or [0.0]),
            "evidence": {"gesture_llr_sum": float(sum(g["llr"] for g in ep["gestures"])),
                         "physio_llr": 0.0},
            "alerted": ep["alerted"], "label": "posible patrón asociado", "shareable": False,
        }

    def tick(self, t: float) -> List[dict]:
        out = []
        if self.ep is not None and (t - self.ep["t_last_pos"]) >= self.cfg.episode_end_s:
            out.append(self._event("END", self.ep["t_last_pos"]))
            self.ep = None
        return out

    def update(self, ev: GestureEvent) -> List[dict]:
        cfg = self.cfg
        out = self.tick(ev.t)
        if self.t_last is not None and ev.t > self.t_last:
            self.S *= math.exp(-(ev.t - self.t_last) / cfg.cusum_tau_mem_s)
        self.t_last = ev.t
        llr = min(max(ev.llr, cfg.llr_clip_lo), cfg.llr_clip_hi)
        if ev.novel and llr > 0:
            llr *= cfg.novelty_llr_factor
        if ev.seg.chunk > 0 and llr > 0:
            llr *= cfg.continued_llr_factor       # misma permanencia: no es evidencia independiente
        self.S = max(0.0, self.S + llr - cfg.cusum_kappa)
        self.recent.append((ev.t, ev.p, llr, ev.seg.hold_id))
        while self.recent and self.recent[0][0] < ev.t - cfg.kofn_window_s:
            self.recent.popleft()
        if self.ep is None and self.S >= cfg.h_candidate:
            self.n_ep += 1
            pos = [r for r in self.recent if r[2] > 0]
            self.ep = {"episode_id": self.n_ep, "t_start": float(pos[0][0] if pos else ev.t),
                       "gestures": [{"t": r[0], "p": r[1], "llr": r[2]} for r in pos],
                       "alerted": False, "t_last_pos": float(ev.t)}
            out.append(self._event("CANDIDATE", ev.t))
        elif self.ep is not None and llr > 0:
            self.ep["gestures"].append({"t": ev.t, "p": ev.p, "llr": llr})
            self.ep["t_last_pos"] = float(ev.t)
        if self.ep is not None and not self.ep["alerted"]:
            n_hi = len({r[3] for r in self.recent if r[1] >= cfg.kofn_p})   # permanencias distintas
            if (self.S >= cfg.h_alert and n_hi >= cfg.kofn_k
                    and (ev.t - self.last_alert_t) >= cfg.refractory_s):
                self.ep["alerted"] = True
                self.last_alert_t = ev.t
                out.append(self._event("ALERT", ev.t))
        return out

    def flush(self, t_final: float) -> List[dict]:
        return self.tick(t_final + self.cfg.episode_end_s + 1.0)


# =============================================================================
# L7 — Corroboración fisiológica lenta (apagada por defecto)
# =============================================================================
class ActivityAdjustedHR:
    """FC esperada = β·[1, A1, A5, A15, sin(2πh/24), cos(2πh/24)] (ridge), con A_k =
    media de |a_lin| en los últimos k minutos. Se ajusta con datos del PROPIO usuario
    en quietud, excluyendo episodios confirmados. El residuo (FC no explicada por
    movimiento ni hora) es la señal lenta. Inespecífica (estrés, cafeína, fiebre):
    solo modula confianza, nunca dispara. physio_weight = 0 hasta validarse."""

    def __init__(self, lam: float = 1.0):
        self.lam = lam
        self.beta: Optional[np.ndarray] = None

    @staticmethod
    def design(a1, a5, a15, hour) -> np.ndarray:
        h = 2.0 * math.pi * np.asarray(hour, float) / 24.0
        a1 = np.asarray(a1, float)
        return np.column_stack([np.ones_like(a1), a1, a5, a15, np.sin(h), np.cos(h)])

    def fit(self, X, y):
        X = np.asarray(X, float)
        y = np.asarray(y, float)
        m = np.all(np.isfinite(X), axis=1) & np.isfinite(y)
        X, y = X[m], y[m]
        P = self.lam * np.eye(X.shape[1])
        P[0, 0] = 0.0
        self.beta = np.linalg.solve(X.T @ X + P, X.T @ y)
        return self

    def residual(self, X, y) -> np.ndarray:
        return np.asarray(y, float) - np.asarray(X, float) @ self.beta


# =============================================================================
# L8 — Política de decisión
# =============================================================================
class DecisionPolicy:
    """Acciones permitidas: LOG, CHECKIN_PROMPT (voluntario y privado),
    SUGGEST_SUPPORT (configurado por el usuario). Restricciones duras:
      - nunca estímulos físicos/aversivos ni sanciones automáticas;
      - nunca compartir eventos no confirmados por el usuario;
      - sin avisos si el modelo no está liberado (released=False)."""
    ALLOWED = ("LOG", "CHECKIN_PROMPT", "SUGGEST_SUPPORT")

    def __init__(self, cfg: Config, alerts_enabled: bool):
        self.cfg = cfg
        self.alerts_enabled = alerts_enabled
        self.prompt_times: deque = deque()

    def on_event(self, ev: dict) -> List[dict]:
        cfg = self.cfg
        acts = [{"action": "LOG", "episode_id": ev.get("episode_id"), "state": ev.get("state"),
                 "t": ev.get("t")}]
        if ev.get("state") == "ALERT" and self.alerts_enabled and cfg.prompts_enabled:
            t = float(ev["t"])
            while self.prompt_times and self.prompt_times[0] < t - 86400.0:
                self.prompt_times.popleft()
            spaced = (not self.prompt_times) or (t - self.prompt_times[-1] >= cfg.min_prompt_interval_s)
            if spaced and len(self.prompt_times) < cfg.max_prompts_per_day:
                acts.append({"action": "CHECKIN_PROMPT", "episode_id": ev["episode_id"], "t": t,
                             "options": ["CONFIRMAR", "NO", "PREFIERO_NO_RESPONDER"]})
                self.prompt_times.append(t)
        for a in acts:
            if a["action"] not in self.ALLOWED:
                raise RuntimeError(f"acción no permitida: {a['action']}")
        return acts

    @staticmethod
    def can_share(record: dict) -> bool:
        return record.get("user_response") == "CONFIRM" and bool(record.get("user_opted_in_sharing"))


# =============================================================================
# Pipeline
# =============================================================================
def run_pipeline(st: Streams, cfg: Config, mount: Mounting, model=None,
                 templates: Optional[TemplateBank] = None, audio=None,
                 t0_ns: Optional[int] = None, t1_ns: Optional[int] = None) -> dict:
    t0 = int(np.min(st.acc_t_ns)) if t0_ns is None else int(t0_ns)
    t1 = int(np.max(st.acc_t_ns)) if t1_ns is None else int(t1_ns)
    t, acc, acc_ok = resample_uniform(st.acc_t_ns, st.acc, cfg.fs, t0, t1, cfg.max_interp_gap_s)
    if st.gyr is not None and st.gyr_t_ns is not None and len(st.gyr_t_ns) > 1:
        _, gyr, gyr_ok = resample_uniform(st.gyr_t_ns, st.gyr, cfg.fs, t0, t1, cfg.max_interp_gap_s)
    else:
        gyr, gyr_ok = None, None
    D = derive(t, acc, acc_ok, gyr, gyr_ok, cfg, mount)
    segs, trace = segment_all(D, cfg)
    so = None if st.screen_on_t_ns is None else (np.asarray(st.screen_on_t_ns, np.int64) - t0) * 1e-9
    feats = [extract_features(s, D, cfg, templates, audio, so) for s in segs]
    scorer = model if model is not None else BootstrapScorer()
    gestures = []
    for s, f in zip(segs, feats):
        p, llr, novel = scorer.predict(f)
        gestures.append(GestureEvent(t=s.t_end, p=p, llr=llr, novel=novel, seg=s))
    epi = EpisodeDetector(cfg)
    pol = DecisionPolicy(cfg, alerts_enabled=bool(getattr(scorer, "released", False)))
    events, actions = [], []
    for gev in gestures:
        for e in epi.update(gev):
            events.append(e)
            actions += pol.on_event(e)
    for e in epi.flush(float(t[-1]) if len(t) else 0.0):
        events.append(e)
        actions += pol.on_event(e)
    physio = None
    if st.hr_bpm is not None and st.hr_ok is not None:
        hv = hr_valid_mask(st.hr_bpm, st.hr_ok, cfg)
        physio = {"hr_n": int(len(hv)), "hr_valid": int(hv.sum()),
                  "hr_valid_frac": float(hv.mean()) if len(hv) else NAN}
    return {"version": VERSION, "t0_ns": t0, "t": t, "derived": D, "trace": trace, "segments": segs,
            "features": feats, "gestures": gestures, "episodes": events, "actions": actions,
            "scorer": getattr(scorer, "name", "custom"), "physio": physio}


# =============================================================================
# Simulador cinemático (SOLO para pruebas de plomería, no para estimar desempeño)
# =============================================================================
GESTURE_SPECS = {
    # Hipótesis de forma, NO mediciones. Sirven para ejercitar el código.
    # v1.1: permanencias de 8–40 s con giros discretos (medido en las sesiones reales del Watch7)
    "TARGET_PIPE_HAND":    dict(theta=(45, 62), phi=(-60, -15), raise_=(0.6, 1.3), dwell=(8.0, 40.0), lower=(0.6, 1.3)),
    "TARGET_LIGHTER_HAND": dict(theta=(35, 55), phi=(20, 70), raise_=(0.6, 1.3), dwell=(3.0, 10.0), lower=(0.6, 1.3)),
    "SMOKE_OTHER":         dict(theta=(45, 70), phi=(60, 110), raise_=(0.5, 1.0), dwell=(1.0, 2.5), lower=(0.5, 1.0)),
    "DRINK":               dict(theta=(40, 60), phi=(70, 100), raise_=(0.6, 1.2), dwell=(2.0, 5.0), lower=(0.6, 1.2)),
    "EAT":                 dict(theta=(40, 65), phi=(30, 90), raise_=(0.4, 0.8), dwell=(0.3, 0.8), lower=(0.4, 0.8)),
    "FACE_TOUCH":          dict(theta=(40, 75), phi=(40, 120), raise_=(0.5, 1.0), dwell=(1.0, 4.0), lower=(0.5, 1.0)),
    "WATCH_CHECK":         dict(theta=(5, 20), phi=(-15, 15), raise_=(0.4, 0.8), dwell=(1.0, 3.0), lower=(0.4, 0.8)),
    "PHONE":               dict(theta=(60, 80), phi=(70, 110), raise_=(0.8, 1.5), dwell=(20.0, 50.0), lower=(0.8, 1.5)),
}


def _minjerk(tau):
    tau = np.clip(tau, 0.0, 1.0)
    return tau ** 3 * (10.0 - 15.0 * tau + 6.0 * tau ** 2)


def _max_len(typ: str) -> float:
    sp = GESTURE_SPECS[typ]
    return sp["raise_"][1] + sp["dwell"][1] + sp["lower"][1]


def make_plan(rng, duration_s: float, with_target: bool, role: str, posture: str = "sitting") -> dict:
    items = []
    if with_target:
        items.append(("cluster", int(rng.integers(4, 9))))
    if rng.random() < 0.6:
        items.append(("meal", None))
    if rng.random() < 0.5:
        items.append(("tobacco", None))
    if posture == "sitting":
        for _ in range(int(rng.integers(1, 3))):
            items.append(("walk", None))
    for typ, lo, hi in (("FACE_TOUCH", 3, 9), ("WATCH_CHECK", 2, 6), ("DRINK", 1, 4)):
        for _ in range(int(rng.integers(lo, hi))):
            items.append(("single", typ))
    if rng.random() < 0.5:
        items.append(("single", "PHONE"))
    t = float(rng.uniform(20, 60))
    gestures, walks = [], []
    for k in rng.permutation(len(items)):
        kind, payload = items[k]
        if kind == "single":
            gestures.append((payload, t))
            t += _max_len(payload) + rng.uniform(15, 60)
        elif kind == "walk":
            dur = float(rng.uniform(60, 150))
            walks.append((t, dur))
            t += dur + rng.uniform(10, 30)
        elif kind == "meal":
            for _ in range(int(rng.integers(10, 21))):
                typ = "DRINK" if rng.random() < 0.15 else "EAT"
                gestures.append((typ, t))
                t += _max_len(typ) + rng.uniform(6, 25)
            t += rng.uniform(20, 60)
        elif kind == "tobacco":
            for _ in range(int(rng.integers(8, 13))):
                gestures.append(("SMOKE_OTHER", t))
                t += _max_len("SMOKE_OTHER") + rng.uniform(15, 50)
            t += rng.uniform(20, 60)
        elif kind == "cluster":
            for j in range(payload):
                gestures.append((role, t))
                # medido: bajadas breves de 2–6 s entre permanencias, o pausas largas
                t += _max_len(role) + (rng.uniform(2, 6) if rng.random() < 0.5 else rng.uniform(20, 90))
                if rng.random() < 0.3 and j < payload - 1:
                    typ = "DRINK" if rng.random() < 0.5 else "FACE_TOUCH"
                    gestures.append((typ, t))
                    t += _max_len(typ) + rng.uniform(10, 40)
            t += rng.uniform(30, 90)
    return {"gestures": gestures, "walks": walks, "duration": max(duration_s, t + 30.0)}


def simulate_session(seed: int, wrist: str = "L", crown_toward_hand: Optional[bool] = None,
                     gyro: bool = True, fs_out: float = 25.0, with_target: bool = True,
                     role: str = "TARGET_PIPE_HAND", duration_s: float = 600.0,
                     rest_theta: Optional[float] = None, posture: Optional[str] = None):
    """Brazo rígido (antebrazo 0.27 m) con orientación R = Rz(yaw)·Ry(-θ)·Rx(φ) del
    marco anatómico; aceleración lineal por doble derivada de la posición de la
    muñeca; ruido aplicado en marco anatómico (invariancia exacta entre montajes)."""
    from scipy.signal import lfilter
    from scipy.spatial.transform import Rotation as Rot
    rng = np.random.default_rng(seed)
    if posture is None:
        posture = "lying" if rng.random() < 0.25 else "sitting"
    plan = make_plan(rng, duration_s, with_target, role, posture)
    plan["posture"] = posture
    fs = 100.0
    dt = 1.0 / fs
    n = int(plan["duration"] * fs)
    t = np.arange(n) * dt

    def slow(sigma, fc):
        b, a = butter2_lowpass(fc, fs)
        y = lfilter(b, a, rng.normal(0.0, 1.0, n))
        return y / (y.std() + 1e-12) * sigma

    if rest_theta is not None:
        th_rest = float(rest_theta)
    elif posture == "lying":
        th_rest = float(rng.uniform(-20.0, -8.0))      # recostado de lado (medido: reposo ≈ −9 a −15°)
    else:
        th_rest = float(rng.choice([-20.0, -10.0, 0.0, 25.0]))
    theta_b = th_rest + slow(3.0, 0.02)
    phi_b = (40.0 if posture == "lying" else 20.0) + slow(5.0, 0.02)
    yaw = slow(25.0, 0.01)
    lin = np.zeros((n, 3))
    bounce = np.zeros(n)
    walking = np.zeros(n)
    for (t0, dur) in plan["walks"]:
        i0, i1 = int(t0 * fs), min(n, int((t0 + dur) * fs))
        if i1 <= i0:
            continue
        tt = t[i0:i1] - t0
        aw = _minjerk(np.clip(np.minimum(tt, dur - tt) / 1.0, 0.0, 1.0))
        swing = -75.0 + 18.0 * np.sin(2 * math.pi * 0.9 * t[i0:i1])
        theta_b[i0:i1] = theta_b[i0:i1] * (1 - aw) + swing * aw
        phi_b[i0:i1] = phi_b[i0:i1] * (1 - aw)
        bounce[i0:i1] += aw * 2.0 * np.sin(2 * math.pi * 1.8 * t[i0:i1])
        walking[i0:i1] = aw
    theta, phi = theta_b.copy(), phi_b.copy()
    labels, screen = [], []
    for typ, t0 in plan["gestures"]:
        sp = GESTURE_SPECS[typ]
        th_t, ph_t = rng.uniform(*sp["theta"]), rng.uniform(*sp["phi"])
        Tr, Td, Tl = rng.uniform(*sp["raise_"]), rng.uniform(*sp["dwell"]), rng.uniform(*sp["lower"])
        i_s, i_e = int(t0 * fs), min(n, int((t0 + Tr + Td + Tl) * fs) + 1)
        if i_e - i_s < 10:
            continue
        if posture == "lying" and typ != "WATCH_CHECK":
            # recostado de lado: la cara queda a θ ≈ 5–25°; el gesto es sobre todo giro + subida corta
            th_t = float(theta_b[i_s]) + rng.uniform(18.0, 35.0)
            ph_t = float(phi_b[i_s]) - rng.uniform(40.0, 80.0)
        elif typ != "WATCH_CHECK":
            # restricción anatómica: llevar la mano a la cara exige >= 20° de flexión desde el reposo
            th_t = max(th_t, float(theta_b[i_s]) + 20.0)
        tt = t[i_s:i_e] - t0
        act = np.where(tt < Tr, _minjerk(tt / Tr),
                       np.where(tt < Tr + Td, 1.0, 1.0 - _minjerk((tt - Tr - Td) / Tl)))
        td = np.clip(tt - Tr, 0.0, Td)
        in_dw = ((tt >= Tr) & (tt < Tr + Td)).astype(float)
        m_th, m_ph = np.zeros_like(tt), np.zeros_like(tt)
        if typ == "TARGET_PIPE_HAND":
            # escalones de giro discretos (15–60°, 0.3–1 s, cada 3–12 s) con la elevación estable
            off, tc = 0.0, rng.uniform(1.5, 5.0)
            while tc < Td - 1.0:
                amp = rng.uniform(15.0, 60.0) * (1.0 if off < 0 else -1.0 if off > 0 else rng.choice([-1.0, 1.0]))
                dur = rng.uniform(0.3, 1.0)
                m_ph += amp * _minjerk((td - tc) / dur) * in_dw
                off += amp
                tc += dur + rng.uniform(3.0, 12.0)
            m_th += 1.5 * np.sin(2 * math.pi * 0.07 * td) * in_dw
        elif typ == "TARGET_LIGHTER_HAND":
            m_th += 1.0 * np.sin(2 * math.pi * 0.4 * td) * in_dw
            m_ph += 2.0 * np.sin(2 * math.pi * 0.5 * td) * in_dw
            k_imp = int(rng.integers(1, 4))
            times = [rng.uniform(0.1, 0.6)] + list(rng.uniform(0.3 * Td, 0.9 * Td, k_imp - 1))
            for ti in times:
                k = i_s + int((Tr + ti) * fs)
                if k + 2 < n:
                    lin[k:k + 2, 2] += rng.uniform(6, 12) * rng.choice([-1.0, 1.0])
                    lin[k:k + 2, 1] += rng.uniform(2, 5) * rng.choice([-1.0, 1.0])
        elif typ == "DRINK":
            m_th += rng.uniform(10, 20) * (td / Td)
            m_ph -= rng.uniform(0, 15) * (td / Td)
        elif typ == "FACE_TOUCH" and rng.random() < 0.5:
            m_th += 2.5 * np.sin(2 * math.pi * 4.0 * td) * in_dw
            lin[i_s:i_e, 1] += 1.5 * np.sin(2 * math.pi * 4.0 * tt) * in_dw
        elif typ == "PHONE":
            m_th += 2.0 * np.sin(2 * math.pi * 0.05 * td) * in_dw
        theta[i_s:i_e] = theta_b[i_s:i_e] * (1 - act) + (th_t + m_th) * act
        phi[i_s:i_e] = phi_b[i_s:i_e] * (1 - act) + (ph_t + m_ph) * act
        labels.append(dict(type=typ, t_on=t0, t_dw0=t0 + Tr, t_dw1=t0 + Tr + Td, t_end=t0 + Tr + Td + Tl))
        if typ == "WATCH_CHECK":
            if rng.random() < 0.8:
                screen.append(t0 + 0.8 * Tr)
        elif rng.random() < 0.03:
            screen.append(t0 + 0.5 * Tr)
    R = Rot.from_euler("ZYX", np.column_stack([yaw, -theta, phi]), degrees=True)
    Rm = R.as_matrix()
    pos = 0.27 * Rm[:, :, 0]
    a_w = np.gradient(np.gradient(pos, dt, axis=0), dt, axis=0)
    a_w[:, 2] += bounce + G
    f_b = np.einsum("nji,nj->ni", Rm, a_w) + lin
    w_b = (R[:-1].inv() * R[1:]).as_rotvec() / dt
    w_b = np.vstack([w_b, w_b[-1:]])
    alin_true = np.linalg.norm(f_b - np.einsum("nji,j->ni", Rm, np.array([0.0, 0.0, G])), axis=1)
    f_b = f_b + rng.normal(0.0, 0.04, f_b.shape) + rng.uniform(-0.05, 0.05, 3)
    w_b = w_b + rng.normal(0.0, 0.01, w_b.shape) + rng.uniform(-0.01, 0.01, 3)
    mount = Mounting.from_config(wrist, crown_toward_hand)
    acc_dev = f_b * mount.s
    gyr_dev = mount.det * (w_b * mount.s)
    q = int(round(fs / fs_out))
    m = n // q
    acc_o = acc_dev[: m * q].reshape(m, q, 3).mean(axis=1)
    gyr_o = gyr_dev[: m * q].reshape(m, q, 3).mean(axis=1)
    t_o = t[: m * q].reshape(m, q).mean(axis=1)
    t_ns = np.round((t_o + rng.normal(0.0, 0.002, m)) * 1e9).astype(np.int64)
    keep = rng.random(m) > 0.002
    keep &= ~((t_o > 10.0) & (t_o < 10.3))          # hueco forzado de 300 ms
    sec = np.arange(int(plan["duration"]) - 1)
    mot = np.array([alin_true[int(s_ * fs): int((s_ + 1) * fs)].mean() for s_ in sec])
    wk = np.array([walking[int(s_ * fs): int((s_ + 1) * fs)].mean() for s_ in sec])
    hr_true = 70.0 + 15.0 * wk + rng.normal(0.0, 1.5, len(sec))
    hr_ok = (mot < 0.3) & (rng.random(len(sec)) < 0.7)
    st = Streams(
        acc_t_ns=t_ns[keep], acc=acc_o[keep],
        gyr_t_ns=t_ns[keep] if gyro else None, gyr=gyr_o[keep] if gyro else None,
        hr_t_ns=(sec * 1e9).astype(np.int64), hr_bpm=np.where(hr_ok, hr_true, 0.0), hr_ok=hr_ok,
        ibi_t_ns=(sec * 1e9).astype(np.int64),
        ibi_ms=np.where(hr_ok, 60000.0 / hr_true + rng.normal(0, 20, len(sec)), 0.0), ibi_ok=hr_ok,
        screen_on_t_ns=(np.array(screen) * 1e9).astype(np.int64),
    )
    return st, labels, plan, mount


# =============================================================================
# Evaluación
# =============================================================================
def _iou(a0, a1, b0, b1) -> float:
    inter = max(0.0, min(a1, b1) - max(a0, b0))
    union = max(a1, b1) - min(a0, b0)
    return inter / union if union > 0 else 0.0


def merge_holds(segs: List[Segment]) -> List[Tuple[float, float]]:
    """Une los fragmentos de una misma permanencia (hold_id) en un intervalo."""
    H: Dict[int, Tuple[float, float]] = {}
    for s in segs:
        h = H.get(s.hold_id)
        H[s.hold_id] = (s.t_on, s.t_end) if h is None else (min(h[0], s.t_on), max(h[1], s.t_end))
    return list(H.values())


def assign_labels(segs: List[Segment], labels: List[dict], iou_min: float = 0.3,
                  cover_min: float = 0.5) -> List[str]:
    """Etiqueta de cada segmento: IoU >= iou_min o, para fragmentos, >= cover_min de su duración
    dentro del gesto etiquetado."""
    out = []
    for s in segs:
        dur = max(s.t_end - s.t_on, 1e-9)
        best, cls = 0.0, "OTHER"
        for L in labels:
            inter = max(0.0, min(s.t_end, L["t_end"]) - max(s.t_on, L["t_on"]))
            iou = _iou(s.t_on, s.t_end, L["t_on"], L["t_end"])
            score = iou if iou >= iou_min else (inter / dur if inter / dur >= cover_min else 0.0)
            if score > best:
                best, cls = score, L["type"]
        out.append(cls)
    return out


def segmentation_recall(segs: List[Segment], labels: List[dict], iou_min: float = 0.3) -> Dict[str, List[int]]:
    holds = merge_holds(segs)
    rec: Dict[str, List[int]] = {}
    for L in labels:
        hit = any(_iou(h0, h1, L["t_on"], L["t_end"]) >= iou_min for h0, h1 in holds)
        r = rec.setdefault(L["type"], [0, 0])
        r[0] += int(hit)
        r[1] += 1
    return rec


def _merge_rec(acc: Dict[str, List[int]], add: Dict[str, List[int]]) -> None:
    for k, (h, n) in add.items():
        r = acc.setdefault(k, [0, 0])
        r[0] += h
        r[1] += n


def selftest(seed: int = 11, n_train: int = 16, n_test: int = 8, out_path: Optional[str] = None) -> dict:
    import time
    from scipy.signal import butter
    from sklearn.metrics import average_precision_score, roc_auc_score
    t_start = time.time()
    cfg = Config()
    rng = np.random.default_rng(seed)
    rep: dict = {"version": VERSION, "seed": seed, "checks": {}, "notes":
                 "Datos SINTÉTICOS: validan el código, NO el desempeño en el mundo real."}
    fails: List[str] = []

    # A) Filtros = scipy
    err = 0.0
    for fc in (cfg.lp_gravity_hz, cfg.lp_theta_hz):
        b, a = butter2_lowpass(fc, cfg.fs)
        bs, as_ = butter(2, fc, fs=cfg.fs)
        err = max(err, float(np.max(np.abs(np.array(b) - bs))), float(np.max(np.abs(np.array(a) - as_))))
    rep["checks"]["filter_vs_scipy_max_abs_err"] = err
    if err > 1e-12:
        fails.append("filtros no coinciden con scipy")

    # B) Invariancia de montaje (izq/der, corona normal/volteada)
    ref, inv_err, inv_seg_ok = None, 0.0, True
    for wrist, crown in (("L", True), ("R", False), ("L", False), ("R", True)):
        st, labels, plan, mount = simulate_session(1234, wrist=wrist, crown_toward_hand=crown,
                                                   with_target=True, duration_s=300)
        out = run_pipeline(st, cfg, mount, t0_ns=0)
        D = out["derived"]
        sig = np.column_stack([D.theta, D.phi, D.w_A, D.alin, out["trace"]["E"]])
        segs = [(s.i_on, s.i_dw0, s.i_dw1, s.i_end) for s in out["segments"]]
        if ref is None:
            ref = (sig, segs)
        else:
            d = np.abs(sig - ref[0])
            inv_err = max(inv_err, float(np.nanmax(d)))
            inv_seg_ok &= (segs == ref[1])
    rep["checks"]["mount_invariance_max_abs_err"] = inv_err
    rep["checks"]["mount_invariance_segments_identical"] = bool(inv_seg_ok)
    if inv_err > 1e-6 or not inv_seg_ok:
        fails.append("el marco anatómico no es invariante al montaje")

    # C) Calibración de montaje con poses sintéticas
    cal_ok = True
    for wrist, crown in (("L", True), ("R", False), ("L", False), ("R", True)):
        mt = Mounting.from_config(wrist, crown)
        hang_A = np.array([-G, 0.0, 0.0])      # f abajo -> a = +g·(-f)
        hand_A = np.array([0.0, -G, 0.0])      # l (cubital) abajo
        mc, probs = Mounting.from_calibration((hang_A * mt.s)[None, :], (hand_A * mt.s)[None, :])
        cal_ok &= (mc == mt) and not probs
    rep["checks"]["mount_calibration_roundtrip"] = bool(cal_ok)
    if not cal_ok:
        fails.append("calibración de montaje inconsistente")

    # D) Sesiones: plantillas (calibración), entrenamiento, prueba
    roles = TARGET_CLASSES
    bank = TemplateBank(max_per_class=3)
    for k in range(3):
        st, labels, _, mount = simulate_session(5000 + k, wrist=("L", "R")[k % 2], with_target=True,
                                                role=roles[k % 2], duration_s=600)
        out = run_pipeline(st, cfg, mount, t0_ns=0)
        for s, lab in zip(out["segments"], assign_labels(out["segments"], labels)):
            if lab != "OTHER":
                bank.add(lab, segment_trajectory(s, out["derived"], cfg.dtw_points))

    def make_session(idx, test=False):
        r = np.random.default_rng(seed * 1000 + idx + (500 if test else 0))
        with_t = bool(idx % 2 == 0)
        role = roles[int(r.integers(0, 2))]
        wrist = ("L", "R")[int(r.integers(0, 2))]
        crown = bool(r.integers(0, 2)) if r.random() < 0.3 else None
        gyro = bool(r.random() > 0.25)
        return simulate_session(int(r.integers(0, 10 ** 9)), wrist=wrist, crown_toward_hand=crown,
                                gyro=gyro, with_target=with_t, role=role, duration_s=600), with_t, gyro

    rec_all: Dict[str, List[int]] = {}
    rec_acc_only: Dict[str, List[int]] = {}
    F, Y, Gp = [], [], []
    n_cand, hours = 0, 0.0
    for k in range(n_train):
        (st, labels, plan, mount), with_t, gyro = make_session(k)
        out = run_pipeline(st, cfg, mount, templates=bank, t0_ns=0)
        rr = segmentation_recall(out["segments"], labels)
        _merge_rec(rec_all, rr)
        if not gyro:
            _merge_rec(rec_acc_only, rr)
        F += out["features"]
        Y += assign_labels(out["segments"], labels)
        Gp += [f"train{k}"] * len(out["segments"])
        n_cand += len(out["segments"])
        hours += plan["duration"] / 3600.0
    model, trep = train_gesture_model(F, Y, Gp, cfg, seed=seed)
    rep["train"] = trep
    rep["candidates_per_hour"] = n_cand / max(hours, 1e-9)

    # E) Prueba: gestos y episodios
    model.released = True          # SOLO en esta prueba sintética, para ejercitar L8
    pt, yt = [], []
    tp_ep, n_ep, fp_alerts, test_hours, prompts = 0, 0, 0, 0.0, 0
    for k in range(n_test):
        (st, labels, plan, mount), with_t, gyro = make_session(k, test=True)
        out = run_pipeline(st, cfg, mount, model=model, templates=bank, t0_ns=0)
        rr = segmentation_recall(out["segments"], labels)
        _merge_rec(rec_all, rr)
        if not gyro:
            _merge_rec(rec_acc_only, rr)
        lab = assign_labels(out["segments"], labels)
        pt += [g.p for g in out["gestures"]]
        yt += [int(l_ in TARGET_CLASSES) for l_ in lab]
        test_hours += plan["duration"] / 3600.0
        tgt = [L for L in labels if L["type"] in TARGET_CLASSES]
        gt = (min(L["t_on"] for L in tgt), max(L["t_end"] for L in tgt)) if tgt else None
        alerts = [e for e in out["episodes"] if e["state"] == "ALERT"]
        prompts += sum(1 for a in out["actions"] if a["action"] == "CHECKIN_PROMPT")
        if gt is not None:
            n_ep += 1
            if any(gt[0] <= a["t"] <= gt[1] + 300.0 for a in alerts):
                tp_ep += 1
        fp_alerts += sum(1 for a in alerts if gt is None or not (gt[0] <= a["t"] <= gt[1] + 300.0))
    yt_a, pt_a = np.array(yt), np.array(pt)
    rep["test"] = {
        "gesture_ap": float(average_precision_score(yt_a, pt_a)) if yt_a.sum() else NAN,
        "gesture_roc_auc": float(roc_auc_score(yt_a, pt_a)) if 0 < yt_a.sum() < len(yt_a) else NAN,
        "episodes_true": n_ep, "episodes_detected": tp_ep,
        "false_alerts": fp_alerts, "test_hours": test_hours,
        "false_alerts_per_hour": fp_alerts / max(test_hours, 1e-9),
        "checkin_prompts": prompts,
    }
    rep["segmentation_recall"] = {k: {"hit": v[0], "n": v[1], "recall": v[0] / max(1, v[1])}
                                  for k, v in sorted(rec_all.items())}
    rep["segmentation_recall_acc_only"] = {k: {"hit": v[0], "n": v[1], "recall": v[0] / max(1, v[1])}
                                           for k, v in sorted(rec_acc_only.items())}
    h2f = [k for k in rec_all if k != "WATCH_CHECK"]
    hit = sum(rec_all[k][0] for k in h2f)
    tot = sum(rec_all[k][1] for k in h2f)
    rep["segmentation_recall_hand_to_face"] = hit / max(1, tot)
    tgt_hit = sum(rec_all[k][0] for k in TARGET_CLASSES if k in rec_all)
    tgt_tot = sum(rec_all[k][1] for k in TARGET_CLASSES if k in rec_all)
    rep["segmentation_recall_target"] = tgt_hit / max(1, tgt_tot)
    if rep["segmentation_recall_hand_to_face"] < 0.90:
        fails.append("recall de segmentación mano-rostro < 0.90")
    if rep["segmentation_recall_target"] < 0.95:
        fails.append("recall de segmentación del objetivo < 0.95")
    if n_ep and tp_ep / n_ep < 0.8:
        fails.append("episodios detectados < 80 %")
    if rep["test"]["false_alerts_per_hour"] > 0.5:
        fails.append("falsas alarmas > 0.5 por hora")
    rep["fails"] = fails
    rep["passed"] = not fails
    rep["runtime_s"] = round(time.time() - t_start, 1)
    if out_path:
        with open(out_path, "w", encoding="utf-8") as fh:
            json.dump(rep, fh, ensure_ascii=False, indent=2, default=float)
    return rep


def write_golden(out_dir: str, seed: int = 4242) -> dict:
    """Vectores dorados de paridad: entradas crudas + salidas intermedias (scorer
    bootstrap determinista). El port debe reproducirlos dentro de PARITY_TOLERANCES."""
    import csv
    os.makedirs(out_dir, exist_ok=True)
    cfg = Config()
    st, labels, plan, mount = simulate_session(seed, wrist="L", with_target=True,
                                               role="TARGET_LIGHTER_HAND", duration_s=420)
    out = run_pipeline(st, cfg, mount, t0_ns=0)
    D, tr = out["derived"], out["trace"]

    def wcsv(name, header, rows):
        with open(os.path.join(out_dir, name), "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(header)
            w.writerows(rows)

    wcsv("input_acc.csv", ["t_ns", "ax", "ay", "az"],
         [[int(a), *map(lambda v: f"{v:.9g}", b)] for a, b in zip(st.acc_t_ns, st.acc)])
    wcsv("input_gyr.csv", ["t_ns", "gx", "gy", "gz"],
         [[int(a), *map(lambda v: f"{v:.9g}", b)] for a, b in zip(st.gyr_t_ns, st.gyr)])
    wcsv("input_screen_on.csv", ["t_ns"], [[int(v)] for v in st.screen_on_t_ns])
    rows = []
    for i in range(len(D.t)):
        rows.append([f"{D.t[i]:.6f}", int(D.valid[i]), int(D.mode[i]),
                     *[f"{v:.9g}" for v in D.g_dev[i]], f"{D.theta[i]:.9g}", f"{D.theta_s[i]:.9g}",
                     f"{D.dtheta[i]:.9g}", f"{D.phi[i]:.9g}", f"{D.w_phi[i]:.9g}", f"{D.alin[i]:.9g}",
                     f"{D.jerk[i]:.9g}", int(D.still[i]), f"{tr['E'][i]:.9g}", f"{tr['dE'][i]:.9g}",
                     STATE_NAMES[int(tr['state'][i])]])
    wcsv("out_derived.csv", ["t_s", "valid", "mode", "gx", "gy", "gz", "theta", "theta_s", "dtheta", "phi",
                             "w_phi", "alin", "jerk", "still", "E", "dE", "state"], rows)
    with open(os.path.join(out_dir, "out_segments.json"), "w", encoding="utf-8") as fh:
        json.dump([s.to_dict() for s in out["segments"]], fh, indent=1)
    wcsv("out_features.csv", ["segment"] + list(FEATURE_NAMES),
         [[k] + [f"{f.get(n_, NAN):.9g}" for n_ in FEATURE_NAMES] for k, f in enumerate(out["features"])])
    with open(os.path.join(out_dir, "out_gestures.json"), "w", encoding="utf-8") as fh:
        json.dump([{"t": g.t, "p": g.p, "llr": g.llr, "novel": g.novel} for g in out["gestures"]], fh, indent=1)
    with open(os.path.join(out_dir, "out_episodes.json"), "w", encoding="utf-8") as fh:
        json.dump(out["episodes"], fh, indent=1, default=float)
    meta = {"version": VERSION, "config": cfg.to_dict(), "mounting": {"sx": mount.sx, "sy": mount.sy},
            "t0_ns": 0, "parity_tolerances": PARITY_TOLERANCES, "labels_ground_truth": labels,
            "n_segments": len(out["segments"])}
    with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=1, default=float)
    return meta


# =============================================================================
# Exportación VISIO (ZIP o carpeta): lectura nativa y corrida por sesión
# =============================================================================
def _visio_open(path: str):
    """Devuelve (listar(), leer_texto(rel)) para un ZIP de exportación VISIO o su carpeta extraída."""
    import zipfile
    if os.path.isfile(path) and zipfile.is_zipfile(path):
        zf = zipfile.ZipFile(path)
        names = set(zf.namelist())
        return (lambda: sorted(names)), (lambda rel: zf.read(rel).decode("utf-8") if rel in names else None)
    root = os.path.abspath(path)

    def listar():
        out = []
        for dp, _, fns in os.walk(root):
            for fn in fns:
                out.append(os.path.relpath(os.path.join(dp, fn), root).replace(os.sep, "/"))
        return sorted(out)

    def leer(rel):
        p = os.path.join(root, *rel.split("/"))
        return open(p, encoding="utf-8").read() if os.path.exists(p) else None
    return listar, leer


def _csv_rows(text: Optional[str]) -> List[dict]:
    import csv
    import io
    return list(csv.DictReader(io.StringIO(text))) if text else []


def load_visio_export(path: str) -> List[dict]:
    """Lee dataset.json + sesiones/<watch_session>/ (acelerometro_*.csv, giroscopio_*.csv, pantalla.csv,
    marcadores.csv, heart_rate_*.csv, meta.json). Devuelve una lista de sesiones con Streams y etiquetas."""
    listar, leer = _visio_open(path)
    files = listar()
    ds = json.loads(leer("dataset.json") or "{}")
    out = []
    for s in ds.get("training_sessions", []):
        ws = s.get("watch_session")
        if not ws:
            continue
        base = f"sesiones/{ws}/"
        meta = json.loads(leer(base + "meta.json") or "{}")

        def parts(prefix):
            rows = []
            for fn in [f for f in files if f.startswith(base + prefix) and f.endswith(".csv")]:
                rows += _csv_rows(leer(fn))
            return rows
        acc = parts("acelerometro_")
        if not acc:
            continue
        gyr = parts("giroscopio_")
        hr = parts("heart_rate_")
        scr = _csv_rows(leer(base + "pantalla.csv"))
        mk = _csv_rows(leer(base + "marcadores.csv"))
        on_t = [int(r["t_elapsed_ns"]) for r in scr[1:] if str(r.get("interactive")) == "1"]
        st = Streams(
            acc_t_ns=np.array([int(r["t_elapsed_ns"]) for r in acc], np.int64),
            acc=np.array([[float(r["x_m_s2"]), float(r["y_m_s2"]), float(r["z_m_s2"])] for r in acc]),
            gyr_t_ns=np.array([int(r["t_elapsed_ns"]) for r in gyr], np.int64) if gyr else None,
            gyr=np.array([[float(r["x_rad_s"]), float(r["y_rad_s"]), float(r["z_rad_s"])] for r in gyr]) if gyr else None,
            hr_t_ns=np.array([int(r["t_elapsed_ns"]) for r in hr], np.int64) if hr else None,
            hr_bpm=np.array([float(r["bpm"]) for r in hr]) if hr else None,
            hr_ok=np.array([int(float(r["status"])) == 1 and float(r["bpm"]) > 0 for r in hr]) if hr else None,
            screen_on_t_ns=np.array(on_t, np.int64),
        )
        a0 = [int(r["t_elapsed_ns"]) for r in mk if r.get("phase") == "ACTIVITY_START"]
        a1 = [int(r["t_elapsed_ns"]) for r in mk if r.get("phase") == "ACTIVITY_END"]
        spec = json.loads(s.get("original_spec") or "{}")
        out.append({"training_id": s.get("id"), "watch_session": ws, "label": s.get("type_name"),
                    "spec_activity": spec.get("activity"), "review": s.get("review"),
                    "technical_test": str(s.get("technical_test")) == "1",
                    "evaluation_only": bool(spec.get("evaluation_only")),
                    "wrist": meta.get("wrist", "izq"), "streams": st,
                    "act_start_ns": a0[0] if a0 else int(st.acc_t_ns[0]),
                    "act_end_ns": a1[0] if a1 else int(st.acc_t_ns[-1])})
    return out


def run_visio(args) -> None:
    import csv
    cfg = Config()
    os.makedirs(args.out, exist_ok=True)
    rows = []
    for s in load_visio_export(args.visio):
        st = s["streams"]
        t0 = int(min(st.acc_t_ns[0], st.gyr_t_ns[0] if st.gyr_t_ns is not None and len(st.gyr_t_ns) else st.acc_t_ns[0]))
        wrist = "R" if str(s["wrist"]).lower().startswith(("der", "r")) else "L"
        out = run_pipeline(st, cfg, Mounting.from_config(wrist), t0_ns=t0)
        a0, a1 = (s["act_start_ns"] - t0) * 1e-9, (s["act_end_ns"] - t0) * 1e-9
        segs = [(sg, f, g) for sg, f, g in zip(out["segments"], out["features"], out["gestures"])
                if sg.t_end >= a0 and sg.t_on <= a1]
        dwell = sum(max(0.0, min(sg.t_dw1, a1) - max(sg.t_dw0, a0)) for sg, _, _ in segs)
        holds = len(set(sg.hold_id for sg, _, _ in segs))
        fn = os.path.join(args.out, f"{s['watch_session']}_candidatos.csv")
        with open(fn, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            keys = ["t_dwell", "th_dw_mean", "d_th", "n_roll_steps", "roll_step_amp", "th_dw_iqr", "alin_rms_dw",
                    "still_frac_dw", "continued", "frozen"]
            w.writerow(["hold", "fragmento", "t_on_s", "t_dw0_s", "t_dw1_s", "t_end_s", *keys, "p_bootstrap_SIN_VALIDAR"])
            for sg, f, g in segs:
                w.writerow([sg.hold_id, sg.chunk, f"{sg.t_on - a0:.2f}", f"{sg.t_dw0 - a0:.2f}", f"{sg.t_dw1 - a0:.2f}",
                            f"{sg.t_end - a0:.2f}", *[f"{f.get(k, NAN):.3g}" for k in keys], f"{g.p:.3f}"])
        rows.append([s["watch_session"], s["label"], s["spec_activity"], s["review"], f"{a1 - a0:.1f}",
                     "sí" if st.gyr is not None else "no", len(segs), holds, f"{dwell / max(a1 - a0, 1e-9):.2f}",
                     f"{max([g.p for _, _, g in segs] or [0.0]):.2f}"])
    hdr = ["sesion", "etiqueta", "actividad", "revision", "dur_s", "giroscopio", "fragmentos", "permanencias",
           "fraccion_en_permanencia", "p_max_bootstrap"]
    with open(os.path.join(args.out, "resumen.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(hdr)
        w.writerows(rows)
    print(" | ".join(hdr))
    for r in rows:
        print(" | ".join(str(x) for x in r))
    print("p_bootstrap es un prior heurístico SIN VALIDAR: ordena la revisión con video, no decide.")


# =============================================================================
# Sesiones reales desde CSV
# =============================================================================
def load_xyz_csv(path: str, time_col: Optional[str] = None, cols: Optional[List[str]] = None,
                 time_unit: str = "auto"):
    import pandas as pd
    df = pd.read_csv(path)
    names = list(df.columns)
    low = [str(c).strip().lower() for c in names]
    if time_col is None:
        keys = ("time", "ts", "nanos", "_ns", "epoch", "stamp", "tiempo")
        cand = [c for c, l_ in zip(names, low) if any(k in l_ for k in keys)]
        time_col = cand[0] if cand else names[0]
    if cols is None:
        num = [c for c in names if c != time_col and pd.api.types.is_numeric_dtype(df[c])]
        pick = []
        for axis in ("x", "y", "z"):
            m = [c for c in num if str(c).strip().lower().endswith(axis) and c not in pick]
            if m:
                pick.append(m[0])
        cols = pick if len(pick) == 3 else num[:3]
    tv = df[time_col].to_numpy(dtype=float)
    d = float(np.median(np.diff(tv))) if len(tv) > 2 else 1.0
    if time_unit == "auto":
        time_unit = "ns" if d >= 1e6 else "us" if d >= 1e3 else "ms" if d >= 0.5 else "s"
    scale = {"ns": 1.0, "us": 1e3, "ms": 1e6, "s": 1e9}[time_unit]
    t_ns = np.round(tv * scale).astype(np.int64)
    X = df[cols].to_numpy(dtype=float)
    return t_ns, X, {"time_col": time_col, "cols": [str(c) for c in cols], "time_unit": time_unit}


def run_csv(args) -> None:
    cfg = Config()
    if args.fs:
        cfg.fs = float(args.fs)
    t_a, A, ia = load_xyz_csv(args.acc, args.time_col)
    nrm = float(np.nanmedian(np.linalg.norm(A, axis=1)))
    notes = [f"acc: columnas {ia['cols']}, tiempo '{ia['time_col']}' en {ia['time_unit']}"]
    if 0.5 < nrm < 2.0:
        A = A * G
        notes.append("acc parecía estar en g -> convertido a m/s²")
    t_g, W = None, None
    if args.gyr:
        t_g, W, ig = load_xyz_csv(args.gyr, args.time_col)
        if args.gyr_deg:
            W = np.radians(W)
        notes.append(f"gyr: columnas {ig['cols']}, tiempo en {ig['time_unit']}")
    mount = Mounting.from_config(args.wrist, None if args.crown == "auto" else args.crown == "hand")
    st = Streams(acc_t_ns=t_a, acc=A, gyr_t_ns=t_g, gyr=W)
    out = run_pipeline(st, cfg, mount)
    os.makedirs(args.out, exist_ok=True)
    import csv
    with open(os.path.join(args.out, "candidatos.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        keys = ["t_dwell", "th_dw_mean", "phi_dw_sin", "phi_dw_cos", "rr_std_dw", "wf_zcr_dw", "n_spikes",
                "still_frac_dw"]
        w.writerow(["idx", "t_on_s", "t_dw0_s", "t_dw1_s", "t_end_s", "t_on_ns", "t_end_ns", *keys,
                    "p_bootstrap_SIN_VALIDAR"])
        for k, (s, f, g) in enumerate(zip(out["segments"], out["features"], out["gestures"])):
            w.writerow([k, f"{s.t_on:.2f}", f"{s.t_dw0:.2f}", f"{s.t_dw1:.2f}", f"{s.t_end:.2f}",
                        int(out["t0_ns"] + s.t_on * 1e9), int(out["t0_ns"] + s.t_end * 1e9),
                        *[f"{f.get(k_, NAN):.4g}" for k_ in keys], f"{g.p:.3f}"])
    D, tr = out["derived"], out["trace"]
    with open(os.path.join(args.out, "senales.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["t_s", "theta", "phi", "alin", "E", "state"])
        for i in range(len(D.t)):
            w.writerow([f"{D.t[i]:.3f}", f"{D.theta_s[i]:.3f}", f"{D.phi[i]:.3f}", f"{D.alin[i]:.4f}",
                        f"{tr['E'][i]:.3f}", STATE_NAMES[int(tr['state'][i])]])
    print("\n".join(notes))
    print(f"{len(out['segments'])} candidatos mano-rostro -> {os.path.join(args.out, 'candidatos.csv')}")
    print("p_bootstrap es un prior heurístico SIN VALIDAR: úsalo solo para ordenar la revisión con video.")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=f"VISIO {VERSION} — implementación de referencia")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--golden", metavar="DIR")
    ap.add_argument("--dump-config", action="store_true")
    ap.add_argument("--visio", help="ZIP o carpeta de exportación VISIO")
    ap.add_argument("--acc")
    ap.add_argument("--gyr")
    ap.add_argument("--gyr-deg", action="store_true", help="giroscopio en °/s")
    ap.add_argument("--time-col")
    ap.add_argument("--wrist", default="L")
    ap.add_argument("--crown", default="auto", choices=["auto", "hand", "elbow"])
    ap.add_argument("--fs", type=float)
    ap.add_argument("--out", default="visio_salida")
    ap.add_argument("--seed", type=int, default=11)
    a = ap.parse_args(argv)
    if a.dump_config:
        print(json.dumps(Config().to_dict(), indent=1))
        return 0
    if a.golden:
        meta = write_golden(a.golden)
        print(f"vectores dorados en {a.golden} ({meta['n_segments']} segmentos)")
        return 0
    if a.visio:
        run_visio(a)
        return 0
    if a.acc:
        run_csv(a)
        return 0
    if a.selftest:
        rep = selftest(seed=a.seed, out_path="selftest_report.json")
        print(json.dumps({k: rep[k] for k in ("checks", "segmentation_recall_hand_to_face",
                                               "segmentation_recall_target", "candidates_per_hour",
                                               "train", "test", "fails", "passed", "runtime_s")},
                         ensure_ascii=False, indent=1, default=float))
        return 0 if rep["passed"] else 1
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
