# -*- coding: utf-8 -*-
"""
DTR-GA v2 · Capa 1: MOTOR CLÍNICO DETERMINISTA.

Reglas explícitas y auditables (no aprendidas). Producen:
  - alertas urgentes  → interlock: bloquean la optimización ambulatoria
  - estado por componente: PERMITIDO / PRECAUCION / BLOQUEADO / ESPECIALISTA /
    NO_INDICADO / NO_DISPONIBLE / RECHAZADO / EXCLUIDO_EVIDENCIA, con nivel máximo
  - componentes obligatorios no optimizables (reducción de daños, tamizajes)
  - severidad 0-4 en 6 dimensiones y nivel de atención (aproximación inspirada en
    ASAM 4ª ed.; NO es el algoritmo oficial)
  - parámetros personalizados para el simulador (riesgos, modificadores, EA, acceso)
Fuentes de contraindicaciones: fichas técnicas (bupropión, naltrexona, mirtazapina),
guías de seguridad de EMT, y evidencia citada en dtr_base.py.
"""
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

from dtr_base import BETA_FREQ, COMPONENTES, DIAS_REF, FARMACOS, Perfil

ORDEN = {"BLOQUEADO": 7, "ESPECIALISTA": 6, "RECHAZADO": 5, "NO_DISPONIBLE": 4,
         "NO_INDICADO": 3, "EXCLUIDO_EVIDENCIA": 2, "PRECAUCION": 1, "PERMITIDO": 0}
BUSCABLES = ("PERMITIDO", "PRECAUCION")


@dataclass
class Evaluacion:
    urgentes: List[str] = field(default_factory=list)
    prioritarias: List[str] = field(default_factory=list)
    dimensiones: Dict[str, Tuple[int, List[str]]] = field(default_factory=dict)
    nivel_atencion: str = ""
    estado: Dict[str, dict] = field(default_factory=dict)
    obligatorios: List[str] = field(default_factory=list)
    notas: List[str] = field(default_factory=list)

    def buscable(self, clave: str) -> bool:
        e = self.estado[clave]
        return e["estado"] in BUSCABLES and e["nivel_max"] >= 1


