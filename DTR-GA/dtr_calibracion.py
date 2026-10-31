# -*- coding: utf-8 -*-
"""
DTR-GA v2 · Capa 2: CALIBRACIÓN POR EMULACIÓN DE ENSAYOS.

Modelo estructural (idéntico al del simulador del sujeto):
    logit P(semana abstinente)_i,t = c + u_i + s·y_{i,t-1} + efecto_t·[en tratamiento]
    u_i ~ N(0, σ_u);  abandono semanal: logit h = H0 + 0.4·[consumió] + log-HR del componente
    antidoping: 2/semana; una semana con consumo da positivo con prob P_DET
    respuesta (ADAPT-2) = >= 3 de 4 antidopings negativos en las 2 semanas finales; abandono = positivo (ITT)

Pasos:
  1) Para cada σ_u candidato:
       c ← brazo placebo ADAPT-2 etapa 1 (3.4%)
       s ← recaída ~60% en 12 meses tras 4 semanas abstinente (aprox., Brecht & Herbeck 2014)
       (c y s se alternan hasta converger)
       β combinación BUP450+NTX-XR ← brazo activo ADAPT-2 etapa 1 (16.5%)
  2) σ_u ← máxima verosimilitud binomial de ADAPT-2 ETAPA 2 (no respondedores a placebo
     re-aleatorizados: 2/111 vs 13/114). La etapa 2 informa la heterogeneidad entre personas,
     que es lo que determina cuánto rinden las ramas "si no responde" de un DTR.
  3) Cada OR publicada → efecto semanal condicional, emulando un ensayo de 12 semanas (media e IC95).
"""
import hashlib
import json
import math
import os

import numpy as np

from dtr_base import COMPONENTES, H0_ABANDONO, OBJ, P_DET, SIGMA_U, VERSION

REJILLA_SIGMA = (0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5)


def sig(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30, 30)))


def odds(p):
    p = min(max(p, 1e-6), 1 - 1e-6)
    return p / (1 - p)


def biseccion(f, objetivo, lo, hi, it=26, creciente=True):
    for _ in range(it):
        mid = 0.5 * (lo + hi)
        v = f(mid)
        if (v < objetivo) == creciente:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def log_binom(k, n, p):
    p = min(max(p, 1e-9), 1 - 1e-9)
    return (math.lgamma(n + 1) - math.lgamma(k + 1) - math.lgamma(n - k + 1)
            + k * math.log(p) + (n - k) * math.log(1 - p))


def wilson(k, n, z=1.96):
    ph = k / n
    den = 1 + z * z / n
    centro = (ph + z * z / (2 * n)) / den
    m = z * math.sqrt(ph * (1 - ph) / n + z * z / (4 * n * n)) / den
    return max(0.0, centro - m), min(1.0, centro + m)


class Emulador:
    """Población virtual de referencia con números aleatorios comunes (curvas suaves para bisección)."""

    def __init__(self, n=30000, semanas=80, semilla=2026, sigma_u=SIGMA_U):
        rng = np.random.default_rng(semilla)
        self.n = n
        self.u = (rng.standard_normal(n) * sigma_u).astype(np.float32)
        self.U = rng.random((semanas, n), dtype=np.float32)
        self.Ud = rng.random((semanas, 2, n), dtype=np.float32)
        self.Uh = rng.random((semanas, n), dtype=np.float32)

    def correr(self, c, s, efecto, semanas, desde=0, y_ini=None, in_ini=None, h_extra=0.0, abandono=True):
        n = self.n
        y_prev = np.zeros(n, bool) if y_ini is None else y_ini.copy()
        in_tx = np.ones(n, bool) if in_ini is None else in_ini.copy()
        Y = np.zeros((semanas, n), bool)
        neg = np.zeros(n, np.int16)
        ef = np.broadcast_to(np.asarray(efecto, np.float32), (semanas,))
        for k in range(semanas):
            t = desde + k
            A = np.where(in_tx, ef[k], 0.0)
            y = self.U[t] < sig(c + self.u + s * y_prev + A)
            if k >= semanas - 2:
                for j in range(2):
                    neg += in_tx & (y | (self.Ud[t, j] > P_DET))
            if abandono:
                in_tx &= ~(self.Uh[t] < sig(H0_ABANDONO + h_extra + 0.4 * (~y)))
            Y[k] = y
            y_prev = y
        return Y, in_tx, neg


