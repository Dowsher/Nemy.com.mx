"""Transient, consented self-exploration. Never stores health answers or AI output.

Only anonymous usage counters persist. Clinical records/authentication belong to the
separate existing service. The model is not used for safety decisions.
"""
from __future__ import annotations

import asyncio
from collections import OrderedDict
import copy
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import time
import uuid

import httpx
from fastapi import Request
from fastapi.responses import JSONResponse

VERSION = "2.0"
PROMPT_VERSION = "traza-reflection-2.0.2"
DEFAULT_MODEL = "gpt-5.4-mini"
PROVIDER_URL = "https://api.openai.com/v1/responses"
BANK = json.loads(Path(__file__).with_name("exploration_bank.json").read_text(encoding="utf-8"))
QUESTIONS = {q["id"]: q for q in BANK["questions"]}
MISSING = {98, 99}
CACHE_TTL_SECONDS = 900


class ExplorationError(Exception):
    def __init__(self, code: str, detail: str, status: int = 422):
        self.code, self.detail, self.status = code, detail, status
        super().__init__(detail)


def _fail(code, message, status=422):
    raise ExplorationError(code, message, status)


def _limit_env(name, default, maximum):
    try:
        return max(1, min(int(os.environ.get(name, str(default))), maximum))
    except (ValueError, TypeError):
        return default


def ai_enabled():
    return os.environ.get("TRAZA_AI_ENABLED", "1") == "1" and bool(os.environ.get("OPENAI_API_KEY", "").strip())


def hourly_limit():
    return _limit_env("TRAZA_AI_IP_HOURLY_LIMIT", _limit_env("TRAZA_AI_PER_IP_HOUR", 5, 100), 100)


def daily_limit():
    return _limit_env("TRAZA_AI_DAILY_LIMIT", _limit_env("TRAZA_AI_GLOBAL_DAY", 40, 1000), 1000)


def get_schema() -> dict:
    """Public copy, with truthful readiness and explicit destination information."""
    schema = copy.deepcopy(BANK)
    schema.update({
        "ai_available": ai_enabled(),
        "model": os.environ.get("TRAZA_AI_MODEL", DEFAULT_MODEL),
        "mode": "self-exploration",
        "limits": {
            "max_answers": 50, "min_present_answers": 3, "min_impact_answers": 1,
            "max_patterns": 3, "max_output_tokens": 2000,
            "per_ip_hour": hourly_limit(),
            "global_day": daily_limit(),
        },
        "privacy": {
            "provider": "OpenAI", "server_persists_answers": False,
            "server_persists_analysis": False, "transient_memory_minutes": 15,
            "provider_store": False, "provider_security_retention_days_up_to": 30,
            "safety_sent_to_provider": False,
            "text": "Al crear tu mapa, tus respuestas seleccionadas viajan al servidor de TRAZA y, salvo las de seguridad, a OpenAI. No pedimos nombre, correo ni relatos. TRAZA no guarda respuestas ni mapas en una base de datos: los procesa y conserva temporalmente en memoria hasta 15 minutos para evitar envíos repetidos. OpenAI puede conservar registros de seguridad hasta 30 días; store:false no garantiza retención cero. Se guardan contadores anónimos de uso para limitar solicitudes.",
        },
    })
    return schema


def safety_status(answers: dict, state: dict | None = None) -> dict:
    """Deterministic current checks plus the client's same-tab high-water state."""
    state = state or {}
    distress = answers.get("S2")
    observed = distress if type(distress) is int and 0 <= distress <= 10 else 0
    peak = max(observed, state.get("peak_distress", 0))
    risk = answers.get("S3") == 1 or state.get("current_risk", False)
    unsafe = answers.get("S1") == 0 or state.get("unsafe", False)
    reasons = []
    if unsafe:
        reasons.append("unsafe_now")
    if risk:
        reasons.append("current_risk")
    if peak >= 8:
        reasons.append("high_distress")
    complete = (answers.get("S1") == 1 and answers.get("S3") == 0
                and type(distress) is int and 0 <= distress <= 7)
    return {"blocked": bool(reasons), "complete": complete, "reasons": reasons,
            "peak_distress": peak, "current_risk": risk, "unsafe": unsafe}