def evaluar_clinico(p: Perfil, ctx: dict, investigacion: bool = False) -> Evaluacion:
    ev = Evaluacion()
    for c in COMPONENTES:
        ev.estado[c.clave] = {"estado": "PERMITIDO", "nivel_max": len(c.niveles) - 1,
                              "razones": [], "estudios": [], "monitoreo": []}

    def marcar(k, est, razon):
        e = ev.estado[k]
        if ORDEN[est] > ORDEN[e["estado"]]:
            e["estado"] = est
        if est not in BUSCABLES:
            e["nivel_max"] = 0
        e["razones"].append(razon)

    def limitar(k, nmax, razon):
        e = ev.estado[k]
        e["nivel_max"] = min(e["nivel_max"], nmax)
        if e["estado"] == "PERMITIDO":
            e["estado"] = "PRECAUCION"
        e["razones"].append(razon)

    def prec(k, razon, estudio=None, monitoreo=None):
        e = ev.estado[k]
        if e["estado"] == "PERMITIDO":
            e["estado"] = "PRECAUCION"
        e["razones"].append(razon)
        if estudio:
            e["estudios"].append(estudio)
        if monitoreo:
            e["monitoreo"].append(monitoreo)

    alcohol_riesgo = p.audit >= 16
    abst_complicada = p.alcohol_diario_intenso or p.benzodiacepinas_diarias
    hep_grave = p.enfermedad_hepatica in ("severa", "hepatitis_aguda")
    tept = p.pcl5 >= 33 or p.tept_complejo

    # ------------------------------------------------------------------ triaje
    if p.riesgo_suicida == "alto":
        ev.urgentes.append("Riesgo suicida ALTO: evaluación psiquiátrica urgente, plan de seguridad y "
                           "restricción de medios ANTES de cualquier plan ambulatorio.")
    if p.psicosis_actual:
        ev.urgentes.append("Psicosis activa: valoración psiquiátrica urgente y entorno seguro.")
    if p.sintomas_cardiacos_agudos:
        ev.urgentes.append("Síntomas cardiacos agudos: urgencias médicas (la metanfetamina causa infarto, "
                           "arritmias y miocardiopatía).")
    if abst_complicada:
        ev.prioritarias.append("Riesgo de abstinencia complicada (alcohol/benzodiacepinas): manejo médico de "
                               "la abstinencia antes de iniciar bupropión.")
    if p.riesgo_suicida == "moderado":
        ev.prioritarias.append("Riesgo suicida moderado: plan de seguridad, restricción de medios, "
                               "seguimiento semanal.")
    if p.uso_opioides_actual:
        ev.prioritarias.append("Uso actual de opioides: evaluar trastorno por uso de opioides; el tratamiento "
                               "con agonistas (buprenorfina/metadona) reduce mortalidad y es prioritario.")
    if p.embarazo:
        ev.prioritarias.append("Embarazo: coordinación obstétrica + medicina de adicciones. Fármacos solo por "
                               "especialista; MC y psicoterapias son opciones seguras.")
    if p.vih == "positivo_sin_tratamiento":
        ev.prioritarias.append("VIH sin tratamiento: vinculación inmediata a terapia antirretroviral.")
    if p.violencia_pareja:
        ev.prioritarias.append("Violencia de pareja: plan de seguridad; evitar terapia conjunta de pareja.")

    # ------------------------------------------------------------------ bupropión
    if p.antecedente_convulsiones:
        marcar("bup", "BLOQUEADO", "Antecedente de convulsiones (contraindicación de ficha técnica).")
    if p.trastorno_alimentario:
        marcar("bup", "BLOQUEADO", "Bulimia/anorexia actual o previa (contraindicación: riesgo convulsivo).")
    if abst_complicada:
        marcar("bup", "BLOQUEADO", "Suspensión de alcohol/benzodiacepinas en curso o previsible (contraindicación).")
    if p.uso_imao_14d:
        marcar("bup", "BLOQUEADO", "IMAO en los últimos 14 días.")
    if p.psicosis_actual:
        marcar("bup", "BLOQUEADO", "Psicosis activa.")
    if p.trastorno_bipolar:
        marcar("bup", "ESPECIALISTA", "Trastorno bipolar: antidepresivo sin estabilizador puede inducir manía.")
    if p.embarazo or p.lactancia:
        marcar("bup", "ESPECIALISTA", "Embarazo/lactancia: decisión de especialista.")
    if hep_grave:
        marcar("bup", "ESPECIALISTA", "Hepatopatía severa: requiere ajuste especializado.")
    elif p.enfermedad_hepatica == "moderada":
        limitar("bup", 1, "Hepatopatía moderada: dosis reducida.")
    if p.tce_moderado_severo:
        limitar("bup", 2, "TCE: menor umbral convulsivo; tope 300 mg/día.")
    if alcohol_riesgo:
        prec("bup", "Alcohol de riesgo: aumenta riesgo convulsivo; recomendar abstinencia de alcohol.")
    if p.hipertension_no_controlada:
        prec("bup", "HTA no controlada: controlarla antes de iniciar.", "TA basal", "TA en cada consulta")
    if p.edad < 25:
        prec("bup", "Menor de 25 años: advertencia de suicidalidad con antidepresivos.", None,
             "PHQ-9 e ideación suicida cada 1-2 semanas las primeras 12")
    if p.psicosis_inducida_previa:
        prec("bup", "Psicosis inducida previa: vigilar síntomas psicóticos.", None, "Síntomas psicóticos")
    ev.estado["bup"]["estudios"].append("Tamizaje de convulsiones/trastorno alimentario; TA basal")

    # ------------------------------------------------------------------ naltrexona
    if p.uso_opioides_actual:
        marcar("ntx", "BLOQUEADO", "Uso actual de opioides: precipita abstinencia.")
    if p.tratamiento_agonista_opioide:
        marcar("ntx", "BLOQUEADO", "En metadona/buprenorfina: naltrexona contraindicada.")
    if p.dolor_cronico_con_opioides:
        marcar("ntx", "BLOQUEADO", "Requiere analgesia opioide.")
    if hep_grave:
        marcar("ntx", "BLOQUEADO", "Hepatitis aguda o insuficiencia hepática.")
    if p.embarazo or p.lactancia:
        marcar("ntx", "ESPECIALISTA", "Embarazo/lactancia: decisión de especialista.")
    if p.alt_veces_lsn > 3:
        prec("ntx", "ALT > 3x LSN: revisión médica antes de iniciar.", "PFH (ALT/AST/bilirrubinas)", "PFH mensual")
    if p.rechaza_inyectables:
        limitar("ntx", 1, "Rechaza inyectables: solo vía oral.")
    if p.antecedente_uso_opioides or p.antecedente_sobredosis:
        prec("ntx", "Antecedente de opioides/sobredosis: riesgo de sobredosis al suspender (pérdida de tolerancia).",
             None, "Naloxona en casa obligatoria")
    ev.estado["ntx"]["estudios"].append("Antidoping con opioides y fentanilo negativo; >=7-10 días sin opioides; PFH basal")

    # ------------------------------------------------------------------ mirtazapina
    if p.uso_imao_14d:
        marcar("mirt", "BLOQUEADO", "IMAO en los últimos 14 días.")
    if p.trastorno_bipolar:
        marcar("mirt", "ESPECIALISTA", "Trastorno bipolar: riesgo de viraje.")
    if p.embarazo or p.lactancia:
        marcar("mirt", "ESPECIALISTA", "Embarazo/lactancia: decisión de especialista.")
    if hep_grave or p.enfermedad_hepatica == "moderada":
        limitar("mirt", 1, "Hepatopatía: dosis reducida.")
    if p.edad < 25:
        prec("mirt", "Menor de 25 años: advertencia de suicidalidad.", None, "PHQ-9 e ideación cada 1-2 semanas")
    if p.qtc_prolongado:
        prec("mirt", "QTc prolongado.", "ECG", "ECG de control")
    if alcohol_riesgo or p.benzodiacepinas_diarias:
        prec("mirt", "Sedación aditiva con alcohol/benzodiacepinas.")
    if not p.hsh_o_mujer_trans:
        ev.notas.append("Mirtazapina: en población general el efecto es modesto (fase 3, 2026); el efecto "
                        "mayor se observó en HSH/mujeres trans.")

    # ------------------------------------------------------------------ EMTr
    if p.antecedente_convulsiones:
        marcar("rtms", "BLOQUEADO", "Antecedente de convulsiones: contraindicación relativa; con evidencia de "
                                    "eficacia limitada en TUM, el balance riesgo/beneficio es desfavorable.")
    if p.implantes_metalicos_cabeza:
        marcar("rtms", "BLOQUEADO", "Metal intracraneal / implante coclear.")
    if p.psicosis_actual:
        marcar("rtms", "BLOQUEADO", "Psicosis activa.")
    if p.dispositivo_implantado or p.embarazo:
        marcar("rtms", "ESPECIALISTA", "Dispositivo implantado o embarazo.")
    if p.tce_moderado_severo:
        prec("rtms", "TCE: menor umbral convulsivo.")
    ev.estado["rtms"]["estudios"].append("Cuestionario de seguridad EMT (TASS) y umbral motor")

    # ------------------------------------------------------------------ psicosociales / entorno
    if p.cardiopatia or p.hipertension_no_controlada:
        prec("ejer", "Requiere valoración cardiológica previa (miocardiopatía por metanfetamina).", "ECG ± ecocardiograma")
    if p.embarazo:
        prec("ejer", "Ejercicio con autorización obstétrica.")
    if p.deterioro_cognitivo:
        prec("tcc", "Deterioro cognitivo: adaptar TCC (menor rendimiento esperado).")
    if not p.transporte and p.minutos_a_clinica > 60:
        prec("cm", "Acceso limitado: considerar MC remota (video-antidoping o saliva).")
    ev.estado["cm"]["estudios"].append("Protocolo de antidoping 2-3/sem con registro y entrega inmediata del incentivo")
    if p.apoyo_familiar == "ninguno" or not p.familia_dispuesta:
        marcar("fam", "NO_INDICADO", "Sin familia disponible o dispuesta.")
    if p.violencia_pareja:
        marcar("fam", "ESPECIALISTA", "Violencia de pareja: terapia conjunta contraindicada sin evaluación de seguridad.")
    if not tept:
        marcar("trauma", "NO_INDICADO", "Sin TEPT probable (PCL-5 < 33 y sin TEPT complejo).")
    if p.psicosis_actual or p.riesgo_suicida == "alto":
        marcar("trauma", "BLOQUEADO", "Diferir terapia de exposición hasta estabilizar.")
    if p.vivienda == "estable" and not p.convive_con_usuarios:
        marcar("viv", "NO_INDICADO", "Vivienda estable y sin convivencia con usuarios.")
    if p.empleo in ("formal", "estudiante"):
        marcar("voc", "NO_INDICADO", "Empleo formal o estudiante.")
    if p.rechaza_grupos:
        marcar("pares", "RECHAZADO", "Preferencia del sujeto.")
    if not p.grupos_ayuda_mutua_locales:
        marcar("pares", "NO_DISPONIBLE", "Sin grupos accesibles (considerar reuniones en línea).")

    # ------------------------------------------------------------------ preferencias / contexto / evidencia
    if p.rechaza_medicacion:
        for k in FARMACOS:
            marcar(k, "RECHAZADO", "El sujeto no acepta fármacos.")
    for c in COMPONENTES:
        if not ctx["disponibles"].get(c.clave, True):
            marcar(c.clave, "NO_DISPONIBLE", "No disponible en el contexto local.")
        if not c.por_defecto and not investigacion:
            marcar(c.clave, "EXCLUIDO_EVIDENCIA", f"Evidencia grado {c.grado}: solo en --modo-investigacion.")
    for k, niveles in ctx.get("niveles_no_disponibles", {}).items():
        if k in ev.estado and niveles:
            nmax = min(niveles) - 1
            if nmax < ev.estado[k]["nivel_max"]:
                limitar(k, nmax, f"Niveles {niveles} no disponibles localmente (p. ej., NTX-XR).")

    # ------------------------------------------------------------------ obligatorios (no optimizables)
    o = ev.obligatorios
    o.append("Laboratorio basal: antidoping ampliado (incluye opioides y fentanilo), BH, química, PFH, TA, peso.")
    if p.riesgo_fentanilo_suministro or p.uso_opioides_actual or p.antecedente_uso_opioides or p.antecedente_sobredosis:
        o.append("Naloxona en casa + capacitación en sobredosis; tiras reactivas de fentanilo; no consumir solo.")
    if p.via_administracion == "inyectada":
        o.append("Material de inyección estéril; vacunas VHA/VHB; tamizaje de endocarditis e infecciones de piel.")
    if p.vih == "desconocido" or p.via_administracion == "inyectada" or p.conducta_sexual_riesgo:
        o.append("Pruebas de VIH, VHC, VHB y sífilis (repetir cada 3-6 meses si la exposición continúa).")
    if p.conducta_sexual_riesgo or p.hsh_o_mujer_trans:
        o.append("Evaluar PrEP para VIH; PEP disponible; condones.")
    if p.vhc == "positivo":
        o.append("Vinculación a tratamiento de hepatitis C (antivirales de acción directa).")
    if p.tabaco_cigarros_dia > 0:
        o.append("Ofrecer tratamiento de tabaquismo (el bupropión también es tratamiento de primera línea).")
    if p.audit >= 8:
        o.append("Intervención por alcohol; si AUDIT >= 16, tratar trastorno por uso de alcohol.")
    if p.riesgo_suicida in ("moderado", "alto") or p.phq9 >= 20:
        o.append("Plan de seguridad (tipo Stanley-Brown) y restricción de medios letales.")
    if p.anios_uso >= 5 or p.cardiopatia or p.hipertension_no_controlada:
        o.append("ECG basal; ecocardiograma si hay disnea, edema o fatiga (miocardiopatía por metanfetamina).")
    if p.via_administracion == "fumada" and p.anios_uso >= 3:
        o.append("Valoración odontológica.")
    if p.psicosis_inducida_previa:
        o.append("Plan de prevención de recaída psicótica (señales tempranas, contacto de crisis).")
    if p.tdah:
        o.append("Valoración especializada de TDAH (no iniciar estimulantes sin evaluar riesgo).")
    if p.trastorno_bipolar:
        o.append("Psiquiatría: estabilizador del ánimo antes de antidepresivos.")
    o.append("Consentimiento informado y decisión compartida: este plan es una propuesta para revisión clínica.")

    # ------------------------------------------------------------------ dimensiones y nivel de atención
    d = {}
    d1, r1 = 0, []
    if abst_complicada:
        d1, r1 = 3, ["abstinencia de alcohol/benzodiacepinas con riesgo"]
    elif p.uso_opioides_actual:
        d1, r1 = 2, ["uso concomitante de opioides"]
    elif p.dias_abstinencia_actual < 14:
        d1, r1 = 1, ["consumo reciente (abstinencia de estimulantes no peligrosa, sí disfórica)"]
    d["D1 Intoxicación/abstinencia/medicamentos"] = (d1, r1)
    d2, r2 = 0, []
    if p.sintomas_cardiacos_agudos or p.enfermedad_hepatica == "hepatitis_aguda":
        d2, r2 = 4, ["condición médica aguda"]
    elif p.cardiopatia or p.hipertension_no_controlada or p.vih == "positivo_sin_tratamiento" or p.embarazo:
        d2, r2 = 2, ["condición médica que requiere manejo concurrente"]
    elif p.vhc == "positivo" or p.enfermedad_hepatica != "ninguna":
        d2, r2 = 1, ["comorbilidad médica estable"]
    d["D2 Biomédica"] = (d2, r2)
    d3, r3 = 0, []
    if p.riesgo_suicida == "alto" or p.psicosis_actual:
        d3, r3 = 4, ["riesgo suicida alto o psicosis activa"]
    elif p.riesgo_suicida == "moderado" or p.phq9 >= 20 or p.trastorno_bipolar:
        d3, r3 = 3, ["riesgo moderado, depresión severa o bipolaridad"]
    elif p.phq9 >= 10 or tept or p.psicosis_inducida_previa or p.tdah:
        d3, r3 = 2, ["comorbilidad psiquiátrica activa (depresión/TEPT/psicosis previa/TDAH)"]
    elif p.gad7 >= 10 or p.phq9 >= 5:
        d3, r3 = 1, ["síntomas leves"]
    d["D3 Psiquiátrica/cognitiva"] = (d3, r3)
    d4, r4 = 1, ["uso de bajo nivel"]
    if p.via_administracion == "inyectada" and p.antecedente_sobredosis:
        d4, r4 = 4, ["inyección con sobredosis previa"]
    elif p.dias_uso_ultimos_30 >= 20 or p.criterios_dsm5 >= 6:
        d4, r4 = 3, ["uso frecuente o TUM severo"]
    elif p.dias_uso_ultimos_30 >= 8:
        d4, r4 = 2, ["uso moderado"]
    elif p.dias_uso_ultimos_30 == 0 and p.dias_abstinencia_actual >= 90:
        d4, r4 = 0, ["abstinencia sostenida"]
    d["D4 Riesgos relacionados con el consumo"] = (d4, r4)
    d5, r5 = 0, []
    if p.vivienda == "calle" and (p.convive_con_usuarios or p.violencia_pareja):
        d5, r5 = 4, ["situación de calle con entorno de consumo o violencia"]
    elif p.vivienda != "estable" or p.convive_con_usuarios or p.pareja_usuaria:
        d5, r5 = 3, ["vivienda inestable o convivencia con usuarios"]
    elif p.red_consumo_prop >= 0.5 or p.apoyo_familiar in ("ninguno", "bajo"):
        d5, r5 = 2, ["red de consumo o apoyo bajo"]
    elif p.red_consumo_prop > 0:
        d5, r5 = 1, ["exposición parcial"]
    d["D5 Entorno de recuperación"] = (d5, r5)
    d6, r6 = 0, []
    if p.etapa_cambio == "precontemplacion":
        d6, r6 = 3, ["precontemplación"]
    elif p.etapa_cambio == "contemplacion" or (not p.transporte) or p.horas_disponibles_semana < 4:
        d6, r6 = 2, ["ambivalencia o barreras de acceso/tiempo"]
    elif p.tratamientos_previos >= 3:
        d6, r6 = 1, ["múltiples tratamientos previos"]
    d["D6 Consideraciones centradas en la persona"] = (d6, r6)
    ev.dimensiones = d

    v = [x[0] for x in d.values()]
    if max(v[1], v[2], v[0]) >= 4:
        ev.nivel_atencion = "Nivel 4 · hospitalario médicamente gestionado (URGENTE)"
    elif v[0] >= 3:
        ev.nivel_atencion = "Nivel 3.7 · manejo médico de abstinencia/residencial médicamente gestionado"
    elif v[4] >= 3 and v[3] >= 3:
        ev.nivel_atencion = ("Nivel 3.5 o 2.1 + vivienda de recuperación · residencial de alta intensidad, o "
                             "ambulatorio intensivo si hay vivienda libre de consumo")
    elif v[3] >= 3 or v[2] >= 2 or v[4] >= 2:
        ev.nivel_atencion = "Nivel 2.1 · ambulatorio intensivo (IOP, >= 9 h/sem)"
    else:
        ev.nivel_atencion = "Nivel 1 · ambulatorio"
    return ev


