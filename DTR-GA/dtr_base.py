# -*- coding: utf-8 -*-
"""
DTR-GA v2 · Módulo base: diseño temporal, base de evidencia y esquema del perfil del sujeto.

Convenciones
------------
* Escala del modelo: log-odds CONDICIONAL de "semana abstinente" (sin consumo de metanfetamina),
  en un modelo con intercepto aleatorio por persona y dependencia de estado (ver dtr_calibracion.py).
* Las OR publicadas (abstinencia/respuesta al final del tratamiento) se convierten a efecto semanal
  por EMULACIÓN DEL ENSAYO en dtr_calibracion.py. Nunca se teclean coeficientes semanales a mano.
* Los términos de riesgo del sujeto son PRIORS DE EXPERTO (media, DE) en esa misma escala.
* Nivel 0 de cada componente = no usar.
* Costos en MXN/semana: ILUSTRATIVOS. Reemplazar en el bloque "contexto" del JSON de entrada.
"""
import json
import math
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

VERSION = "2.0.0"

# =============================================================================
# 1. DISEÑO TEMPORAL DEL RÉGIMEN DINÁMICO (tipo SMART / ADAPT-2)
# =============================================================================
SEM_ETAPA = 6                     # semanas por etapa (ADAPT-2 usó etapas de 6 semanas)
N_ETAPAS = 4
SEM_TX = SEM_ETAPA * N_ETAPAS     # 24 semanas de programa estructurado
SEM_SEG = 12                      # 12 semanas de seguimiento sin programa
SEM_TOTAL = SEM_TX + SEM_SEG      # 36
N_PAQ = 1 + 2 * (N_ETAPAS - 1)    # 7 paquetes: E1, (E2 R/NR), (E3 R/NR), (E4 R/NR)
ETIQ_PAQ = [
    "Etapa 1 · sem 1-6 · inicio",
    "Etapa 2 · sem 7-12 · si RESPONDE", "Etapa 2 · sem 7-12 · si NO responde",
    "Etapa 3 · sem 13-18 · si RESPONDE", "Etapa 3 · sem 13-18 · si NO responde",
    "Etapa 4 · sem 19-24 · si RESPONDE", "Etapa 4 · sem 19-24 · si NO responde",
]

# Clases de persistencia del efecto al suspender un componente (vida media en semanas)
#   farmaco: lavado rápido · contingencia: el incentivo deja de operar al retirarlo (Ronsley 2020:
#   OR 1.10 en seguimiento largo) — lo durable pasa por la dependencia de estado · habilidades:
#   persisten (efecto "durmiente" de TCC/CRA) · comunitario: depende de seguir asistiendo.
CLASES = ["farmaco", "contingencia", "habilidades", "comunitario", "neuromod"]
VIDA_MEDIA_SEM = np.array([0.5, 2.0, 20.0, 3.0, 4.0])
DECAE = np.exp(-np.log(2.0) / VIDA_MEDIA_SEM).astype(np.float32)
I_COM = CLASES.index("comunitario")
FRAC_COM_SEGUIMIENTO = 0.7        # fracción de lo comunitario que continúa tras el programa

# Objetivos de calibración (fuentes verificadas)
OBJ = dict(
    # Trivedi et al., NEJM 2021;384:140-153 (ADAPT-2). Respuesta = >=3/4 antidopings negativos en las
    # 2 semanas finales de la etapa (2/semana). Etapa 1: 18/109 vs 10/294. Etapa 2: 13/114 vs 2/111.
    adapt2_placebo_e1=10 / 294, adapt2_activo_e1=18 / 109,
    adapt2_n=(109, 294),
    adapt2_e2_conteos=((2, 111), (13, 114)),       # (respondedores, n) placebo, activo
    # Recaída ~60% en el primer año post-tratamiento (Brecht & Herbeck, DAD 2014). APROXIMADO.
    recaida_12m=0.60,
)
SIGMA_U = 1.0       # heterogeneidad individual por defecto (la calibración la estima)
R2_PERFIL = 0.30    # fracción de la heterogeneidad individual que el perfil explica (supuesto)
P_DET = 0.80        # prob. de que un antidoping detecte una semana con consumo
H0_ABANDONO = math.log(0.035 / 0.965)   # abandono semanal basal ~3.5%
BETA_FREQ = 0.06    # log-odds por día de uso (últimos 30) respecto a DIAS_REF
DIAS_REF = 24       # uso típico en ensayos recientes (~24/28 días; ADAPT-2 exigió >=18/30)
MAX_FARMACOS = 2    # parsimonia: máximo de fármacos simultáneos para TUM (configurable)


