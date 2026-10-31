# -*- coding: utf-8 -*-
"""DTR-GA v2 · Capa 6: REPORTES (Markdown para revisión clínica, JSON para integración, gráfica opcional)."""
import datetime as _dt
import json
import os

import numpy as np

from dtr_base import COMPONENTES, ETIQ_PAQ, IDX, N_ETAPAS, SEM_ETAPA, SEM_TOTAL, SEM_TX, VERSION
from dtr_calibracion import resumen_conjunto

AVISO = ("Propuesta generada por un modelo de decisión para REVISIÓN CLÍNICA. No es una prescripción ni un "
         "dispositivo médico validado: toda decisión requiere evaluación presencial, consentimiento informado y "
         "juicio del equipo tratante.")

FUENTES = [
    "Trivedi MH et al. Bupropion and naltrexone in methamphetamine use disorder (ADAPT-2). N Engl J Med 2021;384:140-153.",
    "De Crescenzo F et al. Psychosocial interventions for cocaine and amphetamine addiction: network meta-analysis. PLoS Med 2018;15:e1002715.",
    "Ronsley C et al. Treatment of stimulant use disorder: systematic review of reviews. PLoS One 2020 (resumen EUDA de manejo de contingencias).",
    "McKetin R et al. Mirtazapine for methamphetamine use disorder: randomized clinical trial. JAMA Psychiatry 2026.",
    "Coffin PO et al. Mirtazapine for methamphetamine use disorder among MSM and transgender women. JAMA Psychiatry 2020;77:246-255.",
    "Rawson RA et al. Multi-site comparison of psychosocial approaches (Matrix). Addiction 2004;99:708-717.",
    "Rawson RA et al. Exercise intervention and methamphetamine use outcomes. Drug Alcohol Depend 2015;156:21-28.",
    "Jason LA et al. Communal housing settings enhance substance abuse recovery (Oxford House). Am J Public Health 2006.",
    "Mills KL et al. Integrated exposure-based therapy for co-occurring PTSD and substance dependence (COPE). JAMA 2012.",
    "Brecht ML, Herbeck D. Time to relapse following treatment for methamphetamine use. Drug Alcohol Depend 2014.",
    "Heinzerling KG et al. Bupropion in methamphetamine users with less than daily use. Addiction 2014.",
    "Fichas técnicas: bupropión, naltrexona, mirtazapina; guías de seguridad de estimulación magnética transcraneal.",
]


ETIQ = {"cm": ["", "MC", "MC-alta"], "cra": ["", "CRA"], "tcc": ["", "TCC", "Matrix-IOP"], "em": ["", "EM"],
        "ejer": ["", "Ejercicio"], "pares": ["", "Pares/12P"], "fam": ["", "T.Familiar"], "trauma": ["", "T.Trauma"],
        "viv": ["", "Vivienda-R"], "voc": ["", "IPS"], "gestion": ["", "Gestión-caso"], "bup": ["", "BUP150", "BUP300", "BUP450"],
        "ntx": ["", "NTX-oral", "NTX-XR"], "mirt": ["", "MIRT15", "MIRT30"], "rtms": ["", "EMTr"],
        "mod": ["", "MOD"], "nac": ["", "NAC"]}


def corto(modelo, pk):
    return "+".join(ETIQ[modelo.claves[j]][lv] for j, lv in enumerate(pk) if lv > 0) or "monitoreo"


def resumen_corto(modelo, g):
    def tramo(qs):
        v = [corto(modelo, g[q]) for q in qs]
        return f"{v[0]} ×3" if len(set(v)) == 1 else " → ".join(v)
    return f"**E1** {corto(modelo, g[0])} · **NR** {tramo((2, 4, 6))} · **R** {tramo((1, 3, 5))}"


def pc(x, d=1):
    return f"{100 * x:.{d}f}%"


