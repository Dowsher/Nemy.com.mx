"""In-memory PDF presentation of a server-validated, signed TRAZA journey report.

No model calls, persistence, clinical scoring, or interpretation occur here.
The caller verifies the report signature before invoking ``render_report_pdf``.
"""
from __future__ import annotations

from datetime import datetime, timezone
from functools import lru_cache
from io import BytesIO
import json
import math
import os
from pathlib import Path
import re
from threading import Lock
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, Flowable, Frame, NextPageTemplate, KeepTogether,
    PageBreak, PageTemplate, Paragraph, Spacer,
)

MAX_REPORT_BYTES = 262_144
PROFESSIONAL_CAPS = {
    "synthesis": 600, "hypotheses": 600, "differential": 450,
    "priorities": 450, "limitations": 400,
}
_FONT_LOCK = Lock()
_PURPLE = colors.HexColor("#5F42C7")
_INK = colors.HexColor("#22213C")
_MUTED = colors.HexColor("#62647B")
_TEAL = colors.HexColor("#167C80")
_LINE = colors.HexColor("#E3E4EF")
_PAGE_WIDTH, _PAGE_HEIGHT = A4
_MARGIN = 44
_WIDTH = _PAGE_WIDTH - 2 * _MARGIN
_HEIGHT = _PAGE_HEIGHT - 2 * _MARGIN - 12


@lru_cache(maxsize=1)
def _fonts():
    """Prefer DejaVu on Linux, then Arial on Windows; no font downloads."""
    windows = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"
    candidates = (
        (Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
         Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")),
        (Path("/usr/share/fonts/dejavu/DejaVuSans.ttf"),
         Path("/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf")),
        (windows / "arial.ttf", windows / "arialbd.ttf"),
    )
    with _FONT_LOCK:
        for regular, bold in candidates:
            if regular.is_file() and bold.is_file():
                pdfmetrics.registerFont(TTFont("TrazaSans", str(regular)))
                pdfmetrics.registerFont(TTFont("TrazaSansBold", str(bold)))
                pdfmetrics.registerFontFamily(
                    "TrazaSans", normal="TrazaSans", bold="TrazaSansBold",
                    italic="TrazaSans", boldItalic="TrazaSansBold",
                )
                return "TrazaSans", "TrazaSansBold"
    # Standard PDF Helvetica includes all Spanish Latin-1 characters.
    return "Helvetica", "Helvetica-Bold"


def _text(value, maximum=12_000):
    if value is None:
        return ""
    if not isinstance(value, (str, int, float)) or isinstance(value, bool):
        raise ValueError("Tipo de texto no válido para el PDF.")
    value = str(value)
    if len(value) > maximum:
        raise ValueError("Una sección del informe supera el tamaño permitido.")
    value = value.translate(str.maketrans({
        "\u2010": "-", "\u2011": "-", "\u2012": "-", "\u2013": "-",
        "\u2014": "-", "\u2212": "-", "\u00a0": " ",
    }))
    # Keep text literal, remove control/bidi layout characters, and make unsupported
    # non-BMP symbols explicit rather than replacing them with a misleading glyph.
    value = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f\u202a-\u202e\u2066-\u2069]", "", value)
    value = "".join(f"[U+{ord(c):X}]" if ord(c) > 0xFFFF else c for c in value)
    return " ".join(value.split())


def _literal(value):
    return escape(_text(value))


def _date(value):
    raw = _text(value, 100)
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            return parsed.astimezone(timezone.utc).strftime("%d/%m/%Y %H:%M UTC")
    except ValueError:
        pass
    return raw


