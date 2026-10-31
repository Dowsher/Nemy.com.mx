# -*- coding: utf-8 -*-
"""
DTR-GA v2 — Optimizador evolutivo de Regímenes Dinámicos de Tratamiento para trastorno por uso de
metanfetamina (TUM), personalizado por perfil clínico, conductual y de entorno.

Arquitectura (6 capas):
  1. dtr_clinico.py     Motor clínico determinista: contraindicaciones, triaje/interlock, obligatorios, ASAM-like.
  2. dtr_calibracion.py Calibración por emulación de ensayos (ADAPT-2, metaanálisis) + promedio de modelos.
  3. dtr_simulador.py   Gemelo estadístico del sujeto: Monte Carlo semanal con ramas adaptativas.
  4. dtr_optimizador.py NSGA-II con reparación de restricciones.
  5. dtr_analisis.py    Re-evaluación fuera de muestra, régimen mínimo suficiente, ablación, sensibilidad, robustez.
  6. dtr_reporte.py     Reporte Markdown para revisión clínica + JSON + gráfica.

Uso:
  python dtr_main.py --plantilla perfil.json        # genera una plantilla de entrada documentada
  python dtr_main.py perfil.json                    # corrida completa (~2-3 min)
  python dtr_main.py perfil.json --rapido           # corrida corta para iterar (~40 s)
  python dtr_main.py --autoprueba                   # pruebas de integridad
Opciones: --salida DIR --generaciones N --poblacion N --semilla S --modo-investigacion
          --ignorar-interlock --recalibrar
"""
import argparse
import json
import os
import sys
import time

import numpy as np

from dtr_base import N_PAQ, VERSION, cargar_entrada, plantilla

AQUI = os.path.dirname(os.path.abspath(__file__))


def correr(entrada, salida=None, generaciones=120, poblacion=96, semilla=11, rapido=False,
           investigacion=False, ignorar_interlock=False, recalibrar=False, cache=None, verbose=True):
    from dtr_analisis import analizar
    from dtr_calibracion import obtener_calibracion
    from dtr_clinico import evaluar_clinico
    from dtr_optimizador import NSGA2
    from dtr_reporte import escribir_reportes, imprimir_resumen, reporte_interlock
    from dtr_simulador import ModeloSujeto

    t0 = time.time()
    perfil, ctx, avisos = cargar_entrada(entrada)
    salida = salida or os.path.join(os.getcwd(), f"resultados_{perfil.id}")
    os.makedirs(salida, exist_ok=True)
    ev = evaluar_clinico(perfil, ctx, investigacion=investigacion)
    if verbose:
        print(f"DTR-GA v{VERSION} · sujeto {perfil.id} · {len(avisos)} campos con valor por defecto")
        print(f"Nivel de atención sugerido: {ev.nivel_atencion}")
    if ev.urgentes and not ignorar_interlock:
        ruta = reporte_interlock(salida, perfil, ev, avisos)
        if verbose:
            print("\n*** INTERLOCK CLÍNICO: atención urgente requerida; no se genera plan ambulatorio ***")
            for a in ev.urgentes:
                print(f"  ⚠ {a}")
            print(f"Reporte: {ruta}")
        return 2, {"interlock": ruta}

    conj = obtener_calibracion(cache or os.path.join(AQUI, "calibracion_cache.json"), forzar=recalibrar, verbose=verbose)
    if rapido:
        generaciones, poblacion = min(generaciones, 40), min(poblacion, 64)
    modelo = ModeloSujeto(perfil, ctx, ev, conj, n_draws=32 if rapido else 64, n_ind=32, semilla=semilla)
    if verbose:
        print(f"\nComponentes optimizables para este sujeto ({modelo.K}): {', '.join(modelo.claves) or 'ninguno'}")
        print(f"Restricciones: presupuesto ${modelo.presupuesto:,.0f}/sem · {modelo.horas_disp:g} h/sem · "
              f"máx. {modelo.max_farm} fármacos simultáneos")
    if modelo.K:
        if verbose:
            print(f"\nNSGA-II · población {poblacion} · {generaciones} generaciones · "
                  f"{modelo.esc_busqueda.N:,} trayectorias por régimen (búsqueda)")
        from dtr_optimizador import NSGA2
        ga = NSGA2(modelo, pob=poblacion, gens=generaciones, semilla=semilla, verbose=verbose)
        P, F, rango = ga.correr()
        historia = ga.historia
    else:
        P, rango, historia = [np.zeros((N_PAQ, 0), np.int64)], np.zeros(1, np.int64), []
    if verbose:
        print("\nAnálisis de decisión:")
    res = analizar(modelo, P, rango, n_draws=100 if rapido else 200, n_ind=100 if rapido else 200,
                   semilla=semilla + 1000, robustez=True, verbose=verbose)
    cfg = {"semilla": semilla, "generaciones": generaciones if modelo.K else 0, "poblacion": poblacion,
           "rapido": rapido, "modo_investigacion": investigacion, "ignorar_interlock": ignorar_interlock}
    rutas = escribir_reportes(salida, perfil, ctx, ev, conj, modelo, res, avisos, historia, cfg)
    if verbose:
        imprimir_resumen(perfil, ev, modelo, res, rutas)
        print(f"Tiempo total: {time.time() - t0:.0f} s")
    return 0, rutas


def main(argv=None):
    ap = argparse.ArgumentParser(description="DTR-GA v2: régimen dinámico de tratamiento personalizado para TUM.")
    ap.add_argument("entrada", nargs="?", help="JSON con 'perfil' (y opcionalmente 'contexto')")
    ap.add_argument("--plantilla", nargs="?", const="-", metavar="ARCHIVO", help="escribe una plantilla de entrada")
    ap.add_argument("--autoprueba", action="store_true", help="ejecuta las pruebas de integridad")
    ap.add_argument("--salida", default=None)
    ap.add_argument("--generaciones", type=int, default=120)
    ap.add_argument("--poblacion", type=int, default=96)
    ap.add_argument("--semilla", type=int, default=11)
    ap.add_argument("--rapido", action="store_true")
    ap.add_argument("--modo-investigacion", action="store_true", help="permite componentes con evidencia nula (grado D)")
    ap.add_argument("--ignorar-interlock", action="store_true", help="simula aun con alertas urgentes (solo investigación)")
    ap.add_argument("--recalibrar", action="store_true", help="fuerza la recalibración (ignora la caché)")
    a = ap.parse_args(argv)
    if a.autoprueba:
        import dtr_pruebas
        return dtr_pruebas.main()
    if a.plantilla is not None:
        txt = json.dumps(plantilla(), ensure_ascii=False, indent=2)
        if a.plantilla == "-":
            print(txt)
        else:
            with open(a.plantilla, "w", encoding="utf-8") as f:
                f.write(txt)
            print(f"Plantilla escrita en {a.plantilla}")
        return 0
    if not a.entrada:
        ap.print_help()
        return 1
    try:
        codigo, _ = correr(a.entrada, a.salida, a.generaciones, a.poblacion, a.semilla, a.rapido,
                           a.modo_investigacion, a.ignorar_interlock, a.recalibrar)
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    return codigo


if __name__ == "__main__":
    sys.exit(main())