def ic(s, k, d=1, porc=True):
    f = (lambda v: pc(v, d)) if porc else (lambda v: f"{v:,.0f}")
    return f"{f(s[k]['media'])} ({f(s[k]['p10'])}–{f(s[k]['p90'])})"


def describir_paquete(modelo, pk):
    partes = [f"{modelo.comp[j].nombre} · {modelo.comp[j].niveles[lv]}" for j, lv in enumerate(pk) if lv > 0]
    return "; ".join(partes) if partes else ("Monitoreo de recuperación (antidoping en semanas de decisión; si recae, "
                                             "pasa a la rama *no responde* y se re-escala)")


def regimen_dict(modelo, g):
    return [{"paquete": ETIQ_PAQ[q],
             "componentes": {modelo.claves[j]: modelo.comp[j].niveles[lv] for j, lv in enumerate(g[q]) if lv > 0},
             "costo_semana_mxn": modelo.costo_paquete(g[q]), "horas_semana": round(modelo.horas_paquete(g[q]), 2)}
            for q in range(len(g))]


def tabla_regimen(modelo, g):
    filas = ["| Etapa | Si RESPONDIÓ en la etapa previa: continúa con lo que recibía, **sin exceder** | Si NO respondió |",
             "|---|---|---|",
             f"| 1 · sem 1-{SEM_ETAPA} (todos) | {describir_paquete(modelo, g[0])} — "
             f"${modelo.costo_paquete(g[0]):,.0f}/sem · {modelo.horas_paquete(g[0]):.1f} h/sem | ← mismo inicio |"]
    for j in range(1, N_ETAPAS):
        r, nr = g[2 * j - 1], g[2 * j]
        a, b = SEM_ETAPA * j + 1, SEM_ETAPA * (j + 1)
        filas.append(f"| {j + 1} · sem {a}-{b} | {describir_paquete(modelo, r)} — ${modelo.costo_paquete(r):,.0f}/sem · "
                     f"{modelo.horas_paquete(r):.1f} h/sem | {describir_paquete(modelo, nr)} — "
                     f"${modelo.costo_paquete(nr):,.0f}/sem · {modelo.horas_paquete(nr):.1f} h/sem |")
    return "\n".join(filas)


def texto_regimen(modelo, g):
    lineas = [f"  Etapa 1 (sem 1-{SEM_ETAPA}): {describir_paquete(modelo, g[0])}"]
    for j in range(1, N_ETAPAS):
        lineas.append(f"  Etapa {j + 1} si responde (continúa, tope): {describir_paquete(modelo, g[2 * j - 1])}")
        lineas.append(f"  Etapa {j + 1} si NO responde:              {describir_paquete(modelo, g[2 * j])}")
    return "\n".join(lineas)


def avisos_dominio(p):
    a = []
    if p.dias_uso_ultimos_30 < 18:
        a.append("Uso < 18 de 30 días: por debajo del criterio de inclusión de ADAPT-2 → las predicciones extrapolan.")
    if p.dias_uso_ultimos_30 == 0 and p.dias_abstinencia_actual >= 90:
        a.append("Sujeto en remisión: el modelo está calibrado para uso activo (aquí sirve como plan de mantenimiento).")
    if p.edad > 65:
        a.append("Edad > 65: población poco representada en los ensayos.")
    if p.embarazo or p.lactancia:
        a.append("Embarazo/lactancia: población excluida de los ensayos de fármacos; plan a validar por especialista.")
    if p.via_administracion == "inyectada":
        a.append("Vía inyectada: minoría en los ensayos de calibración.")
    return a


