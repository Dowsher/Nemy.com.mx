# -*- coding: utf-8 -*-
"""DTR-GA v2 · Pruebas de integridad: calibración, reglas clínicas, restricciones, simulador y optimizador."""
import os
import sys

import numpy as np

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)

from dtr_base import N_PAQ, OBJ, fusionar_contexto, validar_perfil  # noqa: E402
from dtr_calibracion import obtener_calibracion  # noqa: E402
from dtr_clinico import evaluar_clinico  # noqa: E402
from dtr_optimizador import frentes_no_dominados  # noqa: E402
from dtr_simulador import ModeloSujeto  # noqa: E402


def main():
    fallas, total = [], 0

    def check(cond, msg):
        nonlocal total
        total += 1
        print(("  ok   " if cond else "  FALLA ") + msg)
        if not cond:
            fallas.append(msg)

    print("1. Calibración")
    conj = obtener_calibracion(os.path.join(AQUI, "calibracion_cache.json"))
    for m in conj["modelos"]:
        r = m["reporte"]
        check(abs(r["placebo_e1_sim"] - OBJ["adapt2_placebo_e1"]) < 0.003 and
              abs(r["activo_e1_sim"] - OBJ["adapt2_activo_e1"]) < 0.005 and
              abs(r["recaida_sim"] - OBJ["recaida_12m"]) < 0.02,
              f"σ_u={m['sigma_u']:.1f}: reproduce ADAPT-2 etapa 1 y recaída")
        check(r["e2_placebo_ic"][0] <= r["e2_placebo_sim"] <= r["e2_placebo_ic"][1] and
              r["e2_activo_ic"][0] <= r["e2_activo_sim"] <= r["e2_activo_ic"][1],
              f"σ_u={m['sigma_u']:.1f}: etapa 2 dentro del IC95 publicado")
    check(abs(sum(conj["pesos"]) - 1) < 1e-9, "pesos del promedio de modelos suman 1")

    print("2. Motor clínico")
    ctx = fusionar_contexto({})

    def ev_de(**kw):
        p, _ = validar_perfil(kw)
        return evaluar_clinico(p, ctx)

    e = ev_de(antecedente_convulsiones=True)
    check(e.estado["bup"]["estado"] == "BLOQUEADO" and e.estado["rtms"]["estado"] == "BLOQUEADO",
          "convulsiones → bupropión y EMTr bloqueados")
    e = ev_de(uso_opioides_actual=True)
    check(e.estado["ntx"]["estado"] == "BLOQUEADO" and any("opioides" in a for a in e.prioritarias),
          "opioides actuales → naltrexona bloqueada + alerta prioritaria")
    e = ev_de(uso_imao_14d=True)
    check(e.estado["bup"]["estado"] == "BLOQUEADO" and e.estado["mirt"]["estado"] == "BLOQUEADO",
          "IMAO → bupropión y mirtazapina bloqueados")
    e = ev_de(alcohol_diario_intenso=True)
    check(e.estado["bup"]["estado"] == "BLOQUEADO" and "3.7" in e.nivel_atencion,
          "abstinencia alcohólica de riesgo → bupropión bloqueado y nivel 3.7")
    e = ev_de(riesgo_suicida="alto")
    check(len(e.urgentes) > 0 and "4" in e.nivel_atencion, "riesgo suicida alto → interlock y nivel 4")
    e = ev_de(rechaza_medicacion=True)
    check(all(e.estado[k]["estado"] == "RECHAZADO" for k in ("bup", "ntx", "mirt")), "rechazo de fármacos respetado")
    e = ev_de()
    check(e.estado["ntx"]["nivel_max"] == 1, "NTX-XR no disponible por defecto (México) → solo oral")
    check(e.estado["mod"]["estado"] == "EXCLUIDO_EVIDENCIA" and e.estado["nac"]["estado"] == "EXCLUIDO_EVIDENCIA",
          "modafinilo y NAC excluidos sin --modo-investigacion")
    check(e.estado["trauma"]["estado"] == "NO_INDICADO" and e.estado["viv"]["estado"] == "NO_INDICADO",
          "trauma/vivienda no indicados sin TEPT ni problema de vivienda")
    e = ev_de(tce_moderado_severo=True)
    check(e.estado["bup"]["nivel_max"] == 2, "TCE → bupropión con tope de 300 mg")
    e = ev_de(violencia_pareja=True)
    check(e.estado["fam"]["estado"] == "ESPECIALISTA", "violencia de pareja → terapia conjunta solo por especialista")

    print("3. Validación de entrada")
    for kw, msg in (({"edad": 16}, "menor de edad rechazado"), ({"vivienda": "casa"}, "opción inválida rechazada"),
                    ({"phq9": 40}, "valor fuera de rango rechazado"), ({"audit": True}, "booleano en campo numérico rechazado")):
        try:
            validar_perfil(kw)
            check(False, msg)
        except ValueError:
            check(True, msg)

    print("4. Restricciones (reparación)")
    p, _ = validar_perfil({"presupuesto_semanal_mxn": 900, "horas_disponibles_semana": 4, "convive_con_usuarios": True,
                           "pcl5": 40})
    ev = evaluar_clinico(p, ctx)
    m = ModeloSujeto(p, ctx, ev, conj, n_draws=16, n_ind=16, semilla=3)
    rng = np.random.default_rng(0)
    ok = all(m.factible(m.reparar(rng.integers(0, 4, (N_PAQ, m.K)), rng)) for _ in range(300))
    check(ok, "300 genomas aleatorios reparados son factibles (niveles, presupuesto, horas, máx. fármacos)")

    print("5. Simulador")
    p, _ = validar_perfil({})
    ev = evaluar_clinico(p, ctx)
    m = ModeloSujeto(p, ctx, ev, conj, n_draws=64, n_ind=32, semilla=5)
    z = np.zeros((N_PAQ, m.K), np.int64)
    cm = z.copy()
    cm[:, m.claves.index("cm")] = 1
    cmcra = cm.copy()
    cmcra[:, m.claves.index("cra")] = 1
    met = m.simular(np.stack([z, cm, cmcra]), m.esc_busqueda)
    s = met["sost"].mean(1)
    check(s[1] > s[0] and s[2] > s[1], f"monotonía: nada {100*s[0]:.1f}% < MC {100*s[1]:.1f}% < MC+CRA {100*s[2]:.1f}%")
    stx = met["sost_tx"].mean(1)
    or_cmcra = (stx[2] / (1 - stx[2])) / (stx[0] / (1 - stx[0]))
    check(1.5 < or_cmcra < 5.0, f"OR fin de tratamiento MC+CRA vs nada = {or_cmcra:.2f} (NMA: 2.84, IC95 1.24-6.51)")
    F1, F2 = m.objetivos([cm]), m.objetivos([cm])
    m.cache.clear()
    F3 = m.objetivos([cm])
    check(np.allclose(F1, F2) and np.allclose(F1, F3), "determinismo con números aleatorios comunes")
    check(met["costo"][0].mean() == 0 and met["costo"][1].mean() > 0, "costo cero sin programa; positivo con MC")

    p, _ = validar_perfil({"transporte": False, "minutos_a_clinica": 60, "vivienda": "inestable"})
    ev = evaluar_clinico(p, ctx)
    m2 = ModeloSujeto(p, ctx, ev, conj, n_draws=64, n_ind=32, semilla=5)
    base = np.zeros((N_PAQ, m2.K), np.int64)
    base[:, m2.claves.index("cm")] = 1
    con = base.copy()
    con[:, m2.claves.index("gestion")] = 1
    mt = m2.simular(np.stack([base, con]), m2.esc_busqueda)
    r0, r1 = mt["ret24"].mean(1)
    check(r1 > r0 + 0.05, f"gestión de caso con barreras de acceso mejora retención: {100*r0:.0f}% → {100*r1:.0f}%")
    g = np.zeros((N_PAQ, m2.K), np.int64)
    g[0, m2.claves.index("cm")] = 1
    g[1, m2.claves.index("mirt")] = 2          # responde → fármaco nuevo (prohibido)
    g[4, :] = 0                                 # no responde en etapa 3 → desescala (prohibido)
    g[2, m2.claves.index("cm")] = 2
    gr = m2.reparar(g, np.random.default_rng(1))
    check(m2.factible(gr) and gr[1, m2.claves.index("mirt")] == 0 and
          m2.intensidad(gr[4]) >= m2.intensidad(gr[2]) - 1e-9,
          "reglas clínicas: sin componentes nuevos en quien responde; sin desescalar en quien no responde")

    print("6. Optimizador")
    F = np.array([[1, 2], [2, 1], [2, 2], [3, 3], [1, 1]], float)
    check(frentes_no_dominados(F).tolist() == [1, 1, 2, 3, 0], "ordenamiento no dominado correcto")

    print(f"\n{total - len(fallas)}/{total} pruebas superadas")
    return 1 if fallas else 0


if __name__ == "__main__":
    sys.exit(main())