def calibrar(n=30000, semilla=2026, sigma_u=SIGMA_U, convertir_or=True, verbose=False):
    em = Emulador(n=n, semilla=semilla, sigma_u=sigma_u)

    def resp6(c, s, b):
        return float((em.correr(c, s, b, 6)[2] >= 3).mean())

    def recaida(c, s):
        ef = np.r_[np.full(12, 2.0), np.zeros(52)]
        Y, _, _ = em.correr(c, s, ef, 64, abandono=False)
        base = Y[8:12].all(0)
        if base.sum() < 50:
            return 1.0
        post = Y[12:, base]
        uso2 = (~post[:-1]) & (~post[1:])
        return float(uso2.any(0).mean())

    s, c = 3.0, -3.0
    for _ in range(5):
        c = biseccion(lambda x: resp6(x, s, 0.0), OBJ["adapt2_placebo_e1"], -14.0, 3.0)
        s = biseccion(lambda x: recaida(c, x), OBJ["recaida_12m"], 0.0, 16.0, creciente=False)
    c = biseccion(lambda x: resp6(x, s, 0.0), OBJ["adapt2_placebo_e1"], -14.0, 3.0)

    # --- combinación ADAPT-2 (BUP 450 + NTX-XR) ---
    b_tot = biseccion(lambda b: resp6(c, s, b), OBJ["adapt2_activo_e1"], -2.0, 8.0)
    n1, n0 = OBJ["adapt2_n"]
    r1, r0 = OBJ["adapt2_activo_e1"], OBJ["adapt2_placebo_e1"]
    a, b_, cc, d = r1 * n1, (1 - r1) * n1, r0 * n0, (1 - r0) * n0
    se = math.sqrt(1 / a + 1 / b_ + 1 / cc + 1 / d)
    lor = math.log((a / b_) / (cc / d))
    or_lo, or_hi = math.exp(lor - 1.96 * se), math.exp(lor + 1.96 * se)
    p0_6 = resp6(c, s, 0.0)

    def b_para_or6(or_obj):
        return biseccion(lambda b: odds(resp6(c, s, b)) / odds(p0_6), or_obj, -3.0, 9.0)

    b_tot_lo, b_tot_hi = b_para_or6(or_lo), b_para_or6(or_hi)

    # --- ADAPT-2 etapa 2: no respondedores a placebo en etapa 1 que continúan ---
    Y1, in1, neg1 = em.correr(c, s, 0.0, 6)
    nr = (neg1 < 3) & in1
    ult = Y1[-1]
    _, _, neg_p = em.correr(c, s, 0.0, 6, desde=6, y_ini=ult, in_ini=nr)
    _, _, neg_a = em.correr(c, s, b_tot, 6, desde=6, y_ini=ult, in_ini=nr)
    e2_p = float((neg_p[nr] >= 3).mean())
    e2_a = float((neg_a[nr] >= 3).mean())
    (kp, np_), (ka, na) = OBJ["adapt2_e2_conteos"]
    loglik = log_binom(kp, np_, e2_p) + log_binom(ka, na, e2_a)

    rep = {
        "sigma_u": sigma_u, "c": c, "s": s,
        "placebo_e1_obj": OBJ["adapt2_placebo_e1"], "placebo_e1_sim": resp6(c, s, 0.0),
        "activo_e1_obj": OBJ["adapt2_activo_e1"], "activo_e1_sim": resp6(c, s, b_tot),
        "recaida_obj": OBJ["recaida_12m"], "recaida_sim": recaida(c, s),
        "e2_placebo_pub": kp / np_, "e2_placebo_ic": wilson(kp, np_), "e2_placebo_sim": e2_p,
        "e2_activo_pub": ka / na, "e2_activo_ic": wilson(ka, na), "e2_activo_sim": e2_a,
        "loglik_e2": loglik,
        "or_adapt2": (math.exp(lor), or_lo, or_hi),
    }
    beta = {}
    if convertir_or:
        def resp12(b, h=0.0):
            return float((em.correr(c, s, b, 12, h_extra=h)[2] >= 3).mean())

        p0_12 = resp12(0.0)
        rep["placebo_12sem"] = p0_12
        for comp in COMPONENTES:
            if comp.or_ensayo is None:
                continue
            h = comp.loghr_ret
            f = lambda b: odds(resp12(b, h)) / odds(p0_12)
            m, lo, hi = (biseccion(f, o, -5.0, 8.0) for o in comp.or_ensayo)
            beta[comp.clave] = (m, max((hi - lo) / 3.92, 0.02))

    calib = {"version": VERSION, "sigma_u": sigma_u, "c": c, "s": s, "beta": beta,
             "beta_tot": (b_tot, max((b_tot_hi - b_tot_lo) / 3.92, 0.05)), "reporte": rep}
    if verbose:
        imprimir_calibracion(calib)
    return calib


def calibrar_conjunto(rejilla=REJILLA_SIGMA, n=30000, semilla=2026, verbose=False):
    """
    Promedio bayesiano de modelos sobre σ_u (prior uniforme en la rejilla, verosimilitud binomial de
    ADAPT-2 etapa 2). El perfil es casi plano → σ_u está débilmente identificado; en vez de fijarlo,
    cada extracción de parámetros del simulador usa un modelo calibrado elegido según estos pesos.
    """
    modelos = []
    for sg in rejilla:
        cal = calibrar(n=n, semilla=semilla, sigma_u=sg, convertir_or=True)
        modelos.append(cal)
        if verbose:
            r = cal["reporte"]
            print(f"  σ_u={sg:4.2f}  c={r['c']:6.2f}  s={r['s']:5.2f}  etapa2 placebo {100*r['e2_placebo_sim']:4.1f}% "
                  f"activo {100*r['e2_activo_sim']:4.1f}%  logL={r['loglik_e2']:7.3f}")
    ll = np.array([m["reporte"]["loglik_e2"] for m in modelos])
    w = np.exp(ll - ll.max())
    w /= w.sum()
    conj = {"version": VERSION, "modelos": modelos, "pesos": w.tolist(),
            "perfil_verosimilitud": [(m["sigma_u"], float(l)) for m, l in zip(modelos, ll)],
            "i_mle": int(np.argmax(ll))}
    if verbose:
        imprimir_calibracion(conj)
    return conj