def validate_payload(payload: object) -> dict:
    if not isinstance(payload, dict) or not set(payload) <= {"answers", "consent", "request_id", "safety_state"}:
        _fail("invalid_payload", "La solicitud contiene campos no permitidos.")
    consent = payload.get("consent")
    if (not isinstance(consent, dict) or set(consent) != {"adult", "processing", "ai", "version"}
            or any(consent.get(k) is not True for k in ("adult", "processing", "ai"))
            or consent.get("version") != VERSION):
        _fail("consent_required", "Confirma que eres mayor de edad y acepta el procesamiento y el envío a la IA para continuar.")
    request_id = payload.get("request_id")
    try:
        if not isinstance(request_id, str) or len(request_id) != 36 or str(uuid.UUID(request_id)) != request_id.lower():
            raise ValueError()
    except (ValueError, AttributeError):
        _fail("invalid_request_id", "La solicitud necesita un identificador válido.")
    answers = payload.get("answers")
    if not isinstance(answers, dict) or len(answers) > 50:
        _fail("invalid_answers", "Envía solo las respuestas de esta guía.")
    cleaned = {}
    for key, value in answers.items():
        if key not in QUESTIONS or type(value) is not int:
            _fail("invalid_answers", "Hay una respuesta que no corresponde a esta guía.")
        allowed = {option["value"] for option in QUESTIONS[key]["options"]}
        if value not in allowed:
            _fail("invalid_answers", "Una respuesta está fuera de las opciones disponibles.")
        cleaned[key] = value
    has_event = any(cleaned.get("E" + str(n)) == 1 for n in range(1, 11))
    if not has_event and any(k in cleaned for k in ("EC", "I5")):
        _fail("invalid_branch", "Actualiza las preguntas que dependen de las experiencias seleccionadas.")
    state = payload.get("safety_state", {})
    if (not isinstance(state, dict) or not set(state) <= {"peak_distress", "current_risk", "unsafe"}
            or type(state.get("peak_distress", 0)) is not int
            or not 0 <= state.get("peak_distress", 0) <= 10
            or type(state.get("current_risk", False)) is not bool
            or type(state.get("unsafe", False)) is not bool):
        _fail("invalid_safety_state", "El estado de seguridad no es válido.")
    safety = safety_status(cleaned, state)
    if safety["blocked"]:
        _fail("safety_blocked", "Vamos a detener el mapa. Ahora lo importante es tu seguridad y contar con apoyo humano.", 409)
    if not safety["complete"]:
        _fail("safety_incomplete", "Antes de crear un mapa necesitamos saber que estás a salvo y que puedes continuar con tranquilidad.")
    known_present = sum(k.startswith("P") and v not in MISSING for k, v in cleaned.items())
    known_impact = sum(k.startswith("I") and v not in MISSING for k, v in cleaned.items())
    if known_present < 3 or known_impact < 1:
        _fail("insufficient_information", "Para un mapa útil responde al menos tres preguntas sobre lo que notas hoy y una sobre cómo influye en tu día. También puedes ver tus respuestas sin IA.")
    return {"answers": cleaned, "safety": safety, "request_id": request_id.lower()}


def answer_facts(answers: dict, *, provider: bool = False) -> list[dict]:
    facts = []
    for q in BANK["questions"]:
        key = q["id"]
        if key not in answers or (provider and q["block"] == "safety"):
            continue
        value = answers[key]
        fact = {"id": key, "question": q["text"],
                "answer_label": next(o["label"] for o in q["options"] if o["value"] == value)}
        if provider:
            fact.update({"answer_code": value, "missing": value in MISSING,
                         "evidence_eligible": value in q.get("evidence_values", [])})
        facts.append(fact)
    return facts


def eligible_ids(answers: dict) -> set[str]:
    return {key for key, value in answers.items() if value in QUESTIONS[key].get("evidence_values", [])}


OUTPUT_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["title", "summary", "patterns", "reflection_questions", "supports", "limitations"],
    "properties": {
        "title": {"type": "string", "minLength": 4, "maxLength": 90},
        "summary": {"type": "string", "minLength": 12, "maxLength": 650},
        "patterns": {"type": "array", "maxItems": 3, "items": {
            "type": "object", "additionalProperties": False,
            "required": ["title", "observation", "evidence_ids", "uncertainty"],
            "properties": {
                "title": {"type": "string", "minLength": 3, "maxLength": 85},
                "observation": {"type": "string", "minLength": 12, "maxLength": 500},
                "evidence_ids": {"type": "array", "minItems": 1, "maxItems": 6,
                                 "items": {"type": "string", "enum": list(QUESTIONS)}},
                "uncertainty": {"type": "string", "minLength": 15, "maxLength": 260},
            },
        }},
        "reflection_questions": {"type": "array", "maxItems": 3,
                                 "items": {"type": "string", "minLength": 8, "maxLength": 220}},
        "supports": {"type": "array", "maxItems": 3,
                     "items": {"type": "string", "minLength": 8, "maxLength": 240}},
        "limitations": {"type": "string", "minLength": 25, "maxLength": 450},
    },
}