# =============================================================================
# Parámetros personalizados para el simulador (priors de experto, calibrables)
# =============================================================================
def terminos_riesgo(p: Perfil) -> Dict[str, Tuple[float, float]]:
    """Magnitud (media, DE) de cada término de riesgo en log-odds semanal. Positivo = riesgo."""
    r = {}
    if p.phq9 >= 20:
        r["depresion"] = (0.50, 0.20)
    elif p.phq9 >= 10:
        r["depresion"] = (0.30, 0.15)
    if p.pcl5 >= 33 or p.tept_complejo:
        r["tept"] = (0.35 + (0.10 if p.tept_complejo else 0.0), 0.20)
    if p.vivienda == "calle":
        r["vivienda"] = (0.70, 0.30)
    elif p.vivienda == "inestable":
        r["vivienda"] = (0.35, 0.20)
    if p.convive_con_usuarios:
        r["convivencia"] = (0.40, 0.20)
    if p.pareja_usuaria:
        r["pareja"] = (0.40, 0.20)
    if p.red_consumo_prop > 0:
        r["red"] = (0.60 * p.red_consumo_prop, 0.30 * p.red_consumo_prop)
    fam = {"ninguno": 0.20, "bajo": 0.10, "moderado": 0.0, "alto": -0.20}[p.apoyo_familiar]
    if fam:
        r["familia"] = (fam, 0.15)
    emp = {"desempleado": 0.15, "informal": 0.05, "formal": -0.10, "estudiante": -0.05}[p.empleo]
    r["empleo"] = (emp, 0.10)
    if p.audit >= 16:
        r["alcohol"] = (0.25, 0.15)
    elif p.audit >= 8:
        r["alcohol"] = (0.10, 0.08)
    mot = {"precontemplacion": 0.60, "contemplacion": 0.30, "preparacion": 0.0,
           "accion": -0.20, "mantenimiento": -0.40}[p.etapa_cambio]
    if mot:
        r["motivacion"] = (mot, 0.20)
    if p.tdah:
        r["tdah"] = (0.25, 0.15)
    dd = p.dias_uso_ultimos_30 - DIAS_REF
    r["frecuencia"] = (BETA_FREQ * dd, 0.025 * abs(dd) + 0.02)
    otros = 0.0
    otros += 0.30 if p.via_administracion == "inyectada" else 0.0
    otros += 0.03 * max(0.0, min(p.anios_uso, 15) - 5)
    otros += 0.05 * max(0, p.criterios_dsm5 - 6)
    otros += {"baja": -0.10, "media": 0.0, "alta": 0.20}[p.disponibilidad_sustancia]
    otros += 0.15 if p.psicosis_inducida_previa else 0.0
    otros += 0.05 * (p.craving_eva - 5)
    otros += 0.15 if p.deterioro_cognitivo else 0.0
    otros -= 0.10 if p.supervision_judicial else 0.0
    r["otros"] = (otros, 0.3 * abs(otros) + 0.05)
    return r


