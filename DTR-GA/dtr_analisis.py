# -*- coding: utf-8 -*-
"""
DTR-GA v2 · Capa 5: ANÁLISIS DE DECISIÓN sobre el frente de Pareto.

  1. Re-evaluación FUERA DE MUESTRA: escenario nuevo y grande (extracciones de parámetros e
     individuos distintos a los de la búsqueda) → corrige el sesgo del optimizador.
  2. Selección del régimen MÍNIMO SUFICIENTE: el más barato (luego el más parsimonioso) entre los
     no-inferiores al de máxima eficacia. Ese es el "conjunto de elementos requerido".
  3. Ablación: cuánto se pierde al retirar cada componente (Δ y probabilidad de que importe).
  4. Adición: cuánto ganaría cada componente no incluido y si cabe en presupuesto/horas.
  5. Sensibilidad: qué incertidumbres (efectos, heterogeneidad, riesgos) mueven el resultado.
  6. Robustez estructural: ¿se sostiene la recomendación si el perfil explica menos o más (R²)?
"""
import numpy as np

from dtr_base import N_PAQ
from dtr_optimizador import frentes_no_dominados
from dtr_simulador import Escenario, ModeloSujeto

DELTA_NI = 0.02        # margen máximo de no inferioridad en P(abstinencia sostenida), puntos absolutos
DELTA_NI_PCT = 0.03    # margen máximo en % de semanas abstinentes
REL_NI = 0.10          # ...y nunca más de 10% relativo del mejor (evita "todo es equivalente" cerca del piso)
METRICAS = ("sost", "sost_tx", "pct", "pct_tx", "pct_seg", "ret24", "resp1", "costo", "costo_esperado", "horas", "sae")


def _rango(x):
    return np.argsort(np.argsort(x, kind="stable"), kind="stable").astype(float)


def prcc(X, y):
    """Correlación parcial de rangos (PRCC; Marino et al. 2008): efecto de cada entrada descontando las demás."""
    R = np.column_stack([_rango(X[:, i]) for i in range(X.shape[1])])
    ry = _rango(y)
    out = []
    for i in range(R.shape[1]):
        otros = np.column_stack([np.ones(len(ry)), np.delete(R, i, axis=1)])
        ex = R[:, i] - otros @ np.linalg.lstsq(otros, R[:, i], rcond=None)[0]
        ey = ry - otros @ np.linalg.lstsq(otros, ry, rcond=None)[0]
        out.append(float(np.corrcoef(ex, ey)[0, 1]) if ex.std() > 1e-9 and ey.std() > 1e-9 else 0.0)
    return out


def optimo_sin_presupuesto(modelo, g0, semilla, iters=12):
    """Ascenso local desde el régimen de máxima eficacia relajando SOLO el presupuesto (horas y reglas clínicas se respetan)."""
    from dtr_optimizador import NSGA2
    pres = modelo.presupuesto
    modelo.presupuesto = float("inf")
    try:
        ga = NSGA2(modelo, pob=8, gens=0, semilla=semilla, verbose=False)
        g = modelo.reparar(g0, ga.rng)
        f = modelo.objetivos([g])[0]
        for _ in range(iters):
            V = ga.vecinos(g)
            if not V:
                break
            FV = modelo.objetivos(V)
            k = int(np.lexsort((FV[:, 2], FV[:, 1], FV[:, 0]))[0])
            if FV[k, 0] < f[0] - 1e-9:
                g, f = V[k], FV[k]
            else:
                break
        return g
    finally:
        modelo.presupuesto = pres


def spearman(x, y):
    rx = np.argsort(np.argsort(x)).astype(float)
    ry = np.argsort(np.argsort(y)).astype(float)
    if rx.std() == 0 or ry.std() == 0:
        return 0.0
    return float(np.corrcoef(rx, ry)[0, 1])


def resumen(met, b):
    return {k: {"media": float(met[k][b].mean()), "p10": float(np.quantile(met[k][b], 0.1)),
                "p90": float(np.quantile(met[k][b], 0.9))} for k in METRICAS}