# Observations are assembled from exact bank labels by the server. The model
# selects evidence and offers optional reflection; it cannot rewrite its frequency.
MODEL_OUTPUT_SCHEMA = copy.deepcopy(OUTPUT_SCHEMA)
for _server_field in ("title", "summary", "supports", "limitations"):
    MODEL_OUTPUT_SCHEMA["required"].remove(_server_field)
    MODEL_OUTPUT_SCHEMA["properties"].pop(_server_field)
_model_pattern = MODEL_OUTPUT_SCHEMA["properties"]["patterns"]["items"]
for _server_field in ("title", "observation"):
    _model_pattern["required"].remove(_server_field)
    _model_pattern["properties"].pop(_server_field)
_model_pattern["properties"]["evidence_ids"]["maxItems"] = 3

SUPPORT_TEXT = {
    "A1": "Marcaste que tienes a alguien con quien puedes hablar y sentirte escuchado/a.",
    "A2": "Marcaste que hay una actividad, lugar o rutina que te ayuda a sentirte mejor.",
    "A3": "Marcaste que cuentas ahora con apoyo de un profesional de salud mental.",
}

DOMAIN_TITLES = {
    "P1": "Descanso", "P2": "Alerta", "P3": "Recuerdos molestos",
    "P4": "Situaciones que evitas", "P5": "Volver a la calma", "P6": "Cómo te hablas",
    "P7": "Cercanía y confianza", "P8": "Conexión contigo", "P9": "Bienestar físico",
    "I1": "Actividades del día", "I2": "Relaciones", "I3": "Cuidado personal",
    "I5": "Conexiones que tú notas", "I6": "Concentración",
}
LIMITATIONS_TEXT = "No es un diagnóstico. El mapa usa las respuestas que seleccionaste, no identifica causas y puede equivocarse. No confirma ni descarta una condición. Tú decides si las agrupaciones y preguntas te hacen sentido; tu experiencia tiene prioridad."


def allowed_supports(answers):
    return [text for key, text in SUPPORT_TEXT.items() if answers.get(key) == 1]


def grounded_title(ids):
    domains = list(dict.fromkeys(DOMAIN_TITLES[k] for k in ids if k in DOMAIN_TITLES))
    return " · ".join(domains) if domains else "Respuestas para explorar"


def grounded_summary(patterns, answers):
    areas = list(dict.fromkeys(DOMAIN_TITLES[k].lower() for p in patterns
                              for k in p["evidence_ids"] if k in DOMAIN_TITLES))
    has_event = any(answers.get("E" + str(n)) == 1 for n in range(1, 11))
    applicable = [q for q in BANK["questions"] if q["block"] != "safety"
                  and (not q.get("show_if") or has_event)]
    known = sum(q["id"] in answers and answers[q["id"]] not in MISSING for q in applicable)
    missing = len(applicable) - known
    if areas:
        summary = "Estas áreas reúnen algunas de tus respuestas: " + ", ".join(areas) + ". La IA propuso las agrupaciones; tú decides si te hacen sentido."
    else:
        summary = "La IA no propuso agrupaciones con estas respuestas. Puedes revisar lo que marcaste sin sacar conclusiones sobre lo que no se exploró."
    summary += " El mapa usa " + str(known) + " respuestas."
    if missing:
        summary += " Otras " + str(missing) + " quedaron sin responder o sin una respuesta definida."
    return summary


def ground_observations(value, answers):
    """Keep the raw model schema strict; add only exact reported facts."""
    _check_keys(value, ("patterns", "reflection_questions"))
    if not isinstance(value["patterns"], list) or len(value["patterns"]) > 3:
        _fail("ai_invalid_response", "La IA no respetó los límites del mapa.", 502)
    normalized = copy.deepcopy(value)
    by_id = {f["id"]: f for f in answer_facts(answers)}
    eligible = eligible_ids(answers)
    for pattern in normalized["patterns"]:
        _check_keys(pattern, ("evidence_ids", "uncertainty"))
        ids = pattern["evidence_ids"]
        if (not isinstance(ids, list) or not 1 <= len(ids) <= 3
                or any(not isinstance(k, str) or k not in eligible for k in ids)):
            _fail("ai_invalid_response", "La IA citó respuestas que no respaldan su interpretación.", 502)
        pattern["observation"] = "\n".join(by_id[k]["question"] + " → " + by_id[k]["answer_label"] for k in ids)
        pattern["title"] = grounded_title(ids)
    normalized.update(title="Tu mapa para explorar", summary=grounded_summary(normalized["patterns"], answers),
                      supports=allowed_supports(answers), limitations=LIMITATIONS_TEXT)
    return normalized