def _styles():
    regular, bold = _fonts()
    base = dict(fontName=regular, textColor=_INK, alignment=TA_LEFT,
                splitLongWords=True, allowWidows=0, allowOrphans=0)
    return {
        "title": ParagraphStyle("JTitle", fontSize=27, leading=31,
                                spaceAfter=12, fontName=bold, textColor=_INK),
        "lead": ParagraphStyle("JLead", fontSize=12.2, leading=17,
                               spaceAfter=12, **base),
        "h1": ParagraphStyle("JH1", fontSize=17, leading=22,
                             spaceBefore=16, spaceAfter=9, fontName=bold,
                             textColor=_PURPLE, keepWithNext=True),
        "h2": ParagraphStyle("JH2", fontSize=11.2, leading=15,
                             spaceBefore=10, spaceAfter=5, fontName=bold,
                             textColor=_INK, keepWithNext=True),
        "body": ParagraphStyle("JBody", fontSize=10.2, leading=14.2,
                               spaceAfter=7, **base),
        "small": ParagraphStyle("JSmall", fontSize=8.6, leading=11.5,
                                spaceAfter=5, **dict(base, textColor=_MUTED)),
        "evidence": ParagraphStyle("JEvidence", fontSize=9, leading=12.2,
                                   spaceAfter=5, leftIndent=10,
                                   borderColor=_LINE, borderWidth=1,
                                   borderPadding=6, **base),
        "score": ParagraphStyle("JScore", fontSize=9.3, leading=12.2,
                                spaceAfter=4, **base),
        "score_h": ParagraphStyle("JScoreH", fontSize=10.3, leading=13.2,
                                  spaceBefore=7, spaceAfter=4, fontName=bold,
                                  textColor=_INK, keepWithNext=True),
        "source": ParagraphStyle("JSource", fontSize=8, leading=10.4,
                                 spaceAfter=3, **dict(base, textColor=_MUTED)),
        "source_h": ParagraphStyle("JSourceH", fontSize=9.5, leading=12,
                                   spaceBefore=7, spaceAfter=3, fontName=bold,
                                   textColor=_INK, keepWithNext=True),
    }


def _p(value, style):
    return Paragraph(_literal(value), style)


def _heading(story, text, styles, level="h1"):
    story.append(_p(text, styles[level]))


def _list(story, values, styles):
    for number, value in enumerate(values, 1):
        story.append(Paragraph(f"<b>{number}.</b> {_literal(value)}", styles["body"]))


def _check_report(report):
    if not isinstance(report, dict) or report.get("version") != "4.0":
        raise ValueError("Versión de informe no válida.")
    try:
        size = len(json.dumps(report, ensure_ascii=False, allow_nan=False).encode("utf-8"))
    except (ValueError, TypeError, UnicodeError) as exc:
        raise ValueError("Informe no válido para PDF.") from exc
    if size > MAX_REPORT_BYTES:
        raise ValueError("El informe supera el tamaño permitido para PDF.")
    analysis = report.get("analysis")
    if not isinstance(analysis, dict) or not isinstance(analysis.get("profile"), dict):
        raise ValueError("El informe necesita su síntesis y perfil de IA completos.")
    for field in ("summary",):
        if not isinstance(analysis.get(field), str) or not analysis[field].strip():
            raise ValueError("Falta una sección del análisis de IA.")
    professional = analysis.get("professional")
    if not isinstance(professional, dict):
        raise ValueError("Falta la síntesis profesional.")
    for field, maximum in PROFESSIONAL_CAPS.items():
        value = professional.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise ValueError("La síntesis profesional supera su límite o está incompleta.")
    for field in ("description", "emotional_patterns", "daily_impact", "resources"):
        if not isinstance(analysis["profile"].get(field), str):
            raise ValueError("Falta una sección del perfil.")
    for field, maximum in (("patterns", 3), ("next_steps", 10), ("questions_for_professional", 10)):
        if not isinstance(analysis.get(field), list) or len(analysis[field]) > maximum:
            raise ValueError("Lista del análisis no válida.")
    for field, maximum in (("scores", 22), ("context", 100), ("evidence", 700), ("references", 60)):
        if not isinstance(report.get(field), list) or len(report[field]) > maximum:
            raise ValueError("Lista del informe no válida.")
    if not isinstance(report.get("coverage"), dict):
        raise ValueError("Falta la cobertura de instrumentos.")
    if not isinstance(report.get("meta"), dict) or report["meta"].get("clinical_reviewed") is not False:
        raise ValueError("Este PDF corresponde a una síntesis de IA sin revisión clínica.")
    # No truncation or silent omission of oversized text, even in signed reports.
    def walk(value, depth=0):
        if depth > 18:
            raise ValueError("Estructura de informe demasiado profunda.")
        if isinstance(value, str):
            _text(value)
        elif isinstance(value, float) and not math.isfinite(value):
            raise ValueError("Número no válido.")
        elif isinstance(value, dict):
            for child in value.values():
                walk(child, depth + 1)
        elif isinstance(value, list):
            if len(value) > 700:
                raise ValueError("Lista del informe demasiado larga.")
            for child in value:
                walk(child, depth + 1)
    walk(report)