def _a_tuplas(cal):
    cal["beta"] = {k: tuple(v) for k, v in cal["beta"].items()}
    cal["beta_tot"] = tuple(cal["beta_tot"])
    return cal


def _clave_cache():
    datos = {"v": VERSION, "obj": OBJ, "h0": H0_ABANDONO, "pdet": P_DET, "rej": REJILLA_SIGMA,
             "comp": [(c.clave, c.or_ensayo, c.loghr_ret) for c in COMPONENTES]}
    return hashlib.sha256(json.dumps(datos, sort_keys=True, default=str).encode()).hexdigest()[:16]


def obtener_calibracion(ruta_cache="calibracion_cache.json", forzar=False, verbose=False):
    clave = _clave_cache()
    if not forzar and os.path.exists(ruta_cache):
        try:
            with open(ruta_cache, encoding="utf-8") as f:
                conj = json.load(f)
            if conj.get("clave") == clave:
                conj["modelos"] = [_a_tuplas(m) for m in conj["modelos"]]
                if verbose:
                    imprimir_calibracion(conj)
                return conj
        except (OSError, ValueError, KeyError):
            pass
    if verbose:
        print("Calibrando conjunto de modelos (σ_u en rejilla; ~1 min, se guarda en caché)...")
    conj = calibrar_conjunto(verbose=verbose)
    conj["clave"] = clave
    try:
        with open(ruta_cache, "w", encoding="utf-8") as f:
            json.dump(conj, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    return conj


def resumen_conjunto(conj):
    """β promedio ponderado entre modelos (solo para reporte)."""
    w = np.array(conj["pesos"])
    claves = conj["modelos"][0]["beta"].keys()
    return {k: float(sum(wi * m["beta"][k][0] for wi, m in zip(w, conj["modelos"]))) for k in claves}


def imprimir_calibracion(calib):
    if "modelos" in calib:
        print("\n=== CALIBRACIÓN · conjunto de modelos ponderado por verosimilitud (ADAPT-2 etapa 2) ===")
        for (sg, ll), w in zip(calib["perfil_verosimilitud"], calib["pesos"]):
            print(f"  σ_u={sg:4.2f}  logL={ll:7.3f}  peso={w:5.3f}")
        imprimir_calibracion(calib["modelos"][calib["i_mle"]])
        return
    r = calib["reporte"]
    pc = lambda x: f"{100 * x:5.1f}%"
    print(f"\n--- Modelo σ_u = {calib['sigma_u']:.2f} (máxima verosimilitud en la rejilla) ---")
    print(f"  Heterogeneidad σ_u = {calib['sigma_u']:.2f} | intercepto c = {r['c']:.2f} | "
          f"dependencia de estado s = {r['s']:.2f}")
    print(f"  ADAPT-2 placebo etapa 1 ......... objetivo {pc(r['placebo_e1_obj'])} → simulado {pc(r['placebo_e1_sim'])}")
    print(f"  ADAPT-2 BUP450+NTX-XR etapa 1 ... objetivo {pc(r['activo_e1_obj'])} → simulado {pc(r['activo_e1_sim'])}")
    print(f"  Recaída 12 m tras 4 sem abstinente objetivo {pc(r['recaida_obj'])} → simulado {pc(r['recaida_sim'])} (aprox.)")
    lo, hi = r["e2_placebo_ic"]
    print(f"  ADAPT-2 etapa 2 placebo: publicado {pc(r['e2_placebo_pub'])} (IC95 {pc(lo)}-{pc(hi)}) → simulado {pc(r['e2_placebo_sim'])}")
    lo, hi = r["e2_activo_ic"]
    print(f"  ADAPT-2 etapa 2 activo:  publicado {pc(r['e2_activo_pub'])} (IC95 {pc(lo)}-{pc(hi)}) → simulado {pc(r['e2_activo_sim'])}")
    o = r["or_adapt2"]
    print(f"  OR ADAPT-2 etapa 1 = {o[0]:.2f} (IC95 {o[1]:.2f}-{o[2]:.2f}); β semanal combinación = "
          f"{calib['beta_tot'][0]:.2f} ± {calib['beta_tot'][1]:.2f}")
    if calib.get("beta"):
        print("  Efectos semanales condicionales (media ± DE):")
        for k, (m, sd) in calib["beta"].items():
            print(f"     {k:7s} {m:+.2f} ± {sd:.2f}")
