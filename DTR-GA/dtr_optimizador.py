# -*- coding: utf-8 -*-
"""
DTR-GA v2 · Capa 4: NSGA-II con reparación de restricciones.

Genoma: matriz entera [7 paquetes × K componentes] con el nivel de cada componente en cada
paquete (etapa 1; etapas 2-4 × {responde, no responde}). Restricciones duras (motor clínico,
presupuesto, horas, máx. fármacos) se garantizan por REPARACIÓN: toda solución evaluada es factible.

Objetivos (minimizar), evaluados con números aleatorios comunes (CRN) → función determinista:
  f1 = −P(abstinencia sostenida, semanas 33-36)        durabilidad
  f2 = −% de semanas abstinentes (1-36)                 beneficio acumulado
  f3 = costo planeado del régimen (MXN si el sujeto lo completa; no premia el abandono)
  (EA graves esperados: se reportan y desempatan la selección final; correlacionan con f1-f2)
"""
import time

import numpy as np

from dtr_base import N_PAQ


def domina(F):
    le = (F[:, None, :] <= F[None, :, :]).all(-1)
    lt = (F[:, None, :] < F[None, :, :]).any(-1)
    return le & lt


def frentes_no_dominados(F):
    dom = domina(F)
    cnt = dom.sum(0).astype(np.int64)
    rango = np.full(len(F), -1, np.int64)
    actual = np.where(cnt == 0)[0]
    r = 0
    while len(actual):
        rango[actual] = r
        cnt = cnt - dom[actual].sum(0)
        actual = np.where((cnt == 0) & (rango < 0))[0]
        r += 1
    return rango


def hacinamiento(F, idx):
    n = len(idx)
    if n <= 2:
        return np.full(n, np.inf)
    d = np.zeros(n)
    for m in range(F.shape[1]):
        o = np.argsort(F[idx, m], kind="stable")
        fm = F[idx[o], m]
        rg = fm[-1] - fm[0]
        d[o[0]] = d[o[-1]] = np.inf
        if rg > 0:
            d[o[1:-1]] += (fm[2:] - fm[:-2]) / rg
    return d


def seleccion_ambiental(F, n):
    rango = frentes_no_dominados(F)
    crowd = np.zeros(len(F))
    elegidos = []
    for r in range(rango.max() + 1):
        idx = np.where(rango == r)[0]
        cd = hacinamiento(F, idx)
        crowd[idx] = cd
        if len(elegidos) + len(idx) <= n:
            elegidos.extend(idx.tolist())
        else:
            elegidos.extend(idx[np.argsort(-cd, kind="stable")[:n - len(elegidos)]].tolist())
            break
    el = np.array(elegidos, np.int64)
    return el, rango[el], crowd[el]