def _footer(canvas, doc):
    regular, bold = _fonts()
    canvas.saveState()
    canvas.setFillColor(_PURPLE)
    canvas.setFont(bold, 9)
    canvas.drawString(_MARGIN, _PAGE_HEIGHT - 29, "TRAZA / Nemy")
    canvas.setStrokeColor(_LINE)
    canvas.line(_MARGIN, 34, _PAGE_WIDTH - _MARGIN, 34)
    canvas.setFont(regular, 8)
    canvas.setFillColor(_MUTED)
    canvas.drawString(_MARGIN, 22, "Informe personal anónimo")
    canvas.drawRightString(_PAGE_WIDTH - _MARGIN, 22, f"Pág. {doc.page}")
    canvas.restoreState()


class _RangeVisual(Flowable):
    """A native-range marker, never a clinical probability or cross-test total."""
    def __init__(self, label, value, low, high, unit="puntos"):
        super().__init__()
        self.label, self.value, self.low, self.high, self.unit = label, value, low, high, unit
        self.width, self.height = _WIDTH, 76

    def wrap(self, available_width, available_height):
        self.width = min(_WIDTH, available_width)
        return self.width, self.height

    def draw(self):
        c, w = self.canv, self.width
        regular, bold = _fonts()
        c.saveState()
        c.setFillColor(colors.HexColor("#F3F0FC"))
        c.roundRect(0, 5, w, 66, 10, stroke=0, fill=1)
        label = Paragraph(_literal(self.label), ParagraphStyle("ChartLabel", fontName=regular, fontSize=9, leading=11, textColor=_INK))
        _, h = label.wrap(w-135, 25)
        label.drawOn(c, 13, 55-h+8)
        c.setFont(bold, 10)
        c.setFillColor(_PURPLE)
        c.drawRightString(w-13, 52, f"{self.value:g} {self.unit}")
        x, y, length = 15, 29, w-30
        c.setFillColor(colors.HexColor("#DDD7EE"))
        c.roundRect(x, y, length, 7, 3.5, stroke=0, fill=1)
        ratio = max(0, min(1, (self.value-self.low)/(self.high-self.low)))
        if ratio:
            c.setFillColor(_PURPLE)
            c.roundRect(x, y, length*ratio, 7, 3.5, stroke=0, fill=1)
        c.setFillColor(_TEAL)
        c.circle(x+length*ratio, y+3.5, 5, stroke=0, fill=1)
        c.setFillColor(_MUTED)
        c.setFont(regular, 8)
        c.drawString(x, 15, f"{self.low:g}")
        c.drawRightString(w-15, 15, f"{self.high:g}")
        c.restoreState()


class _CoverageVisual(Flowable):
    def __init__(self, coverage):
        super().__init__()
        self.counts = [len(coverage.get(key, [])) for key in ("completed_form_ids", "incomplete_form_ids", "omitted_form_ids")]
        self.width, self.height = _WIDTH, 57

    def draw(self):
        c, total = self.canv, max(1, sum(self.counts))
        regular, _ = _fonts()
        c.saveState()
        c.setFont(regular, 9)
        c.setFillColor(_MUTED)
        c.drawString(0, 43, "Tu recorrido de un vistazo · cobertura de cuestionarios")
        x = 0
        shades = (_TEAL, _PURPLE, colors.HexColor("#C9C7D7"))
        for count, color in zip(self.counts, shades):
            width = _WIDTH*count/total
            c.setFillColor(color)
            if width:
                c.rect(x, 25, width, 9, stroke=0, fill=1)
            x += width
        x = 0
        for count, title, color in zip(self.counts, ("completos", "incompletos", "omitidos"), shades):
            c.setFillColor(color)
            c.circle(x+3, 10, 3, stroke=0, fill=1)
            c.setFillColor(_INK)
            c.drawString(x+12, 7, f"{count} {title}")
            x += _WIDTH/3
        c.restoreState()