def parsimonia(g):
    return int((g.sum(0) > 0).sum()), int((g > 0).sum())


def margenes(met, b_ref):
    return (min(DELTA_NI, REL_NI * float(met["sost"][b_ref].mean())),
            min(DELTA_NI_PCT, REL_NI * float(met["pct"][b_ref].mean())))


def no_inferiores(met, b_ref):
    delta, delta_pct = margenes(met, b_ref)
    dif = met["sost"][b_ref][None] - met["sost"]          # pareado por extracción (CRN)
    dpc = met["pct"][b_ref][None] - met["pct"]
    ok = (dif.mean(1) <= delta) & ((dif > 2 * delta).mean(1) <= 0.2) & (dpc.mean(1) <= delta_pct)
    return ok, dif, dpc


def elegir_minimo_suficiente(met, G, mascara):
    costo, sae, sost = met["costo"].mean(1), met["sae"].mean(1), met["sost"].mean(1)   # costo planeado
    idx = np.where(mascara)[0]
    return int(min(idx, key=lambda b: (round(costo[b] / 250.0), *parsimonia(G[b]), sae[b], -sost[b])))


def _g(modelo, cfg):
    g = np.zeros((N_PAQ, modelo.K), np.int64)
    for k, lv in cfg.items():
        j = modelo.claves.index(k)
        g[:, j] = modelo.nivel_max[j] if lv == "max" else min(lv, modelo.nivel_max[j])
    return g


def techo(modelo):
    """Todo lo clínicamente permitido al máximo, sin límite de presupuesto ni horas (con máx. fármacos)."""
    from itertools import combinations
    g = np.tile(modelo.nivel_max, (N_PAQ, 1)).astype(np.int64)
    farm = list(modelo.idx_farm)
    if len(farm) > modelo.max_farm:
        mejor, mejor_v = (), -1.0
        for r in range(modelo.max_farm + 1):
            for sub in combinations(farm, r):
                pk = g[0].copy()
                pk[[j for j in farm if j not in sub]] = 0
                v = modelo.intensidad(pk)
                if v > mejor_v:
                    mejor, mejor_v = sub, v
        for j in farm:
            if j not in mejor:
                g[:, j] = 0
    return g


def referencias(modelo, rng):
    cand = [("Sin programa estructurado", {}), ("MC sola", {"cm": 1}),
            ("MC + CRA (estándar psicosocial)", {"cm": 1, "cra": 1}),
            ("BUP + NTX (tipo ADAPT-2)", {"bup": "max", "ntx": "max"})]
    out = {}
    for nombre, cfg in cand:
        if any(k not in modelo.claves for k in cfg):
            continue
        g = _g(modelo, cfg)
        g2 = modelo.reparar(g, rng)
        if (g2 != g).any():
            nombre += " · recortado por presupuesto/horas"
        if cfg.get("ntx") and modelo.nivel_max[modelo.claves.index("ntx")] < 2:
            nombre = nombre.replace("NTX", "NTX oral")
        out[nombre] = g2
    return out