def modificadores(p: Perfil) -> Dict[str, float]:
    """Multiplicador del efecto (log-OR) de cada componente según moderadores con evidencia."""
    m = {c.clave: 1.0 for c in COMPONENTES}
    d = p.dias_uso_ultimos_30
    if d <= 18:
        # Ejercicio: beneficio solo en menor severidad (Rawson 2015). Bupropión: señal post hoc
        # (Elkashef/Shoptaw 2008) no confirmada prospectivamente (Heinzerling 2014) → ajuste pequeño.
        m["bup"], m["ejer"] = 1.2, 1.5
    elif d >= 25:
        m["bup"], m["ejer"], m["cm"] = 0.5, 0.6, 0.85
    if p.hsh_o_mujer_trans:
        m["mirt"] = 3.5   # Coffin 2020 (OR implícita ~2) vs población general (McKetin 2026, OR ~1.2)
    if p.etapa_cambio in ("precontemplacion", "contemplacion"):
        m["em"] = 2.0
    elif p.etapa_cambio in ("accion", "mantenimiento"):
        m["em"] = 0.5
    if p.deterioro_cognitivo:
        m["tcc"], m["cra"] = 0.7, 0.8
    return m


def factores_seguridad(p: Perfil) -> dict:
    """Multiplicadores de EA graves por componente y tasas de eventos graves por semana de consumo."""
    f = {c.clave: 1.0 for c in COMPONENTES}
    if p.tce_moderado_severo:
        f["bup"] *= 3.0
        f["rtms"] *= 3.0
    if p.audit >= 16:
        f["bup"] *= 2.0
        f["mirt"] *= 2.0
    if p.psicosis_inducida_previa:
        f["bup"] *= 1.5
    if p.dias_uso_ultimos_30 >= 10:
        f["bup"] *= 1.3   # el estimulante reduce el umbral convulsivo
    if p.alt_veces_lsn > 3:
        f["ntx"] *= 5.0
    if p.cardiopatia:
        f["ejer"] *= 10.0
    if p.hipertension_no_controlada:
        f["ejer"] *= 3.0
    # Eventos graves ligados al consumo (psicosis, cardiovascular, sobredosis). ORDEN DE MAGNITUD.
    otro = 0.0010
    otro *= 1.5 if p.via_administracion == "inyectada" else 1.0
    otro *= 2.0 if p.psicosis_inducida_previa else 1.0
    otro *= 1.5 if p.cardiopatia else 1.0
    sob = 0.0005 * (3.0 if (p.riesgo_fentanilo_suministro or p.uso_opioides_actual) else 0.2)
    sob *= 2.0 if p.via_administracion == "inyectada" else 1.0
    sob *= 2.0 if p.antecedente_sobredosis else 1.0
    return {"ea": f, "p_otro": otro, "p_sobredosis": sob,
            "ntx_proteccion": [1.0, 0.5, 0.1]}   # bloqueo opioide mientras NTX activa (oral/XR)