SYSTEM_PROMPT = """Organiza respuestas de autoexploración para adultos en español de México.
No eres un servicio clínico. Tu aportación tiene dos partes: agrupar respuestas relacionadas
y proponer preguntas opcionales para observar el presente. Escribe de tú, con frases cortas.

Devuelve únicamente patterns[{evidence_ids,uncertainty}] y reflection_questions[string].
El servidor construye títulos, resumen, observaciones y apoyos con las opciones exactas.
No generes esos campos. No reescribas frecuencias ni grados de las respuestas.

AGRUPACIONES:
- De cero a tres grupos, máximo tres evidence_ids por grupo. Solo IDs evidence_eligible=true.
- Cada grupo necesita una reacción P o una dificultad I1/I2/I3/I6 reportada. Una experiencia,
  apoyo o I5 por sí solos no bastan. Si no hay reacciones/dificultades positivas, patterns=[].
- Agrupa solo hechos que tenga sentido revisar juntos; no propongas una causa o historia.
  Una experiencia difícil no prueba por qué hay malestar. I5 tampoco demuestra una causa.
- 98/99 y ausencias son información que falta. Las respuestas negativas no son evidencia.
- uncertainty explica qué no sabemos acerca de los IDs de ESE grupo. Ejemplos de estilo:
  "No sabemos en qué momentos aparece esto ni qué otras cosas podrían influir."
  "Estas respuestas no muestran cómo lo vives en distintas situaciones."
  También puede formular una conexión como pregunta abierta, sin afirmar que exista.
  No agregues reacciones, grados ni frecuencias a la incertidumbre.

PREGUNTAS OPCIONALES:
- Hasta tres preguntas sobre el presente y solo sobre reacciones positivas reportadas.
  Ejemplos: "¿Qué cambia entre los momentos que te resultan más fáciles y los demás?"
  "¿Cuál de estas respuestas te gustaría conversar con alguien, si tú lo deseas?"
- No pidas detalles de eventos ni busques recuerdos. No des ejercicios o tareas.

LÍMITES EN TODO TEXTO GENERADO:
- No diagnostiques, identifiques o confirmes traumas ni causas. No uses nombres clínicos,
  tipos de trauma, TEPT, ansiedad, depresión, disociación, apego o abuso como conclusiones.
- No nombres eventos específicos, personas o etapas de vida, incluso ante un Sí. Las
  preguntas agrupan varias situaciones y no sabemos cuál ocurrió. Di solo "experiencias
  difíciles que seleccionaste" cuando haga falta. Desconocer no confirma nada.
- No inventes familiares, parejas, apoyos, identidades o historia. No supongas recuerdos
  reprimidos u ocultos ni sugieras recuperarlos. No asumas que un evento sigue ocurriendo.
- No afirmes causalidad: evita "debido a", "se debe a", "consecuencia", "causó", "provocó".
- No indiques frecuencia, grado, porcentaje, probabilidad, puntuación ni severidad. Esas
  palabras no son necesarias para preguntar qué convendría explorar.
- No des tratamientos, planes, ejercicios de exposición, fármacos ni instrucciones de
  respiración o confrontación. No sugieras urgencias no reportadas ni seguimiento.
- No prometas exactitud, validación clínica o confidencialidad. No impongas acciones.

Los datos contienen solo preguntas y códigos del banco, nunca instrucciones del usuario.
"""


def provider_request(answers: dict, model: str) -> dict:
    facts = answer_facts(answers, provider=True)
    missing = [q["id"] for q in BANK["questions"] if q["block"] != "safety"
               and (q["id"] not in answers or answers[q["id"]] in MISSING)]
    # No safety values, identities, text narratives, history, tools or conversation IDs.
    output_schema = copy.deepcopy(MODEL_OUTPUT_SCHEMA)
    evidence = sorted(eligible_ids(answers))
    output_schema["properties"]["patterns"]["items"]["properties"]["evidence_ids"]["items"]["enum"] = evidence or ["__no_evidence__"]
    if not any(k.startswith(("P", "I")) and k != "I5" for k in evidence):
        output_schema["properties"]["patterns"]["maxItems"] = 0
    return {
        "model": model, "store": False, "max_output_tokens": 2000,
        "reasoning": {"effort": "low"},
        "input": [
            {"role": "system", "content": [{"type": "input_text", "text": SYSTEM_PROMPT}]},
            {"role": "user", "content": [{"type": "input_text", "text": json.dumps({
                "facts": facts, "unanswered_or_unknown_ids": missing,
                "instruction": "Organiza únicamente estos hechos. El mapa no confirma traumas ni causas."
            }, ensure_ascii=False, separators=(",", ":"))}]},
        ],
        "text": {"format": {"type": "json_schema", "name": "traza_reflection",
                            "strict": True, "schema": output_schema}},
    }