class NSGA2:
    def __init__(self, modelo, pob=96, gens=150, semilla=11, pc=0.9, verbose=True):
        self.m, self.pob, self.gens, self.pc, self.verbose = modelo, pob, gens, pc, verbose
        self.rng = np.random.default_rng(semilla)
        self.K = modelo.K
        self.pm = 1.5 / max(1, N_PAQ * self.K)
        self.historia = []

    # ------------------------------------------------------------------ inicialización
    def _g(self, cfg):
        m = self.m
        g = np.zeros((N_PAQ, self.K), np.int64)
        for k, lv in cfg.items():
            if k in m.claves:
                j = m.claves.index(k)
                g[:, j] = m.nivel_max[j] if lv == "max" else min(lv, m.nivel_max[j])
        return g

    def semillas(self):
        """Regímenes de referencia clínica como semillas (el GA puede descartarlos)."""
        base = [{}, {"cm": 1}, {"cm": 2}, {"cm": 1, "cra": 1}, {"cm": 1, "cra": 1, "pares": 1},
                {"cm": 2, "cra": 1}, {"bup": "max", "ntx": "max"}, {"cm": 1, "bup": "max", "ntx": "max"},
                {"cm": 1, "tcc": 1}, {"tcc": 2}, {"mirt": "max"}, {"cm": 1, "mirt": "max"}, {"pares": 1},
                {"cm": 1, "cra": 1, "trauma": 1}, {"cm": 1, "cra": 1, "viv": 1},
                {k: "max" for k in self.m.claves}]
        S = [self._g(c) for c in base]
        # plantillas adaptativas: psicosocial base; las ramas "no responde" escalan a farmacoterapia
        for cfg_nr in ({"bup": "max", "ntx": "max"}, {"mirt": "max"}, {"tcc": 2}):
            g = self._g({"cm": 1, "cra": 1})
            extra = self._g(cfg_nr)
            for q in (2, 4, 6):
                g[q] = np.maximum(g[q], extra[q])
            S.append(g)
        # desescalamiento: quien responde deja MC en etapas 3-4 y conserva CRA + pares
        g = self._g({"cm": 1, "cra": 1, "pares": 1})
        if "cm" in self.m.claves:
            g[[3, 5], self.m.claves.index("cm")] = 0
        S.append(g)
        return [self.m.reparar(s, self.rng) for s in S]

    def aleatorio(self):
        nm = self.m.nivel_max
        g = np.zeros((N_PAQ, self.K), np.int64)
        on = self.rng.random((N_PAQ, self.K)) < self.rng.uniform(0.15, 0.5)
        niveles = (self.rng.random((N_PAQ, self.K)) * (nm[None, :] + 1)).astype(np.int64)
        g[on] = np.maximum(1, np.minimum(niveles, nm[None, :]))[on]
        return self.m.reparar(g, self.rng)

    # ------------------------------------------------------------------ operadores
    def cruce(self, a, b):
        r = self.rng.random()
        if r < 0.4:      # por paquete (decisiones completas de una etapa/rama)
            msk = (self.rng.random(N_PAQ) < 0.5)[:, None]
        elif r < 0.8:    # por componente (trayectoria completa de un componente)
            msk = (self.rng.random(self.K) < 0.5)[None, :]
        else:            # uniforme por gen
            msk = self.rng.random(a.shape) < 0.5
        return np.where(msk, a, b)

    def mutar(self, g):
        g = g.copy()
        nm = self.m.nivel_max
        for q, j in zip(*np.nonzero(self.rng.random(g.shape) < self.pm)):
            op = self.rng.random()
            if op < 0.4:
                g[q, j] = np.clip(g[q, j] + self.rng.choice((-1, 1)), 0, nm[j])
            elif op < 0.7:
                g[q, j] = 0
            else:
                g[q, j] = self.rng.integers(0, nm[j] + 1)
        if self.K and self.rng.random() < 0.15:          # encender/apagar un componente en todo el régimen
            j = self.rng.integers(self.K)
            g[:, j] = self.rng.integers(0, nm[j] + 1)
        if self.rng.random() < 0.10:                     # copiar un paquete a otro
            a, b = self.rng.choice(N_PAQ, 2, replace=False)
            g[b] = g[a]
        if self.K and self.rng.random() < 0.10:          # escalar las ramas "no responde"
            j = self.rng.integers(self.K)
            for q in (2, 4, 6):
                g[q, j] = min(nm[j], g[q, j] + 1)
        return g

    def vecinos(self, g):
        """Vecindario: ±1 nivel en un gen y encender/apagar/cambiar un componente en todo el régimen."""
        nm, V = self.m.nivel_max, []
        for q in range(N_PAQ):
            for j in range(self.K):
                for dl in (-1, 1):
                    if 0 <= g[q, j] + dl <= nm[j]:
                        v = g.copy()
                        v[q, j] += dl
                        V.append(v)
        for j in range(self.K):
            for lv in range(nm[j] + 1):
                v = g.copy()
                v[:, j] = lv
                V.append(v)
        out, vistos = [], {g.tobytes()}
        for v in V:
            r = self.m.reparar(v, self.rng)
            if r.tobytes() not in vistos:
                vistos.add(r.tobytes())
                out.append(r)
        return out

    def pulir(self, P, F, n_sem=2, iters=8):
        """Búsqueda local (NSGA-II memético) sobre los extremos de eficacia: ascenso por mejor mejora."""
        nuevos = []
        semillas = list(dict.fromkeys([int(np.argmin(F[:, 0])), int(np.argmin(F[:, 1]))]))[:n_sem]
        for col, i in zip((0, 1), semillas):
            g, f = P[i], F[i]
            for _ in range(iters):
                V = self.vecinos(g)
                if not V:
                    break
                FV = self.m.objetivos(V)
                k = int(np.lexsort((FV[:, 2], FV[:, 1 - col], FV[:, col]))[0])
                if FV[k, col] < f[col] - 1e-9:
                    g, f = V[k], FV[k]
                    nuevos.append(g)
                else:
                    break
        return nuevos

    @staticmethod
    def _mejor(i, j, rango, crowd):
        if rango[i] != rango[j]:
            return i if rango[i] < rango[j] else j
        return i if crowd[i] >= crowd[j] else j

    # ------------------------------------------------------------------ bucle principal
    def correr(self):
        t0 = time.time()
        P, vistos = [], set()
        for g in self.semillas():
            if g.tobytes() not in vistos:
                vistos.add(g.tobytes())
                P.append(g)
        intentos = 0
        while len(P) < self.pob and intentos < 50 * self.pob:
            intentos += 1
            g = self.aleatorio()
            if g.tobytes() not in vistos:
                vistos.add(g.tobytes())
                P.append(g)
        F = self.m.objetivos(P)
        sel, rango, crowd = seleccion_ambiental(F, self.pob)
        P, F = [P[i] for i in sel], F[sel]
        for gen in range(1, self.gens + 1):
            hijos = []
            n = len(P)
            while len(hijos) < self.pob:
                i = self._mejor(*self.rng.integers(n, size=2), rango, crowd)
                j = self._mejor(*self.rng.integers(n, size=2), rango, crowd)
                h = self.cruce(P[i], P[j]) if self.rng.random() < self.pc else P[i].copy()
                hijos.append(self.m.reparar(self.mutar(h), self.rng))
            FQ = self.m.objetivos(hijos)
            R, FR = P + hijos, np.vstack([F, FQ])
            uniq, keep = set(), []
            for idx, g in enumerate(R):
                k = g.tobytes()
                if k not in uniq:
                    uniq.add(k)
                    keep.append(idx)
            R, FR = [R[i] for i in keep], FR[keep]
            relleno = 0
            while len(R) < self.pob and relleno < 20 * self.pob:
                relleno += 1
                g = self.aleatorio()
                if g.tobytes() not in uniq:
                    uniq.add(g.tobytes())
                    R.append(g)
                    FR = np.vstack([FR, self.m.objetivos([g])])
            sel, rango, crowd = seleccion_ambiental(FR, self.pob)
            P, F = [R[i] for i in sel], FR[sel]
            n0 = int((rango == 0).sum())
            reg = {"gen": gen, "mejor_sost": float(-F[:, 0].min()), "mejor_pct": float(-F[:, 1].min()),
                   "frente": n0, "evaluados": len(self.m.cache), "seg": time.time() - t0}
            self.historia.append(reg)
            if self.verbose and (gen % max(1, self.gens // 10) == 0 or gen == 1):
                print(f"  gen {gen:4d} | mejor P(sostenida) {100*reg['mejor_sost']:5.1f}% | "
                      f"mejor % sem. abst. {100*reg['mejor_pct']:5.1f}% | frente {n0:3d} | "
                      f"regímenes únicos evaluados {reg['evaluados']:6d} | {reg['seg']:6.1f} s")
        nuevos = self.pulir(P, F)
        if nuevos:
            vistos = {g.tobytes() for g in P}
            nuevos = [g for g in nuevos if g.tobytes() not in vistos]
            P = P + nuevos
            F = self.m.objetivos(P)
            rango = frentes_no_dominados(F)
            if self.verbose:
                print(f"  búsqueda local: {len(nuevos)} mejoras · mejor P(sostenida) {100 * -F[:, 0].min():5.1f}% · "
                      f"{time.time() - t0:6.1f} s")
        return P, F, rango