# =============================================================================
# 2. BASE DE EVIDENCIA
# =============================================================================
@dataclass
class Componente:
    clave: str
    nombre: str
    tipo: str                  # farmaco | psicosocial | entorno | neuromod
    clase: str                 # clase de persistencia
    niveles: List[str]         # nivel 0 = no usar
    mult: List[float]          # dosis-respuesta relativa (1.0 = condición del ensayo)
    or_ensayo: Optional[Tuple[float, float, float]]   # OR (IC95) vs control; None = solo mecanismo
    grado: str                 # A metaanálisis/varios ECA · B ECA sólido · C inconsistente · D nula
    evidencia: str
    rampa: List[float]         # fracción del efecto en semanas 1..k tras iniciar
    horas: List[float]         # horas/semana del paciente, por nivel
    costo: List[float]         # MXN/semana por nivel (ILUSTRATIVO)
    adh: List[float]           # adherencia relativa al ensayo (1.0 = igual), por nivel
    presencial: bool
    loghr_ret: float           # efecto sobre abandono (log-HR; negativo = mejor retención)
    ea_leve: float             # prob. semanal de EA leve/moderado (fármacos)
    ea_grave: List[float]      # prob. semanal de EA grave, por nivel
    mecanismos: Dict[str, float] = field(default_factory=dict)  # fracción de reducción por término de riesgo
    por_defecto: bool = True   # False = solo en --modo-investigacion
    reduce_abandono: float = 0.0  # fracción de la carga de abandono por ACCESO (transporte, distancia, vivienda) que elimina

    def __post_init__(self):
        n = len(self.niveles)
        for nombre in ("mult", "horas", "costo", "adh", "ea_grave"):
            if len(getattr(self, nombre)) != n:
                raise ValueError(f"{self.clave}.{nombre} debe tener {n} valores")