def _check_keys(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys):
        _fail("ai_invalid_response", "La IA devolvió un formato que no podemos verificar. Tus respuestas siguen disponibles.", 502)


def _check_text(value, minimum, maximum):
    if (not isinstance(value, str) or not minimum <= len(value.strip()) <= maximum
            or re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", value)
            or "<" in value or ">" in value):
        _fail("ai_invalid_response", "La IA devolvió texto que no podemos verificar. Tus respuestas siguen disponibles.", 502)


def validate_analysis(value: object, answers: dict) -> dict:
    """Reject unsupported evidence and unsafe interpretive claims. No fallback report."""
    _check_keys(value, ("title", "summary", "patterns", "reflection_questions", "supports", "limitations"))
    for key, lo, hi in (("title", 4, 90), ("summary", 12, 650), ("limitations", 25, 450)):
        _check_text(value[key], lo, hi)
    patterns = value["patterns"]
    if not isinstance(patterns, list) or len(patterns) > 3:
        _fail("ai_invalid_response", "La IA no respetó los límites del mapa.", 502)
    allowed = eligible_ids(answers)
    positive_reaction = any(k.startswith(("P", "I")) and k != "I5" for k in allowed)
    if patterns and not positive_reaction:
        _fail("ai_invalid_response", "La IA propuso un patrón sin respuestas que lo respalden.", 502)
    for p in patterns:
        _check_keys(p, ("title", "observation", "evidence_ids", "uncertainty"))
        for key, lo, hi in (("title", 3, 85), ("observation", 12, 500), ("uncertainty", 15, 260)):
            _check_text(p[key], lo, hi)
        ids = p["evidence_ids"]
        if (not isinstance(ids, list) or not 1 <= len(ids) <= 6
                or any(not isinstance(k, str) or k not in allowed for k in ids)
                or len(set(ids)) != len(ids)):
            _fail("ai_invalid_response", "La IA citó respuestas que no respaldan su interpretación. No mostraremos ese mapa.", 502)
        if not any(k.startswith(("P", "I")) and k != "I5" for k in ids):
            _fail("ai_invalid_response", "La IA convirtió una experiencia o apoyo en un patrón sin una reacción reportada.", 502)
        exact = {f["id"]: f for f in answer_facts(answers)}
        expected = "\n".join(exact[k]["question"] + " → " + exact[k]["answer_label"] for k in ids)
        if p["observation"] != expected:
            _fail("ai_invalid_response", "La observación no coincide con las respuestas exactas que seleccionaste.", 502)
        if p["title"] != grounded_title(ids):
            _fail("ai_invalid_response", "El título no coincide con las respuestas del grupo.", 502)
    if value["title"] != "Tu mapa para explorar" or value["summary"] != grounded_summary(patterns, answers):
        _fail("ai_invalid_response", "El resumen no coincide con las áreas respaldadas por tus respuestas.", 502)
    for name, maximum in (("reflection_questions", 220), ("supports", 240)):
        if not isinstance(value[name], list) or len(value[name]) > 3:
            _fail("ai_invalid_response", "La IA no respetó el formato del mapa.", 502)
        for item in value[name]:
            _check_text(item, 8, maximum)
    if len(set(value["supports"])) != len(value["supports"]) or any(s not in allowed_supports(answers) for s in value["supports"]):
        _fail("ai_invalid_response", "La IA mencionó un apoyo que no seleccionaste.", 502)
    interpretive = " ".join([value["title"], value["summary"],
        *(p[k] for p in patterns for k in ("title", "uncertainty")),
        *value["reflection_questions"], *value["supports"]]).casefold()
    # Allow explicit, bounded noncausal caveats. Other causal assertions remain
    # invalid; this avoids rejecting "no podemos establecer una causa" as a cause.
    interpretive = re.sub(r"\bno (?:podemos|se puede|permite|es posible) (?:establecer|identificar|determinar|conocer) (?:una |la |las )?causas?\b", "", interpretive)
    interpretive = re.sub(r"\bno (?:identifica|explica|determina|confirma) (?:una |la |las )?causas?\b", "", interpretive)
    interpretive = re.sub(r"\bno (?:sabemos|conocemos) (?:una |la |las )?causas?\b", "", interpretive)
    interpretive = re.sub(r"\bno (?:es|constituye) un diagnóstico\b|\bno permite diagnosticar\b", "", interpretive)
    # Conservative lexical rejection complements strict evidence IDs. It is not a
    # clinical certification or a claim that semantic errors are impossible.
    prohibited = r"\b(tept|cptsd|tdah|trastorn\w*|diagnostic\w*|trauma\w*|traumatiz\w*|disociaci\w*|depresi\w*|apego|abuso|ansi[ea]dad|psicosis|bipolar\w*)\b|\b(caus[aoó]\w*|provoc[aoó]\w*)\b|debido a|se debe a|consecuencia de|esto demuestra|esto confirma|recuerdos? (?:reprimidos?|ocultos?)|recuperar (?:tus )?recuerdos|\b(?:padre|madre|pareja|espos[oa]|hij[oa]s?|adolescencia)\b|\b(?:medicamento|fármaco|exposición|revivir|confrontar)\w*\b|\d+\s*%"
    if re.search(prohibited, interpretive):
        _fail("ai_invalid_response", "La IA usó una interpretación que excede lo que permiten tus respuestas. No mostraremos ese mapa.", 502)
    event_terms = {
        "E1": r"amenaz\w*|temor por tu seguridad",
        "E2": r"humill\w*|menospreci\w*|te control\w*",
        "E3": r"falta de (?:cuidado|protección)|faltó (?:cuidado|protección)",
        "E4": r"golpe\w*|lastim\w* físicamente|violencia física",
        "E5": r"sexual\w*",
        "E6": r"violencia (?:en (?:tu|el) hogar|entre personas|familiar|doméstica)",
        "E7": r"pérdida\w*|abandono|separación|duelo",
        "E8": r"accidente\w*|enfermedad grave|desastre\w*|delito\w*",
        "E9": r"internamiento\w*|retenci[oó]n|retuvieron|internaron",
        "E10": r"discriminaci\w*|acoso|trato injusto|rechazo repetido",
    }
    for event, pattern in event_terms.items():
        if answers.get(event) != 1 and re.search(pattern, interpretive):
            _fail("ai_invalid_response", "La IA mencionó una experiencia que no seleccionaste. No mostraremos ese mapa.", 502)
    # A positive combined category does not identify which member occurred. The
    # exact combined question remains visible in the server-grounded evidence.
    combined_terms = r"pérdida\w*|abandono|separación|duelo|accidente\w*|enfermedad grave|desastre\w*|delito\w*|discriminaci\w*|acoso|rechazo repetido|violencia (?:en (?:tu|el) hogar|familiar|doméstica)"
    if re.search(combined_terms, interpretive):
        _fail("ai_invalid_response", "La IA concretó una experiencia que solo conocemos como una categoría amplia.", 502)
    if re.search(r"infancia|niñez|cuando eras (?:niñ|pequeñ)", interpretive):
        _fail("ai_invalid_response", "La IA añadió una etapa de vida que no seleccionaste.", 502)
    reaction_terms = {
        "P1": r"dorm\w*|descans\w*",
        "P2": r"\balerta\b|sobresalt\w*",
        "P3": r"recuerdos?\b|sueños? molest\w*",
        "P4": r"\bevit(?:as|ar|ación|ando)\b",
        "P5": r"volver a la calma|regular (?:tus |las )?emociones|manejar (?:tus |las )?emociones",
        "P6": r"autocrític\w*|autocritic\w*|vales menos|dureza contigo|te hablas con dureza",
        "P7": r"\bconfiar\b|\bconfianza\b|sentir cercanía",
        "P8": r"desconect\w*|en automático",
        "P9": r"\btensión\b|molestias?\b|cansancio|agotamiento",
        "I6": r"concentr\w*|terminar (?:cosas|tareas)",
    }
    # Titles/summary cannot invent a reaction absent from all reported positives.
    # Pattern-specific titles and uncertainties must also fit their cited facts.
    for key, pattern in reaction_terms.items():
        if key not in allowed and re.search(pattern, interpretive):
            _fail("ai_invalid_response", "La IA mencionó una reacción que no seleccionaste.", 502)
        for p in patterns:
            if key not in p["evidence_ids"] and re.search(pattern, (p["title"] + " " + p["uncertainty"]).casefold()):
                _fail("ai_invalid_response", "La IA usó una reacción que no coincide con las evidencias del patrón.", 502)
    support_claims = {
        "A1": r"(?:tienes|cuentas con|reconoces|dispones de)\b[^.?!]{0,110}(?:alguien|persona|escuch|amistad|amig[oa]|familiar)|(?:alguien|una persona) (?:te escucha|te acompaña|te apoya)",
        "A2": r"(?:una |la |tu |hay una )?(?:actividad|rutina|lugar)\b[^.?!]{0,90}(?:te ayuda|te hace bien|te sirve|te calma)",
        "A3": r"(?:tienes|cuentas con|recibes|reconoces)\b[^.?!]{0,90}(?:apoyo profesional|un profesional|atención profesional)",
    }
    for key, pattern in support_claims.items():
        if answers.get(key) != 1 and re.search(pattern, interpretive):
            _fail("ai_invalid_response", "La IA afirmó que tienes un apoyo que no seleccionaste.", 502)
    if re.search(r"\b(?:nunca|siempre|diari[oa]|a menudo|a veces|a diario|casi todas|casi todos|todos los|todas las|cada día|frecuent\w*)\b", interpretive):
        _fail("ai_invalid_response", "La IA añadió una frecuencia fuera de tus respuestas exactas.", 502)
    if re.search(r"\b(?:sever[oa]s?|extrem[oa]s?|elevad[oa]s?|intens[oa]s?|moderad[oa]s?|leves?)\b", interpretive):
        _fail("ai_invalid_response", "La IA añadió un grado de intensidad que este mapa no determina.", 502)
    if not re.search(r"no (?:es|constituye) un diagnóstico", value["limitations"], flags=re.I):
        _fail("ai_invalid_response", "La IA no explicó los límites de este mapa.", 502)
    return value