def abandono_estatico(p: Perfil) -> float:
    """Desplazamiento del logit de abandono semanal (priors de experto; total acotado a 0.9)."""
    h = 0.0
    h += 0.25 if not p.transporte else 0.0
    h += 0.15 if p.minutos_a_clinica > 45 else 0.0
    h += {"estable": 0.0, "inestable": 0.15, "calle": 0.35}[p.vivienda]
    h += {"precontemplacion": 0.35, "contemplacion": 0.15}.get(p.etapa_cambio, 0.0)
    h += 0.15 if p.phq9 >= 20 else 0.0
    h += 0.10 if p.deterioro_cognitivo else 0.0
    h -= 0.10 if p.supervision_judicial else 0.0
    return min(h, 0.9)


def abandono_acceso(p: Perfil) -> float:
    """Parte de la carga de abandono atribuible a acceso (lo que la gestión de caso puede mitigar)."""
    h = 0.25 if not p.transporte else 0.0
    h += 0.15 if p.minutos_a_clinica > 45 else 0.0
    h += {"estable": 0.0, "inestable": 0.15, "calle": 0.35}[p.vivienda]
    return h


def acceso_adherencia(p: Perfil) -> Dict[str, float]:
    a = {}
    for c in COMPONENTES:
        f = 1.0
        if c.presencial:
            f *= 0.75 if not p.transporte else 1.0
            f *= 0.85 if p.minutos_a_clinica > 45 else 1.0
            f *= 0.85 if p.vivienda == "calle" else 1.0
        if c.tipo == "farmaco":
            f *= 0.75 if p.vivienda == "calle" else 1.0
            f *= 0.85 if p.deterioro_cognitivo else 1.0
        a[c.clave] = f
    return a


def factor_horas(p: Perfil, c) -> float:
    """Horas extra por traslado para componentes presenciales."""
    return 1.0 + max(0, p.minutos_a_clinica - 20) / 60.0 if c.presencial else 1.0