COMPONENTES: List[Componente] = [
    Componente("cm", "Manejo de contingencias (MC)", "psicosocial", "contingencia",
               ["no", "estándar (premios; antidoping 2-3/sem)", "alta magnitud (vales escalonados)"],
               [0, 1.0, 1.25], (2.2, 1.6, 3.1), "A",
               "Intervención con mejor evidencia en estimulantes: abstinencia al final del tratamiento "
               "OR 2.22 (IC95 1.59-3.10) y a 12 semanas OR 2.29 (Ronsley 2020, revisión de revisiones); "
               "en metanfetamina, Roll 2006 y Brown & DeFulio 2020. El efecto NO se sostiene al retirar "
               "incentivos (seguimiento largo OR 1.10, IC95 0.83-1.46). Mayor magnitud, mayor efecto.",
               [1.0], [0, 2.0, 2.0], [0, 550, 950], [0, 0.95, 0.95], True, -0.35, 0.0, [0, 0, 0]),
    Componente("cra", "Reforzamiento comunitario (CRA)", "psicosocial", "habilidades",
               ["no", "CRA semanal"], [0, 1.0], (1.5, 0.95, 2.4), "B",
               "De Crescenzo 2018 (NMA, 50 ECA): CRA eficaz en el seguimiento más largo; MC+CRA fue la "
               "única combinación superior al tratamiento usual al final del tratamiento (OR 2.84, IC95 "
               "1.24-6.51) y la más aceptable. Trabaja red, empleo, familia y recreación.",
               [0.4, 0.7], [0, 1.5], [0, 600], [0, 0.85], True, -0.15, 0.0, [0, 0],
               {"red": 0.25, "empleo": 0.25, "familia": 0.20}),
    Componente("tcc", "Terapia cognitivo-conductual / Modelo Matrix", "psicosocial", "habilidades",
               ["no", "TCC semanal", "Matrix IOP (3 sesiones/sem)"],
               [0, 1.0, 1.45], (1.25, 0.9, 1.75), "B",
               "Rawson 2004 (Addiction): Matrix > usual durante el tratamiento, sin ventaja sostenida. "
               "TCC sola: efectos modestos (De Crescenzo 2018: MC+CRA superior a TCC).",
               [0.4, 0.7, 0.9], [0, 1.5, 9.0], [0, 600, 1800], [0, 0.85, 0.75], True, -0.05, 0.0, [0, 0, 0],
               {"motivacion": 0.15, "depresion": 0.10}),
    Componente("em", "Entrevista motivacional / MET", "psicosocial", "habilidades",
               ["no", "EM/MET (2-4 sesiones por etapa)"], [0, 1.0], (1.1, 0.85, 1.45), "B",
               "Efecto directo pequeño; mejora enganche y retención, sobre todo en precontemplación/contemplación.",
               [1.0], [0, 0.5], [0, 250], [0, 0.9], True, -0.15, 0.0, [0, 0],
               {"motivacion": 0.40}),
    Componente("ejer", "Ejercicio aeróbico estructurado", "psicosocial", "comunitario",
               ["no", "3 sesiones/sem supervisadas"], [0, 1.0], (1.2, 0.85, 1.7), "C",
               "Rawson 2015 (DAD): menor recaída solo en usuarios de menor severidad (<=18 de 30 días); "
               "efecto global incierto. Beneficio en ánimo y ansiedad.",
               [0.3, 0.6], [0, 3.0], [0, 100], [0, 0.7], False, -0.05, 0.03, [0, 2e-5],
               {"depresion": 0.15}),
    Componente("pares", "Ayuda mutua / grupo de pares + facilitación 12 pasos", "entorno", "comunitario",
               ["no", "2-3 reuniones/sem + padrino o mentor"], [0, 1.0], (1.2, 0.9, 1.6), "C",
               "STAGE-12 (Donovan 2013, CTN): aumenta participación en 12 pasos; efecto en estimulantes "
               "modesto e inconsistente. Valor principal: red libre de consumo; costo cero.",
               [0.5], [0, 3.0], [0, 0], [0, 0.65], True, -0.10, 0.0, [0, 0],
               {"red": 0.30}),
    Componente("fam", "Terapia familiar / de pareja conductual", "entorno", "habilidades",
               ["no", "sesión semanal o quincenal"], [0, 1.0], (1.1, 0.85, 1.45), "C",
               "Evidencia sólida en alcohol/opioides; en estimulantes es extrapolada. Requiere familia "
               "disponible y ausencia de violencia de pareja.",
               [0.3, 0.6], [0, 1.0], [0, 400], [0, 0.75], True, -0.10, 0.0, [0, 0],
               {"familia": 0.50, "pareja": 0.30}),
    Componente("trauma", "Terapia de trauma integrada (p. ej., COPE)", "psicosocial", "habilidades",
               ["no", "semanal (12-16 sesiones)"], [0, 1.0], None, "B",
               "Mills 2012 (JAMA, COPE): reduce severidad del TEPT sin empeorar el consumo. Efecto sobre "
               "consumo modelado solo vía reducción del riesgo atribuible al TEPT.",
               [0.2, 0.4, 0.6, 0.8], [0, 1.5], [0, 700], [0, 0.7], True, 0.05, 0.0, [0, 0],
               {"tept": 0.50}),
    Componente("viv", "Vivienda de recuperación (voluntaria, libre de sustancias)", "entorno", "comunitario",
               ["no", "residencia de recuperación"], [0, 1.0], None, "B",
               "Jason 2006 (AJPH, Oxford House): menor consumo a 24 meses. NO equivale a internamiento "
               "involuntario ('anexo'): la evidencia es para residencias voluntarias y autogestionadas.",
               [0.6], [0, 2.0], [0, 1500], [0, 0.85], False, -0.20, 0.0, [0, 0],
               {"vivienda": 0.70, "convivencia": 0.85}),
    Componente("voc", "Apoyo vocacional (empleo con apoyo, tipo IPS)", "entorno", "habilidades",
               ["no", "especialista de empleo semanal"], [0, 1.0], None, "C",
               "Evidencia moderada para empleo en TUS; efecto sobre consumo solo vía estabilidad laboral.",
               [0.2, 0.4, 0.6, 0.8], [0, 1.5], [0, 250], [0, 0.7], True, -0.05, 0.0, [0, 0],
               {"empleo": 0.60}),
    Componente("gestion", "Gestión de caso + apoyo de transporte", "entorno", "comunitario",
               ["no", "gestor de caso semanal + apoyo de traslado"], [0, 1.0], None, "B",
               "Metaanálisis de 21 ECA (Vanderplasschen, Rapp et al., Front Psychiatry 2019): mejora la retención "
               "en tratamiento (SMD 0.47, IC95 0.13-0.81) y la vinculación (SMD 0.23); sin efecto significativo "
               "directo sobre el consumo (SMD 0.10, IC95 -0.02-0.21). Se modela solo vía retención, mayor cuando "
               "hay barreras de acceso.",
               [1.0], [0, 0.5], [0, 350], [0, 0.9], False, -0.30, 0.0, [0, 0],
               {"vivienda": 0.15}, reduce_abandono=0.6),
    Componente("bup", "Bupropión XL", "farmaco", "farmaco",
               ["no", "150 mg/día", "300 mg/día", "450 mg/día"], [0, 0.5, 1.0, 1.1], (1.3, 0.85, 2.0), "C",
               "Monoterapia inconsistente: señal post hoc en uso de menor frecuencia (Elkashef 2008; "
               "Shoptaw 2008) que NO se confirmó prospectivamente (Heinzerling 2014). Su papel fuerte es "
               "dentro de la combinación de ADAPT-2 (450 mg + naltrexona XR).",
               [0.4, 0.8], [0, 0.2, 0.2, 0.2], [0, 120, 220, 330], [0, 0.75, 0.75, 0.75], False, 0.0, 0.10,
               [0, 1e-5, 4e-5, 1.5e-4], {"depresion": 0.40}),
    Componente("ntx", "Naltrexona", "farmaco", "farmaco",
               ["no", "oral 50 mg/día", "XR 380 mg IM c/3 sem"], [0, 1.0, 1.0], (1.2, 0.8, 1.8), "C",
               "Monoterapia en (met)anfetamina inconsistente (Jayaram-Lindström 2008 positivo; Coffin 2018 y "
               "Runarsdottir 2017 nulos). Evidencia sólida en alcohol. Combinación con bupropión: ADAPT-2.",
               [1.0], [0, 0.2, 0.3], [0, 250, 8000], [0, 0.6, 0.95], False, 0.0, 0.07,
               [0, 1e-5, 3e-5], {"alcohol": 0.40}),
    Componente("mirt", "Mirtazapina", "farmaco", "farmaco",
               ["no", "15 mg/noche", "30 mg/noche"], [0, 0.6, 1.0], (1.2, 0.95, 1.5), "B",
               "Fase 3 en práctica rutinaria (McKetin, JAMA Psychiatry 2026; n=344): ~2.2 días menos de uso "
               "en 12 semanas, efecto modesto; ~25% suspendió por EA (vs 15% placebo). Efecto mayor en "
               "HSH/mujeres trans (Coffin 2020: RR 0.67 de antidoping positivo a 12 semanas).",
               [0.3, 0.7], [0, 0.2, 0.2], [0, 40, 60], [0, 0.65, 0.65], False, 0.05, 0.12,
               [0, 1e-5, 2e-5], {"depresion": 0.35}),
    Componente("rtms", "EMTr (rTMS) 10 Hz CPFDL izquierda", "neuromod", "neuromod",
               ["no", "curso de 20 sesiones"], [0, 1.0], (1.25, 0.85, 1.85), "C",
               "Ensayos pequeños: reduce craving; efecto sobre consumo poco establecido. Costo y carga altos.",
               [0.5, 0.8], [0, 5.0], [0, 4000], [0, 0.85], True, 0.05, 0.10, [0, 1e-5]),
    Componente("mod", "Modafinilo (solo investigación)", "farmaco", "farmaco",
               ["no", "200 mg/día"], [0, 1.0], (1.05, 0.75, 1.45), "D",
               "ECA sin eficacia global en TUM (Anderson 2012; Heinzerling 2010).",
               [0.5], [0, 0.2], [0, 400], [0, 0.6], False, 0.0, 0.08, [0, 1e-5], por_defecto=False),
    Componente("nac", "N-acetilcisteína (solo investigación)", "farmaco", "farmaco",
               ["no", "2400 mg/día"], [0, 1.0], (1.0, 0.7, 1.4), "D",
               "ECA negativo en dependencia a metanfetamina (McKetin 2021, EClinicalMedicine).",
               [0.5], [0, 0.2], [0, 150], [0, 0.6], False, 0.0, 0.05, [0, 0], por_defecto=False),
]