async def provider_analyze(answers: dict, *, client: httpx.AsyncClient | None = None) -> dict:
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    model = os.environ.get("TRAZA_AI_MODEL", DEFAULT_MODEL)
    if not ai_enabled():
        _fail("ai_unavailable", "La IA no está disponible ahora. Puedes revisar o descargar tus respuestas sin enviarlas.", 503)
    owned = client is None
    if owned:
        client = httpx.AsyncClient(timeout=httpx.Timeout(55.0, connect=10.0), follow_redirects=False)
    try:
        response = await client.post(PROVIDER_URL, json=provider_request(answers, model),
                                     headers={"Authorization": "Bearer " + key,
                                              "Content-Type": "application/json"})
        if response.status_code != 200:
            _fail("ai_provider_error", "La IA no pudo responder ahora. No se creó un mapa. Tus respuestas siguen aquí.", 503)
        if len(response.content) > 131072:
            _fail("ai_invalid_response", "La IA devolvió una respuesta demasiado extensa.", 502)
        try:
            envelope = response.json()
            if not isinstance(envelope, dict) or envelope.get("status") != "completed":
                raise ValueError()
            texts = []
            for item in envelope.get("output", []):
                if item.get("type") == "message":
                    for part in item.get("content", []):
                        if part.get("type") == "refusal":
                            _fail("ai_provider_error", "La IA no generó este mapa. Puedes seguir usando tu registro de respuestas.", 503)
                        if part.get("type") == "output_text":
                            texts.append(part["text"])
            if len(texts) != 1:
                raise ValueError()
            analysis = json.loads(texts[0])
        except (ValueError, TypeError, KeyError, AttributeError):
            _fail("ai_invalid_response", "La IA devolvió una respuesta que no podemos verificar. Tus respuestas siguen aquí.", 502)
        return validate_analysis(ground_observations(analysis, answers), answers)
    except (httpx.HTTPError, asyncio.TimeoutError):
        _fail("ai_provider_error", "No pudimos conectar con la IA. No se creó un mapa; tus respuestas siguen aquí.", 503)
    finally:
        if owned:
            await client.aclose()