def _score_visuals(result):
    """Only plot fully computed values in the exact units of their form."""
    if not result.get("complete"):
        return []
    ident = result.get("id")
    if ident == "PCPTSD5" and any(a.get("id")=="PCPTSD5_EXPOSURE" and a.get("value")==0 for a in result.get("answers", [])):
        return []
    visuals = []
    low = 1 if ident in {"MSPSS", "ECR12CL", "BRSCL", "CCQBRIEF"} else 0
    unit = "de frecuencia" if ident == "DEST" else "puntos"
    interval, value = result.get("range"), result.get("total")
    if (isinstance(interval, list) and len(interval)==2 and all(type(v) in (int, float) and math.isfinite(v) for v in interval)
            and interval[1]>interval[0] and type(value) in (int, float) and math.isfinite(value)):
        visuals.append(_RangeVisual("Resultado en su escala original", value, *interval, unit=unit))
    for sub in result.get("subscales", []):
        value, high = sub.get("value"), sub.get("max")
        if all(type(v) in (int, float) and math.isfinite(v) for v in (value, high)) and high>low:
            visuals.append(_RangeVisual(sub.get("name", "Dimensión"), value, round(high/5) if ident in {"SOCRATES", "SOCRATES8A"} else low, high))
    return visuals


class _ProfessionalSheet(Flowable):
    """One non-splittable A4 sheet, measured before rendering; never truncates."""
    def __init__(self, report):
        super().__init__()
        self.width, self.height = _WIDTH, _HEIGHT - 4
        self.report = report
        self.parts = None

    def _parts(self, font_size):
        regular, bold = _fonts()
        normal = ParagraphStyle("ProBody", fontName=regular, fontSize=font_size,
                                leading=font_size * 1.20, textColor=_INK,
                                splitLongWords=True, spaceAfter=0)
        label = ParagraphStyle("ProLabel", parent=normal, fontName=bold,
                               textColor=_PURPLE, fontSize=font_size + 0.5,
                               leading=font_size * 1.35)
        heading = ParagraphStyle("ProTitle", parent=normal, fontName=bold,
                                 fontSize=20, leading=25)
        small = ParagraphStyle("ProMeta", parent=normal, fontSize=8.5, leading=11)
        report = self.report
        meta = report["meta"]
        route = report.get("route", {})
        coverage = report["coverage"]
        completed = len(coverage.get("completed_form_ids", []))
        expected = len(coverage.get("expected_form_ids", []))
        parts = [
            (_p("Síntesis para profesionales", heading), 8),
            (_p("GENERADA POR IA PARA REVISIÓN. No es una valoración clínica autónoma ni un diagnóstico.", label), 8),
            (_p(f"Informe {_text(report.get('report_id'), 100)} | {_date(report.get('generated_at'))}", small), 2),
            (_p(f"Recorrido: {_text(route.get('name'), 100)} | Instrumentos completos: {completed}/{expected}", small), 10),
        ]
        headings = (
            ("synthesis", "Síntesis del autorreporte"),
            ("hypotheses", "Hipótesis orientativas, sujetas a entrevista"),
            ("differential", "Aspectos a diferenciar"),
            ("priorities", "Prioridades de exploración profesional"),
            ("limitations", "Límites de la información"),
        )
        for key, title in headings:
            parts.append((_p(title, label), 3))
            parts.append((_p(report["analysis"]["professional"][key], normal), 6))
        parts.append((_p(
            f"Origen: {_text(meta.get('provider'), 100)} / {_text(meta.get('model'), 100)}. "
            f"Versión del análisis: {_text(meta.get('prompt_version'), 120)}. "
            "Basado en autorreporte y puntuaciones del servidor; sin firma o revisión de un profesional.", small), 0))
        return parts

    def wrap(self, available_width, available_height):
        for size in (10.2, 9.8, 9.4, 9.0):
            parts = self._parts(size)
            height = sum(p.wrap(self.width, self.height)[1] + gap for p, gap in parts)
            if height <= self.height:
                self.parts = parts
                return self.width, self.height
        raise ValueError("La síntesis profesional no cabe en una hoja legible; revisa su longitud.")

    def split(self, available_width, available_height):
        return []

    def draw(self):
        self.canv.saveState()
        y = self.height
        for paragraph, gap in self.parts:
            _, height = paragraph.wrap(self.width, self.height)
            y -= height
            paragraph.drawOn(self.canv, 0, y)
            y -= gap
        self.canv.restoreState()


def _evidence(story, ids, index, styles):
    for identifier in ids:
        item = index.get(identifier)
        if item:
            story.append(_p(f"{identifier} | {item.get('question', '')} - {item.get('label', '')}", styles["evidence"]))
        else:
            story.append(_p(f"Referencia del informe: {identifier}", styles["small"]))