IDX = {c.clave: i for i, c in enumerate(COMPONENTES)}
NC = len(COMPONENTES)
LMAX = max(len(c.niveles) for c in COMPONENTES)
FARMACOS = [c.clave for c in COMPONENTES if c.tipo == "farmaco"]

# Interacción BUP×NTX: reconcilia el efecto de la combinación (ADAPT-2) con la suma de
# monoterapias de otros ensayos. NO es sinergia farmacológica demostrada (ADAPT-2 no tuvo
# brazos de monoterapia). Factores por nivel: 150 mg no estudiado; 300 mg y NTX oral = extrapolación.
INTER_BUP = [0.0, 0.0, 0.6, 1.0]
INTER_NTX = [0.0, 0.85, 1.0]

# Términos de riesgo del sujeto (escala log-odds semanal; positivo = más riesgo)
RIESGOS = ["depresion", "tept", "vivienda", "convivencia", "pareja", "red", "familia",
           "empleo", "alcohol", "motivacion", "tdah", "frecuencia", "otros"]
# Valor promedio de cada término (parte positiva) en participantes tipo ADAPT-2 (supuesto, centrado).
# El intercepto calibrado corresponde a esta carga; el sujeto se desplaza por (R_sujeto - R_REF).
REF_RIESGO = {"depresion": 0.14, "tept": 0.10, "vivienda": 0.11, "convivencia": 0.06, "pareja": 0.06,
              "red": 0.24, "familia": 0.05, "empleo": 0.10, "alcohol": 0.04, "motivacion": 0.05,
              "tdah": 0.03, "frecuencia": 0.0, "otros": 0.45}
