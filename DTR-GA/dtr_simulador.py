# -*- coding: utf-8 -*-
"""
DTR-GA v2 · Capa 3: SIMULADOR ESTOCÁSTICO DEL SUJETO (gemelo estadístico calibrado).

Para un sujeto concreto simula miles de trayectorias semanales bajo un régimen dinámico
(7 paquetes: etapa 1 + ramas responde/no responde en etapas 2-4) y 12 semanas de seguimiento.

Por trayectoria i (extracción de parámetros d):
    logit P(semana abstinente) = c_d + u_i − desplazamiento_d + s_d·y_{t−1} + a_i·Efecto_t
      c_d, s_d, σ_d  ← modelo calibrado elegido según su peso de verosimilitud (promedio de modelos)
      R_sujeto,d     ← términos de riesgo del perfil (priors de experto con incertidumbre)
      desplazamiento = √R²·σ_d·Zmax·tanh(z/Zmax),  z = (R_sujeto − R_REF)/SD_R
        (el perfil explica R² de la heterogeneidad; saturación por factores correlacionados/sub-aditivos)
      λ_d = √R² · σ_d / SD_R   (escala de los mecanismos: reducción de un término de riesgo concreto)
      u_i ~ N(0, σ_d·√(1−R²))  (heterogeneidad residual no explicada por el perfil)
      a_i               ← adherencia individual (multiplicador del efecto)
      Efecto_t = Σ_k E_k,t · W_d,k  (+ interacción BUP×NTX)
        E_k,t: exposición (dosis-respuesta × adherencia esperada × rampa de inicio / decaimiento por clase)
        W_d,k: β_k (OR publicada → efecto condicional) × modificador del sujeto
               + mecanismo personalizado (fracción del riesgo del sujeto que el componente reduce)
    Abandono (semanas 1-24): logit h = H0 + estático(perfil) + 0.4·consumo + Σ log-HR + carga(horas)
    Respuesta al final de cada etapa: >= 3/4 antidopings negativos (criterio ADAPT-2) → elige rama.
    Fármacos: suspensión individual por EA leves; EA graves y eventos ligados al consumo como riesgo esperado.
"""
import numpy as np

from dtr_base import (CLASES, COMPONENTES, DECAE, FRAC_COM_SEGUIMIENTO, H0_ABANDONO, I_COM, IDX, INTER_BUP,
                      INTER_NTX, LMAX, MAX_FARMACOS, N_ETAPAS, N_PAQ, P_DET, R2_PERFIL, R_REF, REF_RIESGO,
                      SEM_ETAPA, SEM_SEG, SEM_TOTAL, SEM_TX)
from dtr_clinico import (abandono_acceso, abandono_estatico, acceso_adherencia, factor_horas, factores_seguridad,
                         modificadores, terminos_riesgo)

N_RUTAS = 2 ** (N_ETAPAS - 1)     # 8 historias de respuesta posibles
SD_R_REF = 0.75                   # DE del puntaje de riesgo entre participantes de ensayos (supuesto; incluye
                                  # correlación positiva entre factores: depresión, TEPT, craving, ACE...)
Z_MAX = 2.0                       # saturación: el perfil no desplaza más de ~2 DE (factores sub-aditivos)
KAPPA_CARGA, UMBRAL_CARGA = 1.2, 0.6   # abandono por carga: horas/disponibles por encima de 0.6
P_DISC_EA = 0.08                  # fracción de EA leves que llevan a suspender el fármaco
SD_ADH_IND = 0.25                 # variabilidad individual de adherencia
SEM_SOST = 4                      # abstinencia sostenida = 4 semanas finales del seguimiento


def sig(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30.0, 30.0)))


def paquete(j, r):
    """Índice de paquete para la etapa j (0..3) dado r = respondió al final de la etapa j-1."""
    return 0 if j == 0 else 2 * j - 1 + (0 if r else 1)