async def analyze_payload(payload: object, *, client: httpx.AsyncClient | None = None) -> dict:
    clean = validate_payload(payload)
    analysis = await provider_analyze(clean["answers"], client=client)
    return {
        "analysis": analysis,
        "facts": answer_facts(clean["answers"]),
        "safety": {"blocked": False, "complete": True},
        "meta": {"model": os.environ.get("TRAZA_AI_MODEL", DEFAULT_MODEL),
                 "prompt_version": PROMPT_VERSION, "request_id": clean["request_id"],
                 "generated_at": datetime.now(timezone.utc).isoformat(),
                 "provider": "OpenAI", "source": "generative_ai", "grounding": "facts_and_titles_normalized_by_server",
                 "generated_components": ["grouping", "reflection_questions", "uncertainty"],
                 "grounded_components": ["title", "summary", "observations", "supports"]},
    }


class ExplorationService:
    """Atomic counters survive restarts; health-bearing idempotency cache does not."""
    def __init__(self, db):
        self.db = db
        # Existing app encryption key gives a stable HMAC without storing an IP.
        self._hmac_key = hashlib.sha256(db.cipher._signing_key + b"exploration-ip-v2").digest()
        self.cache = OrderedDict()
        self.inflight = {}
        self.active = 0
        self.lock = asyncio.Lock()
        with db.connection() as con:
            con.execute("CREATE TABLE IF NOT EXISTS exploration_usage(bucket TEXT NOT NULL, scope TEXT NOT NULL, count INTEGER NOT NULL, expires_at REAL NOT NULL, PRIMARY KEY(bucket,scope))")

    def ip_hash(self, ip: str, day: str) -> str:
        return hmac.new(self._hmac_key, (day + "|" + ip).encode(), hashlib.sha256).hexdigest()

    def _expire_cached(self, key, expiry):
        """Timer holds only a pseudonymous key, never the health-bearing result."""
        entry = self.cache.get(key)
        if entry and entry[0] <= expiry:
            self.cache.pop(key, None)

    def consume_budget(self, hashed_ip: str, now: float | None = None):
        now = time.time() if now is None else now
        hour = str(int(now // 3600))
        day = str(int(now // 86400))
        limits = (("hour:" + hour, hashed_ip, hourly_limit(), (int(now // 3600) + 1) * 3600),
                  ("day:" + day, "global", daily_limit(), (int(now // 86400) + 1) * 86400))
        with self.db.connection(transaction=True) as con:
            con.execute("DELETE FROM exploration_usage WHERE expires_at <= ?", (now,))
            for bucket, scope, maximum, _ in limits:
                row = con.execute("SELECT count FROM exploration_usage WHERE bucket=? AND scope=?", (bucket, scope)).fetchone()
                if row and row[0] >= maximum:
                    _fail("rate_limited", "Se alcanzó el límite de mapas por ahora. Puedes seguir viendo tus respuestas y volver más tarde.", 429)
            for bucket, scope, _, expires in limits:
                con.execute("INSERT INTO exploration_usage(bucket,scope,count,expires_at) VALUES(?,?,1,?) ON CONFLICT(bucket,scope) DO UPDATE SET count=count+1", (bucket, scope, expires))

    async def analyze(self, payload: object, ip: str, *, client: httpx.AsyncClient | None = None):
        clean = validate_payload(payload)  # consent, safety and insufficient data before any provider/budget
        if not ai_enabled():
            _fail("ai_unavailable", "La IA no está disponible ahora. Puedes revisar o descargar tus respuestas sin enviarlas.", 503)
        now = time.time()
        day = str(int(now // 86400))
        hashed_ip = self.ip_hash(ip, day)
        cache_key = (hashed_ip, clean["request_id"])
        payload_hash = hashlib.sha256(json.dumps(clean["answers"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        async with self.lock:
            for key in list(self.cache):
                if self.cache[key][0] <= now:
                    self.cache.pop(key, None)
            cached = self.cache.get(cache_key)
            if cached:
                if cached[1] != payload_hash:
                    _fail("request_conflict", "Ese identificador ya se usó con otras respuestas. Inicia un nuevo envío.", 409)
                return copy.deepcopy(cached[2])
            if cache_key in self.inflight:
                if self.inflight[cache_key] != payload_hash:
                    _fail("request_conflict", "Ese identificador está en uso con otras respuestas.", 409)
                _fail("request_in_progress", "Tu mapa ya se está creando. Espera a que termine este envío.", 409)
            if self.active >= 2:
                _fail("ai_busy", "La IA está atendiendo dos mapas. Espera un momento y vuelve a intentarlo.", 429)
            self.consume_budget(hashed_ip, now)
            self.active += 1
            self.inflight[cache_key] = payload_hash
        try:
            result = await analyze_payload(payload, client=client)
            async with self.lock:
                expiry = time.time() + CACHE_TTL_SECONDS
                self.cache[cache_key] = (expiry, payload_hash, copy.deepcopy(result))
                asyncio.get_running_loop().call_later(CACHE_TTL_SECONDS, self._expire_cached, cache_key, expiry)
                while len(self.cache) > 128:
                    self.cache.popitem(last=False)
            return result
        finally:
            async with self.lock:
                self.active -= 1
                self.inflight.pop(cache_key, None)


def attach_exploration(app, db):
    service = ExplorationService(db)
    app.state.exploration = service

    @app.get("/api/explore/schema")
    async def exploration_schema():
        return get_schema()

    @app.post("/api/explore/analyze")
    async def exploration_analyze(request: Request):
        try:
            try:
                payload = await request.json()
            except Exception:
                _fail("invalid_payload", "La solicitud debe tener formato JSON.")
            # Trust only the actual peer. Root's reverse proxy pins X-Forwarded-For
            # and uvicorn must trust only loopback; Request.client reflects that.
            ip = request.client.host if request.client else "unknown"
            return await service.analyze(payload, ip)
        except ExplorationError as exc:
            return JSONResponse({"detail": exc.detail, "code": exc.code}, status_code=exc.status)
    return service