R_REF = float(sum(REF_RIESGO.values()))


# =============================================================================
# 3. ESQUEMA DEL PERFIL DEL SUJETO (entrada)
# =============================================================================
# campo: (valor por defecto, tipo o lista de opciones, documentación)
ESQUEMA = {
    # --- Identificación / demografía
    "id": ("SUJ-000", str, "Identificador anónimo (sin nombre)"),
    "edad": (30, int, "Años. Alcance: adultos >= 18"),
    "sexo": ("M", ["M", "F", "otro"], "Sexo"),
    "hsh_o_mujer_trans": (False, bool, "HSH o mujer trans (población de Coffin 2020: mayor efecto de mirtazapina)"),
    "embarazo": (False, bool, "Embarazo actual"),
    "lactancia": (False, bool, "Lactancia actual"),
    # --- Patrón de consumo (ASAM D1/D4)
    "via_administracion": ("fumada", ["fumada", "inyectada", "intranasal", "oral"], "Vía principal"),
    "dias_uso_ultimos_30": (20, int, "Días con consumo en los últimos 30 (TLFB)"),
    "anios_uso": (5.0, float, "Años de consumo regular"),
    "criterios_dsm5": (7, int, "Criterios DSM-5 de TUM cumplidos (0-11; >=6 severo)"),
    "dias_abstinencia_actual": (0, int, "Días desde el último consumo"),
    "craving_eva": (6, int, "Craving 0-10 (escala visual análoga)"),
    "uso_opioides_actual": (False, bool, "Uso actual de opioides (heroína, fentanilo, analgésicos sin receta)"),
    "tratamiento_agonista_opioide": (False, bool, "En tratamiento con metadona o buprenorfina"),
    "antecedente_uso_opioides": (False, bool, "Uso previo de opioides"),
    "riesgo_fentanilo_suministro": (True, bool, "Posible fentanilo en el suministro local"),
    "antecedente_sobredosis": (False, bool, "Sobredosis previa"),
    "audit": (5, int, "AUDIT alcohol (0-40; 8-15 riesgo, >=16 perjudicial/dependencia)"),
    "alcohol_diario_intenso": (False, bool, "Consumo diario intenso de alcohol (riesgo de abstinencia complicada)"),
    "benzodiacepinas_diarias": (False, bool, "Uso diario de benzodiacepinas"),
    "tabaco_cigarros_dia": (0, int, "Cigarros/día"),
    "conducta_sexual_riesgo": (False, bool, "Chemsex, ITS reciente o sexo sin protección con parejas múltiples"),
    # --- Biomédico (D2)
    "antecedente_convulsiones": (False, bool, "Cualquier convulsión previa (incluida inducida por sustancias)"),
    "tce_moderado_severo": (False, bool, "Traumatismo craneoencefálico moderado/severo"),
    "trastorno_alimentario": (False, bool, "Bulimia o anorexia, actual o previa"),
    "enfermedad_hepatica": ("ninguna", ["ninguna", "leve", "moderada", "severa", "hepatitis_aguda"], "Hepatopatía"),
    "alt_veces_lsn": (1.0, float, "ALT en múltiplos del límite superior normal"),
    "hipertension_no_controlada": (False, bool, "HTA no controlada"),
    "cardiopatia": (False, bool, "Miocardiopatía, arritmia o cardiopatía isquémica"),
    "qtc_prolongado": (False, bool, "QTc prolongado en ECG"),
    "sintomas_cardiacos_agudos": (False, bool, "Dolor torácico, disnea, síncope o palpitaciones actuales"),
    "implantes_metalicos_cabeza": (False, bool, "Metal intracraneal / implante coclear"),
    "dispositivo_implantado": (False, bool, "Marcapasos, DAI u otro estimulador"),
    "vih": ("desconocido", ["negativo", "positivo_en_tratamiento", "positivo_sin_tratamiento", "desconocido"], "VIH"),
    "vhc": ("desconocido", ["negativo", "positivo", "desconocido"], "Hepatitis C"),
    "dolor_cronico_con_opioides": (False, bool, "Dolor crónico que requiere analgesia opioide"),
    "uso_imao_14d": (False, bool, "IMAO en los últimos 14 días"),
    # --- Psiquiátrico / cognitivo (D3)
    "phq9": (8, int, "PHQ-9 (0-27; >=10 moderado, >=20 severo)"),
    "riesgo_suicida": ("bajo", ["ninguno", "bajo", "moderado", "alto"], "Riesgo suicida (p. ej., C-SSRS)"),
    "psicosis_actual": (False, bool, "Síntomas psicóticos actuales"),
    "psicosis_inducida_previa": (False, bool, "Psicosis inducida por estimulantes previa"),
    "trastorno_bipolar": (False, bool, "Trastorno bipolar diagnosticado"),
    "tdah": (False, bool, "TDAH diagnosticado"),
    "pcl5": (10, int, "PCL-5 (0-80; >=33 TEPT probable)"),
    "tept_complejo": (False, bool, "TEPT complejo (p. ej., ITQ / evaluación tipo TRAZA)"),
    "ace": (2, int, "Experiencias adversas en la infancia (0-10)"),
    "gad7": (6, int, "GAD-7 (0-21)"),
    "deterioro_cognitivo": (False, bool, "Deterioro cognitivo clínicamente relevante"),
    # --- Persona / motivación / recursos (D6)
    "etapa_cambio": ("preparacion", ["precontemplacion", "contemplacion", "preparacion", "accion", "mantenimiento"],
                     "Etapa de cambio"),
    "tratamientos_previos": (0, int, "Episodios de tratamiento previos"),
    "rechaza_medicacion": (False, bool, "No acepta fármacos"),
    "rechaza_inyectables": (False, bool, "No acepta inyectables"),
    "rechaza_grupos": (False, bool, "No acepta grupos"),
    "horas_disponibles_semana": (8.0, float, "Horas/semana disponibles para tratamiento (incluye traslados)"),
    "transporte": (True, bool, "Tiene transporte viable"),
    "minutos_a_clinica": (20, int, "Minutos de traslado (un sentido)"),
    "presupuesto_semanal_mxn": (1500.0, float, "Presupuesto máximo por semana (MXN)"),
    # --- Entorno de recuperación (D5)
    "vivienda": ("estable", ["estable", "inestable", "calle"], "Situación de vivienda"),
    "convive_con_usuarios": (False, bool, "Vive con personas que consumen"),
    "pareja_usuaria": (False, bool, "Pareja que consume"),
    "violencia_pareja": (False, bool, "Violencia de pareja"),
    "red_consumo_prop": (0.3, float, "Proporción de su red cercana que consume (0-1)"),
    "apoyo_familiar": ("moderado", ["ninguno", "bajo", "moderado", "alto"], "Apoyo familiar"),
    "familia_dispuesta": (True, bool, "Familia dispuesta a participar"),
    "empleo": ("informal", ["formal", "informal", "desempleado", "estudiante"], "Situación laboral"),
    "supervision_judicial": (False, bool, "Bajo supervisión judicial"),
    "disponibilidad_sustancia": ("media", ["baja", "media", "alta"], "Disponibilidad de la sustancia en su entorno"),
    "grupos_ayuda_mutua_locales": (True, bool, "Hay grupos de ayuda mutua accesibles"),
}