def paquetes_por_ruta():
    PK = np.zeros((N_RUTAS, N_ETAPAS), np.int64)
    for p in range(N_RUTAS):
        for j in range(N_ETAPAS):
            PK[p, j] = 0 if j == 0 else paquete(j, (p >> (j - 1)) & 1)
    return PK


class Escenario:
    """Conjunto fijo de extracciones de parámetros + individuos + semilla de ruido semanal (CRN)."""

    def __init__(self, modelo, n_draws, n_ind, semilla):
        rng = np.random.default_rng(semilla)
        D, I = n_draws, n_ind
        self.D, self.I, self.N = D, I, D * I
        self.semilla_semanal = int(rng.integers(1 << 31))
        calibs, pesos = modelo.calibs, modelo.pesos
        r2 = modelo.r2
        # asignación estratificada de extracciones a modelos calibrados (según su peso)
        cuant = (np.arange(D) + 0.5) / D
        m = np.minimum(np.searchsorted(np.cumsum(pesos), cuant), len(pesos) - 1)
        m = rng.permutation(m)
        self.m = m
        self.c = np.array([calibs[i]["c"] for i in m])
        self.s = np.array([calibs[i]["s"] for i in m])
        self.sigma = np.array([calibs[i]["sigma_u"] for i in m])
        lam = np.sqrt(r2) * self.sigma / SD_R_REF
        self.lam = lam
        # términos de riesgo del sujeto
        nombres = list(modelo.riesgos)
        mu = np.array([modelo.riesgos[n][0] for n in nombres])
        sd = np.array([modelo.riesgos[n][1] for n in nombres])
        r = rng.normal(mu, sd, size=(D, len(nombres)))
        self.riesgo_nombres, self.r = nombres, r
        z = (r.sum(1) - R_REF) / SD_R_REF                      # carga de riesgo estandarizada
        self.z = z
        self.Rshift = np.sqrt(r2) * self.sigma * Z_MAX * np.tanh(z / Z_MAX)
        # efectos de componentes
        K = modelo.K
        beta = np.zeros((D, K))
        zbeta = np.zeros((D, K))
        for j, c in enumerate(modelo.comp):
            if c.or_ensayo is not None:
                mb = np.array([calibs[i]["beta"][c.clave][0] for i in m])
                sb = np.array([calibs[i]["beta"][c.clave][1] for i in m])
                beta[:, j] = rng.normal(mb, sb)
                zbeta[:, j] = (beta[:, j] - mb) / sb
        mech = np.zeros((D, K))
        for j, c in enumerate(modelo.comp):
            for nombre, frac in c.mecanismos.items():
                rv = np.maximum(r[:, nombres.index(nombre)], 0.0) if nombre in nombres else 0.0
                ref = REF_RIESGO[nombre] if c.or_ensayo is not None else 0.0
                mech[:, j] += frac * lam * (rv - ref)
        self.beta, self.zbeta = beta, zbeta
        self.W = (beta * modelo.mods[None, :] + mech).astype(np.float32)
        if modelo.i_bup is not None and modelo.i_ntx is not None:
            mt = np.array([calibs[i]["beta_tot"][0] for i in m])
            st = np.array([calibs[i]["beta_tot"][1] for i in m])
            bt = rng.normal(mt, st)
            self.zgamma = (bt - mt) / st
            self.gamma = (bt - (beta[:, modelo.i_bup] * 1.1 + beta[:, modelo.i_ntx] * 1.0)).astype(np.float32)
        else:
            self.gamma = np.zeros(D, np.float32)
            self.zgamma = np.zeros(D)
        # individuos
        sig_res = self.sigma * np.sqrt(1.0 - r2)
        u = rng.standard_normal((D, I)) * sig_res[:, None]
        a = np.clip(1.0 + SD_ADH_IND * rng.standard_normal((D, I)), 0.1, 1.8)
        self.di = np.repeat(np.arange(D), I)
        self.u = u.ravel().astype(np.float32)
        self.a = a.ravel().astype(np.float32)