def analizar(modelo, P, rango, n_draws=200, n_ind=200, semilla=2027, robustez=True, verbose=True):
    rng = np.random.default_rng(semilla)
    esc = Escenario(modelo, n_draws, n_ind, semilla)
    G, nombres, vistos = [], [], {}

    def agregar(g, nombre=None):
        k = g.tobytes()
        if k not in vistos:
            vistos[k] = len(G)
            G.append(g)
            nombres.append(nombre)
        elif nombre and not nombres[vistos[k]]:
            nombres[vistos[k]] = nombre
        return vistos[k]

    for g, r in zip(P, rango):
        if r == 0:
            agregar(g)
    refs = {n: agregar(g, n) for n, g in referencias(modelo, rng).items()}
    if modelo.K and len(G):
        Fb = modelo.objetivos(G)
        g_mb = optimo_sin_presupuesto(modelo, G[int(np.argmin(Fb[:, 0]))], semilla)
        if not modelo.factible(g_mb):
            refs["Sin límite de presupuesto (mismas horas y reglas clínicas)"] = agregar(
                g_mb, "Sin límite de presupuesto (mismas horas y reglas clínicas)")
    G = np.stack(G)
    if verbose:
        print(f"  Re-evaluando {len(G)} regímenes fuera de muestra ({esc.N:,} trayectorias c/u)...")
    met = modelo.simular(G, esc, curvas=True, bloque=12)

    sost_m = met["sost"].mean(1)
    fact = np.array([modelo.factible(g) for g in G])
    b_max = int(np.argmax(np.where(fact, sost_m, -1.0)))
    ni, dif, dpc = no_inferiores(met, b_max)
    ni &= fact
    b_sel = elegir_minimo_suficiente(met, G, ni)
    F = np.stack([-sost_m, -met["pct"].mean(1), met["costo"].mean(1)], 1)
    idx_f = np.where(fact)[0]
    frente_oos = idx_f[frentes_no_dominados(F[idx_f]) == 0]

    # ------------------------------------------------------------- ablación del régimen elegido
    g = G[b_sel]
    activos = [j for j in range(modelo.K) if g[:, j].any()]
    ablacion = []
    if activos:
        var = [g]
        for j in activos:
            v = g.copy()
            v[:, j] = 0
            var.append(v)
        ma = modelo.simular(np.stack(var), esc, bloque=12)
        base, base_p = float(ma["sost"][0].mean()), float(ma["pct"][0].mean())
        for i, j in enumerate(activos, start=1):
            d, dp = ma["sost"][0] - ma["sost"][i], ma["pct"][0] - ma["pct"][i]
            pp, ppp = float((d > 0).mean()), float((dp > 0).mean())
            rel, relp = d.mean() / max(base, 1e-9), dp.mean() / max(base_p, 1e-9)
            if rel >= 0.10 and pp >= 0.9:
                clase = "ESENCIAL"
            elif (rel >= 0.02 and pp >= 0.75) or (relp >= 0.02 and ppp >= 0.75):
                clase = "CONTRIBUYE"
            else:
                clase = "MARGINAL"
            ablacion.append({"clave": modelo.claves[j], "nombre": modelo.comp[j].nombre,
                             "d_sost": float(d.mean()), "d_rel": float(rel), "p_d_sost_pos": pp, "d_pct": float(dp.mean()),
                             "ahorro": float(ma["costo"][0].mean() - ma["costo"][i].mean()), "clase": clase})
        ablacion.sort(key=lambda x: -x["d_sost"])

    # ------------------------------------------------------------- adición de componentes no incluidos
    adicion = []
    no_incl = [j for j in range(modelo.K) if not g[:, j].any()]
    if no_incl:
        var = []
        for j in no_incl:
            v = g.copy()
            v[:, j] = modelo.nivel_max[j] if modelo.farm[j] else 1
            var.append(v)
        mb = modelo.simular(np.stack(var), esc, bloque=12)
        for i, j in enumerate(no_incl):
            v = var[i]
            motivo = []
            ar = np.arange(modelo.K)
            if any(modelo.COSTO[ar, pk].sum() > modelo.presupuesto + 1e-6 for pk in v):
                motivo.append("excede presupuesto")
            if any(modelo.HORAS[ar, pk].sum() > modelo.horas_disp + 1e-6 for pk in v):
                motivo.append("excede horas")
            if any((pk[modelo.idx_farm] > 0).sum() > modelo.max_farm for pk in v):
                motivo.append("excede máx. fármacos")
            d = mb["sost"][i] - met["sost"][b_sel]
            adicion.append({"clave": modelo.claves[j], "nombre": modelo.comp[j].nombre,
                            "nivel": modelo.comp[j].niveles[v[:, j].max()],
                            "d_sost": float(d.mean()), "p_d_sost_pos": float((d > 0).mean()),
                            "d_pct": float((mb["pct"][i] - met["pct"][b_sel]).mean()),
                            "d_costo": float(mb["costo"][i].mean() - met["costo"][b_sel].mean()),
                            "restriccion": ", ".join(motivo) or "cabe"})
        adicion.sort(key=lambda x: -x["d_sost"])

    # ------------------------------------------------------------- sensibilidad (qué incertidumbre importa)
    y = met["sost"][b_sel]
    nombres_x, cols = [], []
    for j in activos:
        if modelo.comp[j].or_ensayo is not None:
            nombres_x.append(f"Efecto real de {modelo.comp[j].nombre} (incertidumbre de la evidencia)")
            cols.append(esc.zbeta[:, j])
    if modelo.i_bup is not None and modelo.i_ntx is not None and g[:, modelo.i_bup].any() and g[:, modelo.i_ntx].any():
        nombres_x.append("Efecto de la combinación BUP+NTX (ADAPT-2)")
        cols.append(esc.zgamma)
    if np.unique(esc.sigma).size > 1:
        nombres_x.append("Heterogeneidad entre personas σ_u (estructura del modelo)")
        cols.append(esc.sigma)
    for i, n in enumerate(esc.riesgo_nombres):
        if esc.r[:, i].std() > 1e-9:
            nombres_x.append(f"Riesgo del perfil: {n} (+ = más riesgo)")
            cols.append(esc.r[:, i])
    factores = list(zip(nombres_x, prcc(np.column_stack(cols), y))) if cols else []
    factores.sort(key=lambda x: -abs(x[1]))

    # ------------------------------------------------------------- robustez estructural (R² del perfil)
    robust = []
    if robustez and len(G) > 1:
        orden = np.argsort(-sost_m)
        cands = sorted(set([b_sel, b_max] + list(np.where(ni)[0][:20]) + [b for b in orden if fact[b]][:10]
                           + [b for b in refs.values() if fact[b]]))
        for r2 in (0.15, 0.45):
            m2 = ModeloSujeto(modelo.perfil, modelo.ctx, modelo.ev, {"modelos": modelo.calibs, "pesos": modelo.pesos},
                              n_draws=8, n_ind=8, semilla=1, r2=r2)
            e2 = Escenario(m2, n_draws, n_ind, semilla + 7)
            mt = m2.simular(G[cands], e2, bloque=12)
            s2 = mt["sost"].mean(1)
            i_sel = cands.index(b_sel)
            i_best = int(np.argmax(s2))
            ok2, d2, dp2 = no_inferiores(mt, i_best)
            dl, dlp = margenes(mt, i_best)
            motivo = []
            if d2[i_sel].mean() > dl:
                motivo.append("brecha media en P(sostenida) > margen")
            if (d2[i_sel] > 2 * dl).mean() > 0.2:
                motivo.append(f"P(peor por > 2×margen) = {100 * (d2[i_sel] > 2 * dl).mean():.0f}%")
            if dp2[i_sel].mean() > dlp:
                motivo.append("brecha en % semanas > margen")
            robust.append({"r2": r2, "sost_sel": float(s2[i_sel]), "sost_mejor": float(s2[i_best]),
                           "brecha": float(s2[i_best] - s2[i_sel]), "margen": dl,
                           "sigue_no_inferior": bool(ok2[i_sel]), "motivo": "; ".join(motivo),
                           "mejor_es_sel": i_best == i_sel})

    delta, delta_pct = margenes(met, b_max)
    return {"G": G, "nombres": nombres, "refs": refs, "met": met, "b_sel": b_sel, "b_max": b_max,
            "margenes": {"sost": delta, "pct": delta_pct},
            "no_inferiores": np.where(ni)[0].tolist(), "frente_oos": frente_oos.tolist(),
            "dif_vs_max": dif[b_sel], "ablacion": ablacion, "adicion": adicion, "sensibilidad": factores[:8],
            "robustez": robust, "escenario": {"D": esc.D, "I": esc.I, "N": esc.N}}