RANGOS = {"edad": (18, 100), "dias_uso_ultimos_30": (0, 30), "criterios_dsm5": (0, 11),
          "craving_eva": (0, 10), "audit": (0, 40), "phq9": (0, 27), "pcl5": (0, 80), "ace": (0, 10),
          "gad7": (0, 21), "red_consumo_prop": (0, 1), "horas_disponibles_semana": (0.5, 80),
          "presupuesto_semanal_mxn": (0, 1e7), "alt_veces_lsn": (0.1, 50), "anios_uso": (0, 70),
          "dias_abstinencia_actual": (0, 100000), "tratamientos_previos": (0, 100),
          "tabaco_cigarros_dia": (0, 200), "minutos_a_clinica": (0, 1000)}

# Contexto local (México por defecto). Ajustable en el JSON, bloque "contexto".
CONTEXTO_DEFECTO = {
    "disponibles": {c.clave: True for c in COMPONENTES},
    # niveles no disponibles en el formulario local: {clave: [niveles]}
    # NTX-XR: sin evidencia de comercialización regular en México → no disponible por defecto.
    "niveles_no_disponibles": {"ntx": [2]},
    "costos_mxn": {},          # {clave: [costo por nivel]} reemplaza los ilustrativos
    "max_farmacos": MAX_FARMACOS,
}