def seccion_clinica(ev):
    L = ["## 1. Evaluación clínica determinista (reglas explícitas, auditables)", "",
         "| Dimensión (inspirada en ASAM 4ª ed.) | Severidad 0-4 | Motivo |", "|---|---|---|"]
    for nombre, (sev, mot) in ev.dimensiones.items():
        L.append(f"| {nombre} | {sev} | {'; '.join(mot) or '—'} |")
    L += ["", f"**Nivel de atención sugerido:** {ev.nivel_atencion} _(aproximación; no es el algoritmo oficial de ASAM)_", "",
          "### Componentes obligatorios (no se optimizan: se indican siempre que aplican)"]
    L += [f"- {o}" for o in ev.obligatorios]
    L += ["", "### Estado de cada componente para este sujeto", "",
          "| Componente | Evidencia | Estado | Nivel máximo permitido | Motivo |", "|---|---|---|---|---|"]
    for c in COMPONENTES:
        e = ev.estado[c.clave]
        nm = c.niveles[e["nivel_max"]] if e["nivel_max"] > 0 else "—"
        L.append(f"| {c.nombre} | {c.grado} | {e['estado']} | {nm} | {'; '.join(e['razones']) or '—'} |")
    if ev.notas:
        L += [""] + [f"> {n}" for n in ev.notas]
    return L


def grafica(res, modelo, ruta):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return None
    met, b_sel, b_max = res["met"], res["b_sel"], res["b_max"]
    series = [("Recomendado (mínimo suficiente)", b_sel, "#1f6f8b", "-")]
    if b_max != b_sel:
        series.append(("Máxima eficacia", b_max, "#99582a", "--"))
    for n, b in res["refs"].items():
        if b not in (b_sel, b_max) and (n.startswith("Sin programa") or n.startswith("MC + CRA")):
            series.append((n, b, "#6c757d" if n.startswith("Sin") else "#588157", ":"))
    fig, ax = plt.subplots(figsize=(9, 4.6), dpi=130)
    sem = np.arange(1, SEM_TOTAL + 1)
    for n, b, col, ls in series:
        ax.plot(sem, 100 * met["curva"][b], ls, color=col, lw=2.2 if b == b_sel else 1.6, label=n)
    ax.axvspan(SEM_TX + 0.5, SEM_TOTAL + 0.5, color="#adb5bd", alpha=0.18, lw=0)
    for j in range(1, N_ETAPAS):
        ax.axvline(SEM_ETAPA * j + 0.5, color="#adb5bd", lw=0.8)
    ax.text(SEM_TX + 1, ax.get_ylim()[1] * 0.95, "seguimiento sin programa", fontsize=8, color="#495057", va="top")
    ax.set_xlabel("Semana")
    ax.set_ylabel("Probabilidad de semana abstinente (%)")
    ax.set_title(f"Trayectoria esperada · {modelo.perfil.id}", fontsize=11)
    ax.set_xlim(1, SEM_TOTAL)
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8, loc="upper left", frameon=False)
    fig.tight_layout()
    fig.savefig(ruta)
    plt.close(fig)
    return ruta