class ModeloSujeto:
    def __init__(self, perfil, ctx, ev, conj_calib, n_draws=64, n_ind=32, semilla=7, r2=None):
        self.perfil, self.ctx, self.ev = perfil, ctx, ev
        self.r2 = float(ctx.get("r2_perfil", R2_PERFIL)) if r2 is None else float(r2)
        self.calibs = conj_calib["modelos"]
        self.pesos = np.asarray(conj_calib["pesos"], float)
        self.claves = [c.clave for c in COMPONENTES if ev.buscable(c.clave)]
        self.comp = [COMPONENTES[IDX[k]] for k in self.claves]
        K = self.K = len(self.claves)
        self.nivel_max = np.array([ev.estado[k]["nivel_max"] for k in self.claves], np.int64)
        acc = acceso_adherencia(perfil)
        fseg = factores_seguridad(perfil)
        mods = modificadores(perfil)
        costos_ctx = ctx.get("costos_mxn", {})
        self.MULT = np.zeros((K, LMAX), np.float32)
        self.ADH = np.zeros((K, LMAX), np.float32)
        self.COSTO = np.zeros((K, LMAX), np.float32)
        self.HORAS = np.zeros((K, LMAX), np.float32)
        self.EAG = np.zeros((K, LMAX), np.float32)
        rm = max(len(c.rampa) for c in COMPONENTES) + 1
        self.RAMPA = np.ones((K, rm), np.float32)
        for j, c in enumerate(self.comp):
            n = len(c.niveles)
            self.MULT[j, :n] = c.mult
            self.ADH[j, :n] = np.array(c.adh) * acc[c.clave]
            self.COSTO[j, :n] = costos_ctx.get(c.clave, c.costo)
            self.HORAS[j, :n] = np.array(c.horas) * factor_horas(perfil, c)
            self.EAG[j, :n] = np.array(c.ea_grave) * fseg["ea"][c.clave]
            self.RAMPA[j, :len(c.rampa)] = c.rampa
        self.cls = np.array([CLASES.index(c.clase) for c in self.comp], np.int64)
        self.dec = DECAE[self.cls] if K else np.zeros(0, np.float32)
        self.CL = np.zeros((K, len(CLASES)), np.float32)
        self.CL[np.arange(K), self.cls] = 1.0
        h_acc = abandono_acceso(perfil)
        self.loghr = np.array([c.loghr_ret - c.reduce_abandono * h_acc for c in self.comp], np.float32)
        self.farm = np.array([c.tipo == "farmaco" for c in self.comp], bool)
        self.idx_farm = np.where(self.farm)[0]
        self.idx_nf = np.where(~self.farm)[0]
        self.pdisc = np.array([c.ea_leve * P_DISC_EA if c.tipo == "farmaco" else 0.0 for c in self.comp], np.float32)
        self.mods = np.array([mods[c.clave] for c in self.comp], np.float32)
        self.i_bup = self.claves.index("bup") if "bup" in self.claves else None
        self.i_ntx = self.claves.index("ntx") if "ntx" in self.claves else None
        self.q_bup = int(np.where(self.idx_farm == self.i_bup)[0][0]) if self.i_bup is not None else None
        self.q_ntx = int(np.where(self.idx_farm == self.i_ntx)[0][0]) if self.i_ntx is not None else None
        self.prot_ntx = np.array(fseg["ntx_proteccion"], np.float32)
        self.p_otro, self.p_sob = fseg["p_otro"], fseg["p_sobredosis"]
        self.riesgos = terminos_riesgo(perfil)
        self.h_static = abandono_estatico(perfil)
        self.y0 = perfil.dias_abstinencia_actual >= 7
        self.presupuesto = float(perfil.presupuesto_semanal_mxn)
        self.horas_disp = float(perfil.horas_disponibles_semana)
        self.max_farm = int(ctx.get("max_farmacos", MAX_FARMACOS))
        self.PK = paquetes_por_ruta()
        self.esc_busqueda = Escenario(self, n_draws, n_ind, semilla)
        # pesos de intensidad terapéutica esperada para ESTE sujeto (efecto semanal medio por unidad de exposición)
        self.W_media = np.maximum(self.esc_busqueda.W.mean(0), 0.0)
        self.gamma_media = max(float(self.esc_busqueda.gamma.mean()), 0.0)
        self.cache = {}
        self.n_evals = 0

    def intensidad(self, pk):
        """Efecto semanal esperado (logit) de un paquete: Σ W̄·dosis·adherencia (+ interacción BUP×NTX)."""
        if not self.K:
            return 0.0
        ar = np.arange(self.K)
        v = float((self.W_media * self.MULT[ar, pk] * self.ADH[ar, pk]).sum())
        if self.i_bup is not None and self.i_ntx is not None:
            lb, ln = pk[self.i_bup], pk[self.i_ntx]
            v += self.gamma_media * INTER_BUP[lb] * INTER_NTX[ln] * self.ADH[self.i_bup, lb] * self.ADH[self.i_ntx, ln]
        return v

    # ------------------------------------------------------------------ restricciones
    def costo_paquete(self, pk):
        return float(self.COSTO[np.arange(self.K), pk].sum()) if self.K else 0.0

    def horas_paquete(self, pk):
        return float(self.HORAS[np.arange(self.K), pk].sum()) if self.K else 0.0

    def _reparar_paquete(self, pk, rng):
        pk = pk.copy()
        ar = np.arange(self.K)
        fon = [int(k) for k in self.idx_farm if pk[k] > 0]
        while len(fon) > self.max_farm:
            pk[fon.pop(int(rng.integers(len(fon))))] = 0
        for _ in range(200):
            cst, hrs = self.COSTO[ar, pk].sum(), self.HORAS[ar, pk].sum()
            exc_c, exc_h = cst > self.presupuesto + 1e-6, hrs > self.horas_disp + 1e-6
            if not (exc_c or exc_h):
                break
            act = np.where(pk > 0)[0]
            if len(act) == 0:
                break
            w = np.full(len(act), 1e-6)
            if exc_c:
                w += self.COSTO[act, pk[act]] / max(cst, 1e-9)
            if exc_h:
                w += self.HORAS[act, pk[act]] / max(hrs, 1e-9)
            pk[act[rng.choice(len(act), p=w / w.sum())]] -= 1
        return pk

    @staticmethod
    def _previos(j):
        """Paquetes de la etapa anterior (etapa j = 1..3 en índice 0)."""
        return [0] if j == 1 else [2 * j - 3, 2 * j - 2]

    def reparar(self, g, rng):
        """
        Proyecta un genoma al espacio factible:
          - niveles permitidos por el motor clínico, máx. fármacos simultáneos, presupuesto y horas por semana;
          - ATENCIÓN ESCALONADA para quien NO responde: la intensidad terapéutica nunca baja a lo largo de la
            ruta de no respuesta (E1 <= NR2 <= NR3 <= NR4): se intensifica o se cambia, no se abandona;
          - CONTINUIDAD para quien SÍ responde: solo se mantiene o reduce lo que ya recibía en la etapa previa
            (no se introducen componentes nuevos en quien está funcionando).
        """
        g = np.clip(np.asarray(g, np.int64), 0, self.nivel_max[None, :]).copy()
        for q in range(N_PAQ):
            g[q] = self._reparar_paquete(g[q], rng)
        for j in range(1, N_ETAPAS):
            qr, qnr = 2 * j - 1, 2 * j
            g[qr] = np.minimum(g[qr], g[self._previos(j)].max(0))
            piso = 0 if j == 1 else 2 * j - 2
            i_piso = self.intensidad(g[piso])
            if self.intensidad(g[qnr]) + 1e-9 < i_piso:
                cand = self._reparar_paquete(np.maximum(g[qnr], g[piso]), rng)
                g[qnr] = cand if self.intensidad(cand) + 1e-9 >= i_piso else g[piso]
        return g

    def factible(self, g):
        ar = np.arange(self.K)
        for pk in g:
            if (pk > self.nivel_max).any() or (pk < 0).any():
                return False
            if (pk[self.idx_farm] > 0).sum() > self.max_farm:
                return False
            if self.COSTO[ar, pk].sum() > self.presupuesto + 1e-6 or self.HORAS[ar, pk].sum() > self.horas_disp + 1e-6:
                return False
        for j in range(1, N_ETAPAS):
            if (g[2 * j - 1] > g[self._previos(j)].max(0)).any():
                return False
            piso = 0 if j == 1 else 2 * j - 2
            if self.intensidad(g[2 * j]) + 1e-9 < self.intensidad(g[piso]):
                return False
        return True

    # ------------------------------------------------------------------ paquetes efectivamente recibidos por ruta
    def paquetes_reales(self, G):
        """
        (B, ruta, etapa, K). Si NO respondió: recibe el paquete NR de la etapa. Si respondió: CONTINÚA con lo que
        recibía, reducido como máximo al tope R de la etapa: min(R_j, paquete previo). Así, en ninguna historia se
        introducen componentes nuevos en quien está respondiendo.
        """
        B = G.shape[0]
        A = np.zeros((B, N_RUTAS, N_ETAPAS, self.K), np.int64)
        A[:, :, 0, :] = G[:, None, 0, :]
        for j in range(1, N_ETAPAS):
            for p in range(N_RUTAS):
                if (p >> (j - 1)) & 1:
                    A[:, p, j] = np.minimum(G[:, 2 * j - 1], A[:, p, j - 1])
                else:
                    A[:, p, j] = G[:, 2 * j]
        return A

    # ------------------------------------------------------------------ exposición determinista por ruta
    def exposicion(self, G):
        """Arreglos deterministas por (semana, genoma, ruta): exposición, abandono, costo, horas, EA."""
        B, K, T, P = G.shape[0], self.K, SEM_TOTAL, N_RUTAS
        nf = len(self.idx_farm)
        E = np.zeros((T, B, P, K), np.float32)
        Eint = np.zeros((T, B, P), np.float32)
        Hdet = np.zeros((SEM_TX, B, P), np.float32)
        Cost = np.zeros((SEM_TX, B, P), np.float32)
        Hrs = np.zeros((SEM_TX, B, P), np.float32)
        Psae = np.zeros((SEM_TX, B, P, nf), np.float32)
        Pdisc = np.zeros((SEM_TX, B, P, nf), np.float32)
        Prot = np.ones((SEM_TX, B, P), np.float32)
        ar = np.arange(K)
        rm = self.RAMPA.shape[1]
        inter = self.i_bup is not None and self.i_ntx is not None
        ib, inn = self.i_bup, self.i_ntx
        dec_f = DECAE[CLASES.index("farmaco")]
        IB, IN = np.asarray(INTER_BUP, np.float32), np.asarray(INTER_NTX, np.float32)
        es_com = self.cls == I_COM
        e = np.zeros((B, P, K), np.float32)
        tau = np.zeros((B, P, K), np.int64)
        eint = np.zeros((B, P), np.float32)
        tau_i = np.zeros((B, P), np.int64)
        on = np.zeros((B, P, K), bool)
        cont = e_cont = None
        APK = self.paquetes_reales(G)
        for t in range(T):
            if t < SEM_TX:
                lev = APK[:, :, t // SEM_ETAPA, :]                               # (B, P, K)
                on = lev > 0
                tau = np.where(on, tau + 1, 0)
                ramp = self.RAMPA[ar, np.clip(tau - 1, 0, rm - 1)]
                target = self.MULT[ar, lev] * self.ADH[ar, lev]
                e = np.where(on, np.maximum(e * self.dec, target * ramp), e * self.dec)
                if inter:
                    lb, ln = lev[..., ib], lev[..., inn]
                    both = (lb > 0) & (ln > 0)
                    tau_i = np.where(both, tau_i + 1, 0)
                    ramp_i = self.RAMPA[ib, np.clip(tau_i - 1, 0, rm - 1)]
                    tint = IB[lb] * IN[ln] * self.ADH[ib, lb] * self.ADH[inn, ln]
                    eint = np.where(both, np.maximum(eint * dec_f, tint * ramp_i), eint * dec_f)
                hrs = self.HORAS[ar, lev].sum(-1) if K else np.zeros((B, P), np.float32)
                Hdet[t] = (self.loghr * on).sum(-1) + KAPPA_CARGA * np.maximum(0.0, hrs / self.horas_disp - UMBRAL_CARGA)
                Cost[t] = self.COSTO[ar, lev].sum(-1) if K else 0.0
                Hrs[t] = hrs
                for q, kd in enumerate(self.idx_farm):
                    Psae[t, ..., q] = self.EAG[kd, lev[..., kd]]
                    Pdisc[t, ..., q] = self.pdisc[kd] * on[..., kd]
                if inn is not None:
                    Prot[t] = self.prot_ntx[lev[..., inn]]
            else:
                if t == SEM_TX:
                    cont = on & es_com
                    e_cont = e * FRAC_COM_SEGUIMIENTO
                e = np.where(cont, e_cont, e * self.dec)
                eint = eint * dec_f
            E[t] = e
            Eint[t] = eint
        return dict(E=E, Eint=Eint, Hdet=Hdet, Cost=Cost, Hrs=Hrs, Psae=Psae, Pdisc=Pdisc, Prot=Prot)

    # ------------------------------------------------------------------ simulación Monte Carlo
    def simular(self, G, esc, curvas=False, bloque=None):
        G = np.asarray(G, np.int64)
        if G.ndim == 2:
            G = G[None]
        if bloque and G.shape[0] > bloque:
            partes = [self.simular(G[i:i + bloque], esc, curvas) for i in range(0, G.shape[0], bloque)]
            return {k: np.concatenate([p[k] for p in partes], 0) for k in partes[0]}
        B, T, K, P = G.shape[0], SEM_TOTAL, self.K, N_RUTAS
        D, I, N = esc.D, esc.I, esc.N
        BP = B * P
        X = self.exposicion(G)
        nf = len(self.idx_farm)
        di = esc.di
        # tablas planas por semana: índice = (genoma·P + ruta)·D + extracción
        E = X["E"].reshape(T, BP, K)
        if len(self.idx_nf):
            A_nd = np.einsum("tbk,dk->tbd", E[..., self.idx_nf], esc.W[:, self.idx_nf],
                             optimize=True).astype(np.float32).reshape(T, BP * D)
        else:
            A_nd = np.zeros((T, BP * D), np.float32)
        # tabla semanal empaquetada por (genoma, ruta): [Eint, Hdet, Costo, Horas, ProtNTX]
        SC = np.zeros((T, BP, 5), np.float32)
        SC[:, :, 0] = X["Eint"].reshape(T, BP)
        SC[:SEM_TX, :, 1] = X["Hdet"].reshape(SEM_TX, BP)
        SC[:SEM_TX, :, 2] = X["Cost"].reshape(SEM_TX, BP)
        SC[:SEM_TX, :, 3] = X["Hrs"].reshape(SEM_TX, BP)
        SC[:, :, 4] = 1.0
        SC[:SEM_TX, :, 4] = X["Prot"].reshape(SEM_TX, BP)
        # tabla de fármacos empaquetada: [exposición, P(EA grave), P(suspensión por EA)]
        FD = np.zeros((T, BP, nf, 3), np.float32)
        if nf:
            FD[..., 0] = E[..., self.idx_farm]
            FD[:SEM_TX, ..., 1] = X["Psae"].reshape(SEM_TX, BP, nf)
            FD[:SEM_TX, ..., 2] = X["Pdisc"].reshape(SEM_TX, BP, nf)
        Wf = esc.W[di][:, self.idx_farm][None]
        gam = esc.gamma[di][None]
        inter = self.i_bup is not None and self.i_ntx is not None
        base = ((esc.c[di] - esc.Rshift[di]).astype(np.float32) + esc.u)[None]
        s_ind = esc.s[di].astype(np.float32)[None]
        a = esc.a[None]
        h0 = np.float32(H0_ABANDONO + self.h_static)
        p_uso_tx = np.float32(self.p_otro)
        p_sob = np.float32(self.p_sob)
        rng = np.random.default_rng(esc.semilla_semanal)
        gP = (np.arange(B) * P)[:, None]
        dI = di[None, :]
        y_prev = np.full((B, N), self.y0, bool)
        in_tx = np.ones((B, N), bool)
        caido = np.zeros((B, N), bool)
        ruta = np.zeros((B, N), np.int64)
        fi = gP + ruta
        fid = fi * D + dI
        neg = np.zeros((B, N), np.int8)
        on_f = np.ones((B, N, nf), bool)
        S = np.zeros((B, N, len(CLASES)), np.float32)
        abst = np.zeros((B, N), np.int16)
        abst_tx = np.zeros((B, N), np.int16)
        sost = np.ones((B, N), bool)
        sost_tx = np.ones((B, N), bool)
        p_sum = np.zeros((B, N), np.float32)        # Σ_t P(abstinente_t | historia): Rao-Blackwell de % semanas
        p_sum_tx = np.zeros((B, N), np.float32)
        p_sost = np.ones((B, N), np.float32)        # P(abstinente sem 33-36 | historia a sem 32)
        costo = np.zeros((B, N), np.float32)
        costo_plan = np.zeros((B, N), np.float32)   # costo si completa el plan (no premia el abandono)
        horas = np.zeros((B, N), np.float32)
        sae = np.zeros((B, N), np.float32)
        resp = np.zeros((B, N, N_ETAPAS), bool)
        ret = np.zeros((B, N), bool)
        curva = np.zeros((B, T), np.float32) if curvas else None
        hay_caidos = False
        uno5 = np.ones(len(CLASES), np.float32)
        with np.errstate(divide="ignore"):
            for t in range(T):
                U = rng.random(N, dtype=np.float32)
                Ud = rng.random((2, N), dtype=np.float32)
                Uh = rng.random(N, dtype=np.float32)
                Ua = rng.random((max(nf, 1), N), dtype=np.float32)
                # comparación en escala logit: U < sig(z)  <=>  logit(U) < z  (evita exp sobre B×N)
                LU = (np.log(U) - np.log1p(-U))[None]
                sc = SC[t][fi]                                      # (B, N, 5)
                At = np.take(A_nd[t], fid)
                if nf:
                    fd = FD[t][fi]                                  # (B, N, nf, 3)
                    At += np.einsum('bnq,bnq->bn', fd[..., 0], Wf * on_f)
                    if inter:
                        At += gam * sc[..., 0] * (on_f[..., self.q_bup] & on_f[..., self.q_ntx])
                At *= a
                if hay_caidos:
                    S *= DECAE
                    At = np.where(caido, S @ uno5, At)
                z = base + s_ind * y_prev + At
                y = LU < z
                pz = sig(z)
                p_sum += pz
                if t >= T - SEM_SOST:
                    p_sost *= pz if t == T - SEM_SOST else sig(base + s_ind + At)
                abst += y
                uso = ~y
                if t >= T - SEM_SOST:
                    sost &= y
                if t < SEM_TX:
                    abst_tx += y
                    p_sum_tx += pz
                    if t >= SEM_TX - SEM_SOST:
                        sost_tx &= y
                    j, w = divmod(t, SEM_ETAPA)
                    if w >= SEM_ETAPA - 2:
                        neg += in_tx & (y | (Ud[0] > P_DET))
                        neg += in_tx & (y | (Ud[1] > P_DET))
                    if self.i_ntx is not None:
                        prot = np.where(in_tx & on_f[..., self.q_ntx], sc[..., 4], np.float32(1.0))
                    else:
                        prot = np.float32(1.0)
                    sae += uso * (p_uso_tx + p_sob * prot)
                    if nf:
                        sae += in_tx * np.einsum('bnq,bnq->bn', fd[..., 1], on_f.astype(np.float32))
                        on_f &= ~(in_tx[..., None] & (Ua[:nf].T[None] < fd[..., 2]))
                    costo += in_tx * sc[..., 2]
                    costo_plan += sc[..., 2]
                    horas += in_tx * sc[..., 3]
                    LUh = (np.log(Uh) - np.log1p(-Uh))[None]
                    drop = in_tx & (LUh < h0 + 0.4 * uso + sc[..., 1])
                    if drop.any():
                        bb, ii = np.nonzero(drop)
                        fbi = fi[bb, ii]
                        contrib = E[t][fbi] * esc.W[di[ii]]
                        if nf:
                            contrib[:, self.idx_farm] *= on_f[bb, ii]
                        Sd = contrib @ self.CL
                        if inter:
                            Sd[:, CLASES.index("farmaco")] += (esc.gamma[di[ii]] * SC[t][fbi, 0]
                                                              * (on_f[bb, ii, self.q_bup] & on_f[bb, ii, self.q_ntx]))
                        S[bb, ii] = esc.a[ii, None] * Sd
                        in_tx &= ~drop
                        caido |= drop
                        hay_caidos = True
                    if w == SEM_ETAPA - 1:
                        r = (neg >= 3) & in_tx
                        resp[..., j] = r
                        if j < N_ETAPAS - 1:
                            ruta |= r.astype(np.int64) << j
                            fi = gP + ruta
                            fid = fi * D + dI
                        neg[:] = 0
                    if t == SEM_TX - 1:
                        ret = in_tx.copy()
                else:
                    sae += uso * (p_uso_tx + p_sob)
                y_prev = y
                if curvas:
                    curva[:, t] = pz.mean(1)

        def pd(x):
            return x.reshape(B, D, I).mean(-1).astype(np.float64)

        met = {
            "sost": pd(p_sost), "sost_obs": pd(sost), "sost_tx": pd(sost_tx), "pct": pd(p_sum / T),
            "pct_tx": pd(p_sum_tx / SEM_TX), "pct_seg": pd((p_sum - p_sum_tx) / SEM_SEG), "ret24": pd(ret),
            "resp1": pd(resp[..., 0]), "resp4": pd(resp[..., -1]), "costo": pd(costo_plan),
            "costo_esperado": pd(costo), "horas": pd(horas),
            "sae": pd(sae) * 1000.0,
        }
        if curvas:
            met["curva"] = curva
        self.n_evals += B
        return met

    # ------------------------------------------------------------------ objetivos (con caché)
    def objetivos(self, genomas):
        """F (n, 3) a minimizar: −P(sostenida), −% semanas abstinentes, costo planeado del régimen (MXN)."""
        F = np.zeros((len(genomas), 3))
        faltan = []
        for i, g in enumerate(genomas):
            k = g.tobytes()
            if k in self.cache:
                F[i] = self.cache[k]
            else:
                faltan.append(i)
        if faltan:
            uniq, inv = {}, []
            for i in faltan:
                inv.append(uniq.setdefault(genomas[i].tobytes(), len(uniq)))
            G = np.stack([genomas[faltan[inv.index(u)]] for u in range(len(uniq))])
            met = self.simular(G, self.esc_busqueda, bloque=128)
            Fn = np.stack([-met["sost"].mean(1), -met["pct"].mean(1), met["costo"].mean(1)], 1)
            for k, u in uniq.items():
                self.cache[k] = Fn[u]
            for i, u in zip(faltan, inv):
                F[i] = Fn[u]
        return F