class Perfil(dict):
    """Perfil validado con acceso por atributo (p.edad)."""
    def __getattr__(self, k):
        try:
            return self[k]
        except KeyError as e:
            raise AttributeError(k) from e


def validar_perfil(datos: dict) -> Tuple[Perfil, List[str]]:
    avisos, errores, p = [], [], {}
    for campo, (defecto, tipo, _) in ESQUEMA.items():
        v = datos.get(campo, defecto)
        if campo not in datos:
            avisos.append(f"'{campo}' no especificado → se usa {defecto!r}")
        if isinstance(tipo, list):
            if v not in tipo:
                errores.append(f"'{campo}'={v!r} no válido; opciones: {tipo}")
        elif tipo is bool:
            if not isinstance(v, bool):
                errores.append(f"'{campo}' debe ser true/false")
        elif tipo in (int, float):
            if isinstance(v, bool):
                errores.append(f"'{campo}' debe ser numérico")
                continue
            try:
                v = tipo(v)
            except (TypeError, ValueError):
                errores.append(f"'{campo}' debe ser numérico")
                continue
            if campo in RANGOS and not (RANGOS[campo][0] <= v <= RANGOS[campo][1]):
                errores.append(f"'{campo}'={v} fuera de rango {RANGOS[campo]}")
        p[campo] = v
    for k in datos:
        if k not in ESQUEMA and not k.startswith("_"):
            avisos.append(f"campo desconocido ignorado: '{k}'")
    if isinstance(p.get("edad"), (int, float)) and not isinstance(p.get("edad"), bool) and p["edad"] < 18:
        errores.append("Fuera de alcance: el sistema es para adultos (>= 18 años).")
    if errores:
        raise ValueError("Perfil inválido:\n  - " + "\n  - ".join(errores))
    return Perfil(p), avisos


def fusionar_contexto(ctx_usuario: dict) -> dict:
    ctx = json.loads(json.dumps(CONTEXTO_DEFECTO))
    for k, v in (ctx_usuario or {}).items():
        if isinstance(v, dict) and isinstance(ctx.get(k), dict):
            ctx[k].update(v)
        else:
            ctx[k] = v
    for k, costos in ctx.get("costos_mxn", {}).items():
        if k not in IDX:
            raise ValueError(f"contexto.costos_mxn: componente desconocido '{k}'")
        if len(costos) != len(COMPONENTES[IDX[k]].niveles):
            raise ValueError(f"contexto.costos_mxn.{k} debe tener {len(COMPONENTES[IDX[k]].niveles)} valores")
    return ctx


def cargar_entrada(ruta: str):
    with open(ruta, encoding="utf-8") as f:
        datos = json.load(f)
    perfil_raw = datos.get("perfil", datos)
    ctx = fusionar_contexto(datos.get("contexto", {}))
    perfil, avisos = validar_perfil(perfil_raw)
    return perfil, ctx, avisos


def plantilla() -> dict:
    return {
        "perfil": {k: v[0] for k, v in ESQUEMA.items()},
        "contexto": CONTEXTO_DEFECTO,
        "_documentacion": {k: (v[2] + (f" | opciones: {v[1]}" if isinstance(v[1], list) else ""))
                           for k, v in ESQUEMA.items()},
    }