def escribir_reportes(dir_salida, perfil, ctx, ev, conj, modelo, res, avisos, historia, cfg):
    met, G, b_sel, b_max = res["met"], res["G"], res["b_sel"], res["b_max"]
    g = G[b_sel]
    s_sel, s_max = (lambda b: {k: {"media": float(met[k][b].mean()), "p10": float(np.quantile(met[k][b], .1)),
                                   "p90": float(np.quantile(met[k][b], .9))} for k in met if k != "curva"})(b_sel), None
    resumir = lambda b: {k: {"media": float(met[k][b].mean()), "p10": float(np.quantile(met[k][b], .1)),
                             "p90": float(np.quantile(met[k][b], .9))} for k in met if k != "curva"}
    s_max = resumir(b_max)
    columnas = [("Recomendado", s_sel)]
    if b_max != b_sel:
        columnas.append(("Máxima eficacia", s_max))
    bcols = [b_sel] + ([b_max] if b_max != b_sel else [])
    for n, b in res["refs"].items():
        if b not in bcols and (n.startswith("Sin programa") or n.startswith("MC + CRA") or n.startswith("Sin límite")):
            columnas.append((n, resumir(b)))
            bcols.append(b)
    vacios = [not G[b].any() for b in bcols]
    ruta_png = grafica(res, modelo, os.path.join(dir_salida, "curvas_abstinencia.png"))
    hoy = _dt.date.today().isoformat()

    L = [f"# Plan DTR personalizado · {perfil.id}", "", f"> **{AVISO}**", "",
         f"DTR-GA v{VERSION} · {hoy} · semilla {cfg.get('semilla')} · búsqueda: {cfg.get('poblacion')}×{cfg.get('generaciones')} "
         f"(NSGA-II, {len(modelo.cache):,} regímenes únicos evaluados) · evaluación final fuera de muestra: "
         f"{res['escenario']['N']:,} trayectorias por régimen ({res['escenario']['D']} extracciones de parámetros × "
         f"{res['escenario']['I']} individuos)", ""]
    L += ["## 0. Alertas", ""]
    if ev.urgentes:
        L += ["**URGENTES (interlock ignorado por el usuario — el plan NO es aplicable hasta estabilizar):**"]
        L += [f"- ⚠ {a}" for a in ev.urgentes]
    if ev.prioritarias:
        L += ["**Prioritarias:**"] + [f"- {a}" for a in ev.prioritarias]
    dom = avisos_dominio(perfil)
    if s_sel["ret24"]["media"] < 0.25 and G[b_sel].any():
        dom = dom + [f"Retención esperada baja ({pc(s_sel['ret24']['media'])} a 24 semanas): el cuello de botella es "
                     "mantener al sujeto en tratamiento (acceso, motivación, entorno), no la elección de terapias."]
    if s_max["sost"]["media"] < 0.05:
        dom = dom + [f"Pronóstico bajo con CUALQUIER régimen ambulatorio factible (máximo P(sostenida) = "
                     f"{pc(s_max['sost']['media'])}). Priorizar mayor nivel de atención, entorno libre de consumo y "
                     "relajar restricciones (ver columna 'Sin límite de presupuesto' en la sección 2)."]
    if dom:
        L += ["**Fuera del dominio de calibración:**"] + [f"- {a}" for a in dom]
    if avisos:
        L += [f"**Datos no especificados:** {len(avisos)} campos tomaron valor por defecto (ver JSON). "
              "Completar el perfil mejora la personalización."]
    if not (ev.urgentes or ev.prioritarias or dom or avisos):
        L += ["Sin alertas."]
    L += [""] + seccion_clinica(ev) + [""]

    L += ["## 2. Régimen recomendado — conjunto mínimo suficiente", "",
          f"Criterio: entre los regímenes factibles **no-inferiores** al de máxima eficacia (diferencia media ≤ "
          f"{100 * res['margenes']['sost']:.2f} puntos en P(abstinencia sostenida) y ≤ {100 * res['margenes']['pct']:.2f} "
          "en % de semanas abstinentes —el menor entre 2-3 puntos y 10% relativo—, con ≤ 20% de probabilidad de ser peor "
          "por más del doble del margen), se elige el de **menor costo**; empates → menos componentes → menor riesgo de "
          "EA graves. Reglas clínicas duras: (a) atención escalonada — en la ruta de *no respuesta* la intensidad "
          "terapéutica nunca baja (se intensifica o se cambia, no se abandona); (b) continuidad — a quien *responde* solo "
          "se le mantiene o reduce lo que ya recibía, sin introducir componentes nuevos.", "",
          f"**Regla de decisión (tailoring):** al final de cada etapa de {SEM_ETAPA} semanas, *responde* = ≥ 3 de 4 "
          "antidopings negativos en las 2 últimas semanas (2 por semana; criterio de ADAPT-2). Abandono = no responde.", "",
          tabla_regimen(modelo, g), "",
          f"≈ {pc(s_sel['resp1']['media'], 0)} de las trayectorias simuladas responde al final de la etapa 1; el resto "
          "pasa a la rama *no responde*.", ""]
    if b_max != b_sel:
        L += ["<details><summary>Régimen de máxima eficacia (referencia)</summary>", "", tabla_regimen(modelo, G[b_max]),
              "", "</details>", ""]

    L += ["### Resultados esperados para este sujeto",
          "Media e intervalo 80% (P10–P90) **sobre la incertidumbre de parámetros** (efectos, heterogeneidad, riesgos); "
          "la variabilidad individual pura es mayor.", ""]
    cab = "| Métrica | " + " | ".join(n for n, _ in columnas) + " |"
    L += [cab, "|---" * (len(columnas) + 1) + "|"]
    filas = [("P(abstinencia sostenida, sem 33-36)", "sost", True),
             ("Abstinencia al cierre del programa (sem 21-24)", "sost_tx", True),
             ("% de semanas abstinentes (1-36)", "pct", True),
             ("% semanas abstinentes en seguimiento (25-36)", "pct_seg", True),
             ("Retención en programa a 24 semanas", "ret24", True),
             ("Respuesta en etapa 1 (criterio ADAPT-2)", "resp1", True),
             ("Costo planeado del régimen, MXN (si completa)", "costo", False),
             ("Costo esperado, MXN (ajustado por abandono)", "costo_esperado", False),
             ("Horas totales del paciente", "horas", False),
             ("Eventos graves esperados por 1000 (fármacos + consumo)", "sae", False)]
    for et, k, porc in filas:
        celdas = ["n/a" if (k in ("ret24", "horas", "resp1") and vacio) else ic(s, k, porc=porc) for (_, s), vacio in
                  zip(columnas, vacios)]
        L.append(f"| {et} | " + " | ".join(celdas) + " |")
    d = res["dif_vs_max"]
    if b_max != b_sel:
        L += ["", f"Brecha del recomendado frente al de máxima eficacia en P(sostenida): {100 * d.mean():.1f} puntos "
                  f"(P10–P90: {100 * np.quantile(d, .1):.1f} a {100 * np.quantile(d, .9):.1f}) por "
                  f"${s_max['costo']['media'] - s_sel['costo']['media']:,.0f} MXN menos."]
    if ruta_png:
        L += ["", f"![Trayectorias]({os.path.basename(ruta_png)})"]

    L += ["", "### ¿Qué componentes son realmente necesarios? (ablación)",
          "Se retira cada componente de todo el régimen y se re-simula con los mismos números aleatorios. "
          "ESENCIAL: pérdida relativa ≥ 10% en P(sostenida) con ≥ 90% de probabilidad entre escenarios de parámetros. "
          "CONTRIBUYE: pérdida ≥ 2% consistente (≥ 75%). MARGINAL: candidato a retirar si el costo o la carga importan.", "",
          "| Componente | Clase | Δ P(sostenida) al retirarlo | Pérdida relativa | P(que importe) | Δ % semanas | Ahorro MXN |",
          "|---|---|---|---|---|---|---|"]
    for a in res["ablacion"]:
        L.append(f"| {a['nombre']} | **{a['clase']}** | {-100 * a['d_sost']:+.2f} pts | {-100 * a['d_rel']:+.0f}% | "
                 f"{pc(a['p_d_sost_pos'], 0)} | {-100 * a['d_pct']:+.2f} pts | ${a['ahorro']:,.0f} |")
    if not res["ablacion"]:
        L.append("| (régimen sin componentes) | | | | | | |")
    if res["adicion"]:
        L += ["", "### Componentes permitidos que NO entraron: ¿qué aportarían?", "",
              "| Componente (nivel probado) | Δ P(sostenida) al añadirlo | P(>0) | Δ costo MXN | ¿Cabe? |", "|---|---|---|---|---|"]
        for a in res["adicion"]:
            L.append(f"| {a['nombre']} ({a['nivel']}) | {100 * a['d_sost']:+.2f} pts | {pc(a['p_d_sost_pos'], 0)} | "
                     f"{a['d_costo']:+,.0f} | {a['restriccion']} |")

    L += ["", "## 3. Alternativas: frente de Pareto (re-evaluado fuera de muestra)",
          "Cada fila es un régimen no dominado en {P(sostenida), % semanas abstinentes, costo planeado}. Útil si cambian "
          "presupuesto o preferencias.", "",
          "E1 = etapa 1; NR = ramas *no responde* (etapas 2→3→4); R = ramas *responde*.", "",
          "| # | P(sostenida) | % sem. abst. | Costo planeado MXN | Estructura |", "|---|---|---|---|---|"]
    fo = sorted(res["frente_oos"], key=lambda b: met["costo"][b].mean())
    paso = max(1, len(fo) // 14)
    filas_f = list(dict.fromkeys(fo[::paso][:15] + [b for b in (b_sel, b_max) if b in fo]))
    for i, b in enumerate(sorted(filas_f, key=lambda b: met["costo"][b].mean())):
        marca = " **← recomendado**" if b == b_sel else (" **← máx. eficacia**" if b == b_max else "")
        L.append(f"| {i + 1} | {pc(met['sost'][b].mean())} | {pc(met['pct'][b].mean())} | "
                 f"{met['costo'][b].mean():,.0f} | {resumen_corto(modelo, G[b])}{marca} |")

    L += ["", "## 4. Sensibilidad y robustez", "",
          "### Qué incertidumbre mueve más el resultado de este sujeto",
          "Correlación parcial de rangos (PRCC) entre cada fuente de incertidumbre y P(sostenida) con el régimen "
          "recomendado, descontando las demás. Signo + = valores altos mejoran el pronóstico. Lo que encabeza la lista "
          "es lo que más valdría medir mejor (perfil) o vigilar (respuesta real a cada componente).", "",
          "| Fuente de incertidumbre | PRCC |", "|---|---|"]
    for n, r in res["sensibilidad"]:
        L.append(f"| {n} | {r:+.2f} |")
    if res["robustez"]:
        L += ["", "### Robustez estructural: ¿cuánto de la heterogeneidad explica el perfil? (supuesto base R² = "
                  f"{modelo.r2:.2f})", "", "| R² supuesto | P(sostenida) recomendado | Mejor alternativa | Brecha (margen) | ¿Sigue no-inferior? |",
              "|---|---|---|---|---|"]
        for r in res["robustez"]:
            L.append(f"| {r['r2']:.2f} | {pc(r['sost_sel'])} | {pc(r['sost_mejor'])} | {100 * r['brecha']:.2f} pts "
                     f"({100 * r['margen']:.2f}) | {'sí' if r['sigue_no_inferior'] else '**no** — ' + r['motivo']} |")

    L += ["", "## 5. Plan de monitoreo", "",
          f"- Antidoping 2 por semana en las semanas de decisión ({', '.join(f'{SEM_ETAPA * j - 1}-{SEM_ETAPA * j}' for j in range(1, N_ETAPAS + 1))}); "
          "si el régimen incluye MC, antidoping 2-3 por semana durante todo el programa con entrega inmediata del incentivo.",
          "- Registrar consumo semanal (TLFB), craving, PHQ-9 y eventos adversos en cada consulta; re-evaluar este perfil al cierre de cada etapa."]
    usados = [modelo.claves[j] for j in range(modelo.K) if g[:, j].any()]
    for k in usados:
        e = ev.estado[k]
        extra = e["estudios"] + e["monitoreo"]
        if extra:
            L.append(f"- {COMPONENTES[IDX[k]].nombre}: " + "; ".join(dict.fromkeys(extra)) + ".")

    mle = conj["modelos"][conj["i_mle"]]["reporte"]
    L += ["", "## 6. Calibración y validación del modelo", "",
          "Los efectos NO se teclean a mano: cada OR publicada se convierte en efecto semanal emulando el ensayo sobre una "
          "población virtual. La heterogeneidad entre personas (σ_u) se estima con la etapa 2 de ADAPT-2 (no respondedores "
          "re-aleatorizados) y, por estar débilmente identificada, se promedian modelos ponderados por verosimilitud.", "",
          "| σ_u | log-verosimilitud etapa 2 | peso |", "|---|---|---|"]
    for (sg, ll), w in zip(conj["perfil_verosimilitud"], conj["pesos"]):
        L.append(f"| {sg:.2f} | {ll:.3f} | {w:.3f} |")
    L += ["", f"Modelo de máxima verosimilitud (σ_u = {mle['sigma_u']:.2f}): placebo etapa 1 {pc(mle['placebo_e1_sim'])} "
              f"(objetivo {pc(mle['placebo_e1_obj'])}); BUP+NTX-XR etapa 1 {pc(mle['activo_e1_sim'])} (objetivo "
              f"{pc(mle['activo_e1_obj'])}); etapa 2 placebo {pc(mle['e2_placebo_sim'])} (publicado {pc(mle['e2_placebo_pub'])}, "
              f"IC95 {pc(mle['e2_placebo_ic'][0])}–{pc(mle['e2_placebo_ic'][1])}); etapa 2 activo {pc(mle['e2_activo_sim'])} "
              f"(publicado {pc(mle['e2_activo_pub'])}, IC95 {pc(mle['e2_activo_ic'][0])}–{pc(mle['e2_activo_ic'][1])}); "
              f"recaída 12 m {pc(mle['recaida_sim'])} (objetivo aproximado {pc(mle['recaida_obj'])})."]
    bp = resumen_conjunto(conj)
    L += ["", "Efectos semanales condicionales promediados entre modelos: " +
          ", ".join(f"{k} {v:+.2f}" for k, v in bp.items()) + "."]

    L += ["", "## 7. Supuestos y limitaciones (leer antes de usar)", "",
          "1. Modelo de decisión, no dispositivo médico validado. Ninguna salida sustituye la evaluación clínica.",
          "2. La precisión está acotada por la evidencia: los efectos de casi todos los componentes en TUM son modestos y con "
          "intervalos amplios; el modelo propaga esa incertidumbre, no la elimina.",
          f"3. Personalización = modificadores con evidencia (frecuencia de uso, HSH/mujeres trans para mirtazapina, etapa de "
          f"cambio) + mecanismos (fracción del riesgo del sujeto que cada componente reduce) + priors de experto sobre los "
          f"términos de riesgo. Supuesto: el perfil explica R² = {modelo.r2:.2f} de la heterogeneidad (ver robustez).",
          "4. Efectos aditivos en escala logit, salvo la interacción BUP×NTX anclada a ADAPT-2 (que no tuvo brazos de "
          "monoterapia: es reconciliación, no sinergia demostrada).",
          "5. Persistencia por clase (fármaco 0.5 sem, MC 2 sem, habilidades 20 sem, comunitario 3 sem; 70% de lo comunitario "
          "continúa en seguimiento) y recaída post-tratamiento ~60%/12 m: supuestos aproximados.",
          "6. Sin reingreso tras abandono (conservador). Regla de decisión markoviana (depende de la última respuesta).",
          "7. Costos ilustrativos en MXN: sustituir por costos locales en `contexto.costos_mxn`. "
          "NTX-XR se asume no disponible en México (configurable).",
          "8. Fármacos con evidencia nula (modafinilo, NAC) solo entran con --modo-investigacion. "
          "Intervenciones sin evidencia clínica en TUM (p. ej., crioterapia) se excluyeron."]
    L += ["", "## 8. Fuentes principales", ""] + [f"- {f}" for f in FUENTES]
    md = "\n".join(L) + "\n"
    ruta_md = os.path.join(dir_salida, f"plan_{perfil.id}.md")
    with open(ruta_md, "w", encoding="utf-8") as f:
        f.write(md)

    salida = {
        "version": VERSION, "fecha": hoy, "aviso": AVISO, "configuracion": cfg, "perfil": dict(perfil), "contexto": ctx,
        "avisos_entrada": avisos, "avisos_dominio": dom,
        "evaluacion_clinica": {"urgentes": ev.urgentes, "prioritarias": ev.prioritarias, "nivel_atencion": ev.nivel_atencion,
                               "dimensiones": {k: {"severidad": v[0], "motivos": v[1]} for k, v in ev.dimensiones.items()},
                               "estado_componentes": ev.estado, "obligatorios": ev.obligatorios, "notas": ev.notas},
        "regimen_recomendado": regimen_dict(modelo, g), "resultados_recomendado": s_sel,
        "regimen_max_eficacia": regimen_dict(modelo, G[b_max]), "resultados_max_eficacia": s_max,
        "referencias": {n: resumir(b) for n, b in res["refs"].items()},
        "ablacion": res["ablacion"], "adicion": res["adicion"],
        "sensibilidad": [{"factor": n, "rho": r} for n, r in res["sensibilidad"]], "robustez": res["robustez"],
        "frente_pareto": [{"regimen": regimen_dict(modelo, G[b]), "sost": float(met["sost"][b].mean()),
                           "pct": float(met["pct"][b].mean()), "costo": float(met["costo"][b].mean())} for b in fo],
        "curva_semanal_recomendado": met["curva"][b_sel].tolist(),
        "calibracion": {"perfil_verosimilitud": conj["perfil_verosimilitud"], "pesos": conj["pesos"],
                        "modelo_mle": mle, "beta_promedio": bp},
        "convergencia": historia,
    }
    ruta_json = os.path.join(dir_salida, f"plan_{perfil.id}.json")
    with open(ruta_json, "w", encoding="utf-8") as f:
        json.dump(salida, f, ensure_ascii=False, indent=1, default=lambda o: o.tolist() if hasattr(o, "tolist") else str(o))
    return {"md": ruta_md, "json": ruta_json, "png": ruta_png, "resumen": s_sel}


def reporte_interlock(dir_salida, perfil, ev, avisos):
    L = [f"# INTERLOCK CLÍNICO · {perfil.id}", "", f"> **{AVISO}**", "",
         "## Atención urgente requerida — no se generó plan ambulatorio", ""]
    L += [f"- ⚠ {a}" for a in ev.urgentes]
    if ev.prioritarias:
        L += ["", "**Además:**"] + [f"- {a}" for a in ev.prioritarias]
    L += ["", "Re-ejecutar el sistema cuando el sujeto esté estabilizado (actualizando el perfil). "
              "Para fines de investigación/simulación existe `--ignorar-interlock`.", ""] + seccion_clinica(ev)
    ruta = os.path.join(dir_salida, f"interlock_{perfil.id}.md")
    with open(ruta, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    return ruta


def imprimir_resumen(perfil, ev, modelo, res, rutas):
    s = rutas["resumen"]
    print("\n" + "=" * 78)
    print(f" PLAN DTR · {perfil.id} · nivel de atención: {ev.nivel_atencion}")
    print("=" * 78)
    for a in ev.prioritarias:
        print(f" ! {a}")
    print(" Régimen recomendado (mínimo suficiente):")
    print(texto_regimen(modelo, res["G"][res["b_sel"]]))
    print(f" P(abstinencia sostenida sem 33-36): {ic(s, 'sost')}")
    print(f" % semanas abstinentes (1-36):       {ic(s, 'pct')}")
    print(f" Retención a 24 semanas:             {ic(s, 'ret24')}")
    print(f" Costo planeado del régimen (MXN):   {ic(s, 'costo', porc=False)}")
    esenciales = [a["nombre"] for a in res["ablacion"] if a["clase"] == "ESENCIAL"]
    if esenciales:
        print(f" Componentes ESENCIALES: {', '.join(esenciales)}")
    print(f" Reporte: {rutas['md']}\n JSON:    {rutas['json']}" + (f"\n Gráfica: {rutas['png']}" if rutas["png"] else ""))
    print(" " + AVISO)