def render_report_pdf(report: dict) -> bytes:
    """Render a complete journey report; caller must verify its HMAC signature.

    Raises ValueError for invalid/oversized content. All personal sections and
    appendices precede the final single professional page. Nothing is persisted.
    """
    _check_report(report)
    styles = _styles()
    story = []
    analysis, coverage, meta = report["analysis"], report["coverage"], report["meta"]
    evidence_index = {entry.get("id"): entry for entry in report["evidence"]}
    route = report.get("route", {})
    story.append(Spacer(1, 8))
    story.append(_p("Tu mapa personal", styles["title"]))
    story.append(_p("Un punto de partida para entender tus respuestas y conversar sobre lo que necesitas.", styles["lead"]))
    story.append(_p(f"Recorrido {_text(route.get('name'), 100)} | {_date(report.get('generated_at'))}", styles["small"]))
    story.append(_p(f"Informe anónimo: {_text(report.get('report_id'), 100)}", styles["small"]))
    story.append(_CoverageVisual(coverage))
    _heading(story, "Lo principal", styles)
    story.append(_p(analysis["summary"], styles["lead"]))
    _heading(story, "Tu perfil en este momento", styles)
    for field, title in (
        ("description", "Panorama"), ("emotional_patterns", "Lo emocional"),
        ("daily_impact", "En tu día a día"), ("resources", "Apoyos y recursos"),
    ):
        _heading(story, title, styles, "h2")
        story.append(_p(analysis["profile"][field], styles["body"]))

    _heading(story, "Posibles conexiones para explorar", styles)
    if not analysis["patterns"]:
        story.append(_p("No se propusieron conexiones suficientes entre experiencias y reacciones actuales. El resumen anterior conserva lo que sí se pudo describir.", styles["body"]))
    for pattern in analysis["patterns"]:
        _heading(story, pattern.get("title", "Patrón para explorar"), styles, "h2")
        story.append(_p(pattern.get("explanation", ""), styles["body"]))
        _evidence(story, pattern.get("evidence_ids", []), evidence_index, styles)
        story.append(Paragraph(f"<b>Qué queda por aclarar.</b> {_literal(pattern.get('uncertainty', ''))}", styles["body"]))
        story.append(Paragraph(f"<b>Cómo abordarlo en consulta.</b> {_literal(pattern.get('professional_work', ''))}", styles["body"]))
    _heading(story, "Qué puedes hacer con este mapa", styles)
    _list(story, analysis["next_steps"], styles)
    _heading(story, "Ideas para tu próxima consulta", styles)
    _list(story, analysis["questions_for_professional"], styles)

    _heading(story, "Qué se revisó y qué quedó abierto", styles)
    counts = (
        ("Completos", "completed_form_ids"), ("Incompletos", "incomplete_form_ids"),
        ("Omitidos", "omitted_form_ids"),
    )
    for title, key in counts:
        ids = coverage.get(key, [])
        story.append(Paragraph(f"<b>{title} ({len(ids)}):</b> {_literal(', '.join(ids) if ids else 'Ninguno')}", styles["body"]))
    story.append(_p(f"Contexto respondido: {coverage.get('context_answered', 0)} de {coverage.get('context_total', 0)} preguntas.", styles["body"]))
    recognized = [item for item in report["context"]
                  if re.fullmatch(r"E(?:10|[1-9])", str(item.get("id", "")))
                  and type(item.get("value")) is int and item["value"] == 1]
    if recognized:
        _heading(story, "Experiencias que reconociste", styles, "h2")
        for item in recognized:
            story.append(_p(item.get("question", ""), styles["body"]))
    story.append(_p(report.get("limitations", ""), styles["body"]))
    if report.get("safety_note"):
        story.append(_p(report["safety_note"], styles["body"]))
    story.append(_p(
        f"Síntesis generada por IA: {_text(meta.get('provider'), 100)} / {_text(meta.get('model'), 100)}. "
        f"Versión {_text(meta.get('prompt_version'), 120)}. Las puntuaciones siguientes se calcularon con reglas de cada formulario; no se suman en un total de trauma.",
        styles["small"],
    ))

    story.append(PageBreak())
    _heading(story, "Puntuaciones y versiones", styles)
    story.append(_p("Cada resultado conserva su escala. Los instrumentos incompletos no reciben una puntuación completa; las omisiones no valen cero.", styles["body"]))
    for result in report["scores"]:
        section_start = len(story)
        _heading(story, result.get("name", result.get("id", "")), styles, "score_h")
        story.append(_p(f"{result.get('id', '')} | {result.get('version', '')}", styles["source"]))
        story.append(_p(f"Respuestas requeridas: {result.get('answered', 0)}/{result.get('required', 0)}. "
                        + ("Completo." if result.get("complete") else "Incompleto."), styles["score"]))
        story.extend(_score_visuals(result))
        if result.get("total") is not None:
            value = str(result["total"])
            interval = result.get("range")
            if isinstance(interval, list) and len(interval) == 2:
                value += f" (rango {interval[0]} a {interval[1]})"
            story.append(Paragraph(f"<b>Puntuación:</b> {_literal(value)}", styles["score"]))
        for subscale in result.get("subscales", []):
            story.append(_p(f"{subscale.get('name', '')}: {subscale.get('value', '')}" +
                            (f" / {subscale['max']}" if subscale.get("max") is not None else ""), styles["score"]))
        story.append(_p(result.get("summary", ""), styles["score"]))
        for finding in result.get("findings", []):
            story.append(_p(finding, styles["source"]))
        if result.get("missing_ids"):
            story.append(_p("Ítems sin respuesta puntuable: " + ", ".join(result["missing_ids"]), styles["source"]))
        # Keep the title, source and first visual together at a page boundary.
        grouped = min(4, len(story)-section_start)
        story[section_start:section_start+grouped] = [KeepTogether(story[section_start:section_start+grouped])]

    lec_notes = [item for result in report["scores"] if result.get("id") == "LEC5"
                 for item in result.get("answers", [])
                 if isinstance(item.get("value"), str)
                 and item["value"].strip() and item["value"] not in ("skipped", "unknown")]
    if lec_notes:
        _heading(story, "Notas que conservaste para ti", styles)
        story.append(_p("Textos opcionales de LEC-5. No se enviaron a la IA ni se utilizaron para elaborar sus hipótesis.", styles["body"]))
        for item in lec_notes:
            _heading(story, item.get("question", ""), styles, "h2")
            story.append(_p(item["value"], styles["body"]))
    story.append(_p("La descarga JSON conserva el registro completo de tus respuestas. Este PDF prioriza la síntesis, las puntuaciones y las respuestas usadas como referencia.", styles["small"]))

    _heading(story, "Fuentes, condiciones y alcance", styles)
    source_urls = set()
    for result in report["scores"]:
        _heading(story, result.get("name", result.get("id", "")), styles, "source_h")
        source_urls.add(result.get("source_url", ""))
        printed = []
        def note_once(value):
            cleaned = _text(value)
            if cleaned and not any(cleaned.casefold() in old.casefold() for old in printed):
                story.append(_p(cleaned, styles["source"]))
                printed.append(cleaned)
        for key in ("source_url", "attribution", "content_license", "intended_use", "translation_warning", "presentation_note"):
            if result.get(key):
                note_once(result[key])
        for condition in result.get("registration", {}).get("conditions", []):
            note_once(condition)
    additional_references = [ref for ref in report["references"] if ref.get("url") not in source_urls]
    if additional_references:
        _heading(story, "Lecturas de referencia", styles, "h2")
        for reference in additional_references:
            story.append(_p(reference.get("title", ""), styles["source"]))
            story.append(_p(reference.get("url", ""), styles["source"]))

    # Appendices end here. The final template has exactly one non-splittable sheet.
    story.extend([NextPageTemplate("professional"), PageBreak(), _ProfessionalSheet(report)])
    buffer = BytesIO()
    doc = BaseDocTemplate(
        buffer, pagesize=A4, leftMargin=_MARGIN, rightMargin=_MARGIN,
        topMargin=_MARGIN + 6, bottomMargin=_MARGIN + 6,
        title="TRAZA - Mapa personal y síntesis para profesionales",
        author="TRAZA / Proyecto Nemy", subject="Autorreporte y síntesis generada por IA para revisión",
        pageCompression=1, allowSplitting=True,
    )
    frame = lambda name: Frame(_MARGIN, _MARGIN + 6, _WIDTH, _HEIGHT,
                               leftPadding=0, rightPadding=0, topPadding=0,
                               bottomPadding=0, id=name)
    doc.addPageTemplates([
        PageTemplate(id="personal", frames=[frame("personal")], onPage=_footer),
        PageTemplate(id="professional", frames=[frame("professional")], onPage=_footer),
    ])
    doc.build(story)
    return buffer.getvalue()
