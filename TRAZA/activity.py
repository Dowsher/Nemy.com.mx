"""Consented public activity, encrypted health history and restricted administration.

Visit metadata is deliberately coarse. Tokens are derived in memory and only their
hashes persist. Answers, consent, event payloads and AI reports use Database's
application encryption; payload fingerprints are keyed to prevent answer guessing.
"""
from __future__ import annotations

import base64
from collections import OrderedDict, defaultdict, deque
from datetime import date, datetime, time as daytime, timedelta, timezone
import hashlib
import hmac
import inspect
import json
import re
import time
import threading
import uuid

from fastapi import HTTPException, Request

import battery
import journey

VERSION = "5.0"
DEVICE_KINDS = {"mobile", "tablet", "desktop", "unknown"}
OS_NAMES = {"Android", "iOS", "Windows", "macOS", "Linux", "other"}
BROWSERS = {"Chrome", "Safari", "Firefox", "Edge", "other"}
EVENT_TYPES = {"answer", "omit_form", "resume_form", "route_change", "complete", "ai_request", "ai_error", "export"}
EVENT_KEYS = {"event_id", "sequence", "type", "form_id", "question_id", "value"}
MAX_EVENTS = 5000
MAX_BODY = 262144


def fail(message, status=422):
    raise HTTPException(status, message)


def exact(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys):
        fail("La solicitud contiene campos ausentes o no permitidos.")


def ident(value):
    try:
        if not isinstance(value, str) or len(value) != 36 or str(uuid.UUID(value)) != value.lower():
            raise ValueError()
    except (ValueError, AttributeError):
        fail("Se requiere un identificador válido.")
    return value.lower()


def iso(stamp):
    return datetime.fromtimestamp(stamp, timezone.utc).isoformat()


def canonical(value):
    try:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
    except (TypeError, ValueError, OverflowError, RecursionError):
        fail("El contenido no es válido.")


class ActivityService:
    def __init__(self, db):
        self.db = db
        self.forms = {f["id"]: f for f in battery.load_bank()["instruments"] if f["status"] == "available"}
        self.key = hmac.new(db.cipher._signing_key, b"traza-activity-v5", hashlib.sha256).digest()
        self.retention_days = 90
        self.rate_buckets = OrderedDict()
        self.global_rates = defaultdict(deque)
        self.rate_lock = threading.Lock()
        self.last_rate_sweep = 0.0
        self.last_purge = 0.0
        with db.connection() as con:
            con.executescript("""
            CREATE TABLE IF NOT EXISTS activity_visits(
              id TEXT PRIMARY KEY,event_id TEXT NOT NULL UNIQUE,request_hash TEXT NOT NULL,
              token_hash TEXT NOT NULL UNIQUE,device_kind TEXT NOT NULL,os TEXT NOT NULL,
              browser TEXT NOT NULL,viewport_width INTEGER NOT NULL,viewport_height INTEGER NOT NULL,
              entry TEXT NOT NULL,created_at REAL NOT NULL,updated_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS activity_sessions(
              id TEXT PRIMARY KEY,visit_id TEXT NOT NULL REFERENCES activity_visits(id) ON DELETE CASCADE,
              event_id TEXT NOT NULL UNIQUE,request_hash TEXT NOT NULL,kind TEXT NOT NULL,
              route_id TEXT,form_id TEXT,consent_enc TEXT NOT NULL,snapshot_enc TEXT NOT NULL,
              revision INTEGER NOT NULL DEFAULT 0,completed INTEGER NOT NULL DEFAULT 0,
              answer_count INTEGER NOT NULL DEFAULT 0,report_count INTEGER NOT NULL DEFAULT 0,
              created_at REAL NOT NULL,updated_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS activity_events(
              event_id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES activity_sessions(id) ON DELETE CASCADE,
              sequence INTEGER NOT NULL,type TEXT NOT NULL,request_hash TEXT NOT NULL,
              payload_enc TEXT NOT NULL,created_at REAL NOT NULL,UNIQUE(session_id,sequence));
            CREATE TABLE IF NOT EXISTS activity_reports(
              event_id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES activity_sessions(id) ON DELETE CASCADE,
              report_id TEXT NOT NULL,request_hash TEXT NOT NULL,payload_enc TEXT NOT NULL,
              revision INTEGER NOT NULL,created_at REAL NOT NULL,UNIQUE(session_id,report_id));
            CREATE TABLE IF NOT EXISTS activity_session_forms(
              session_id TEXT NOT NULL REFERENCES activity_sessions(id) ON DELETE CASCADE,
              form_id TEXT NOT NULL,started_at REAL NOT NULL,updated_at REAL NOT NULL,
              completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session_id,form_id));
            CREATE TABLE IF NOT EXISTS activity_queries(
              request_id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES activity_sessions(id) ON DELETE CASCADE,
              request_hash TEXT NOT NULL,payload_enc TEXT NOT NULL,status TEXT NOT NULL,
              result_enc TEXT,error_code TEXT,attempt_count INTEGER NOT NULL DEFAULT 1,
              revision INTEGER NOT NULL,created_at REAL NOT NULL,updated_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS activity_report_receipts(
              event_id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES activity_sessions(id) ON DELETE CASCADE,
              request_hash TEXT NOT NULL,report_id TEXT NOT NULL,created_at REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS activity_visits_created ON activity_visits(created_at);
            CREATE INDEX IF NOT EXISTS activity_sessions_created ON activity_sessions(created_at,id);
            CREATE INDEX IF NOT EXISTS activity_sessions_visit ON activity_sessions(visit_id);
            CREATE INDEX IF NOT EXISTS activity_events_session ON activity_events(session_id,sequence);
            CREATE INDEX IF NOT EXISTS activity_reports_session ON activity_reports(session_id,created_at);
            CREATE INDEX IF NOT EXISTS activity_forms_form ON activity_session_forms(form_id);
            CREATE INDEX IF NOT EXISTS activity_queries_session ON activity_queries(session_id,created_at);
            """)
        self.purge(force=True)

    def fingerprint(self, payload):
        return hmac.new(self.key, b"payload:" + canonical(payload), hashlib.sha256).hexdigest()

    def rate(self, category, network_address):
        """Bound writes before SQLite using only keyed, hourly RAM counters.

        No forwarded header is trusted here. Uvicorn's trusted proxy processing
        supplies request.client; neither raw address nor its hash enters SQLite.
        """
        limits = {"visits": (120, 3000), "sessions": (90, 2500), "events": (12000, 60000),
                  "reports": (100, 5000), "delete": (90, 2500)}
        local_max, global_max = limits[category]
        address_hash = hmac.new(self.key, b"rate:" + str(network_address or "unknown").encode(), hashlib.sha256).hexdigest()
        key, now = (category, address_hash), time.monotonic()
        cutoff = now - 3600
        with self.rate_lock:
            if now - self.last_rate_sweep > 60:
                for bucket_key in list(self.rate_buckets):
                    queue = self.rate_buckets[bucket_key]
                    while queue and queue[0] <= cutoff:
                        queue.popleft()
                    if not queue:
                        self.rate_buckets.pop(bucket_key, None)
                self.last_rate_sweep = now
            global_queue = self.global_rates[category]
            while global_queue and global_queue[0] <= cutoff:
                global_queue.popleft()
            queue = self.rate_buckets.get(key)
            if queue is not None:
                while queue and queue[0] <= cutoff:
                    queue.popleft()
            if len(global_queue) >= global_max or queue is not None and len(queue) >= local_max or queue is None and len(self.rate_buckets) >= 20000:
                raise HTTPException(429, "Se alcanzó el límite temporal de guardado. Espera y reintenta el mismo envío.", headers={"Retry-After": "60"})
            if queue is None:
                queue = self.rate_buckets[key] = deque()
            queue.append(now)
            global_queue.append(now)

    def audit(self, con, actor, action, subject=None):
        hashed = lambda value: hashlib.sha256(str(value).encode()).hexdigest()[:20] if value else None
        con.execute("INSERT INTO audit(at,actor,action,subject,outcome) VALUES(?,?,?,?,?)",
                    (iso(time.time()), hashed(actor), action, hashed(subject), "ok"))

    def purge(self, force=False):
        now = time.time()
        if not force and now - self.last_purge < 3600:
            return
        with self.db.connection(transaction=True) as con:
            cutoff = now - self.retention_days * 86400
            # Retention starts at creation, so abandoned and active sessions both
            # have a predictable maximum lifetime. Cascades remove health history.
            con.execute("DELETE FROM activity_visits WHERE created_at<?", (cutoff,))
        self.last_purge = now

    def visit(self, payload):
        exact(payload, {"event_id", "device", "viewport", "entry"})
        event_id = ident(payload["event_id"])
        exact(payload["device"], {"kind", "os", "browser"})
        d = payload["device"]
        if any(not isinstance(d[k], str) for k in ("kind", "os", "browser")) or d["kind"] not in DEVICE_KINDS or d["os"] not in OS_NAMES or d["browser"] not in BROWSERS:
            fail("Selecciona metadatos de dispositivo válidos.")
        exact(payload["viewport"], {"width", "height"})
        if any(type(payload["viewport"][k]) is not int or not 1 <= payload["viewport"][k] <= 16000 for k in ("width", "height")):
            fail("El tamaño de pantalla no es válido.")
        if not isinstance(payload["entry"], str) or payload["entry"] not in {"home", "battery", "explore"}:
            fail("La página de entrada no es válida.")
        fingerprint = self.fingerprint(payload)
        # The event UUID is random; HMAC prevents deriving a token from a visit ID.
        token = base64.urlsafe_b64encode(hmac.new(self.key, b"visit:" + event_id.encode(), hashlib.sha256).digest()).decode().rstrip("=")
        now = time.time()
        self.purge()
        with self.db.connection(transaction=True) as con:
            old = con.execute("SELECT id,request_hash FROM activity_visits WHERE event_id=?", (event_id,)).fetchone()
            if old:
                if not hmac.compare_digest(old["request_hash"], fingerprint):
                    fail("Ese identificador ya se usó para otra visita.", 409)
                return {"visit_id": old["id"], "visit_token": token}
            # Global coarse abuse control does not retain network identifiers.
            if con.execute("SELECT count(*) FROM activity_visits WHERE created_at>?", (now - 60,)).fetchone()[0] >= 600:
                fail("Hay muchas entradas en este momento. Inténtalo de nuevo en un minuto.", 429)
            visit_id = str(uuid.uuid4())
            # Round viewport to 100 pixels to avoid a screen-size fingerprint.
            w, h = (min(16000, max(100, round(payload["viewport"][k] / 100) * 100)) for k in ("width", "height"))
            con.execute("INSERT INTO activity_visits VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        (visit_id, event_id, fingerprint, hashlib.sha256(token.encode()).hexdigest(), d["kind"], d["os"], d["browser"], w, h, payload["entry"], now, now))
        return {"visit_id": visit_id, "visit_token": token}

    def _visit(self, con, token):
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
            fail("La visita no está autorizada. Vuelve al inicio.", 401)
        row = con.execute("SELECT * FROM activity_visits WHERE token_hash=?", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        if row is None or row["created_at"] < time.time() - self.retention_days * 86400:
            fail("La visita caducó. Vuelve al inicio.", 401)
        return row

    def _session(self, con, session_id, token):
        visit = self._visit(con, token)
        row = con.execute("SELECT * FROM activity_sessions WHERE id=? AND visit_id=?", (ident(session_id), visit["id"])).fetchone()
        if row is None:
            fail("No se encontró una sesión de esta visita.", 404)
        return row

    def session(self, payload, token):
        exact(payload, {"event_id", "kind", "route_id", "form_id", "consent"})
        event_id = ident(payload["event_id"])
        exact(payload["consent"], {"adult", "storage", "version"})
        if payload["consent"] != {"adult": True, "storage": True, "version": VERSION} or type(payload["consent"]["adult"]) is not bool or type(payload["consent"]["storage"]) is not bool:
            fail("Confirma mayoría de edad y almacenamiento para administración antes de responder.", 403)
        if payload["kind"] == "journey":
            if not isinstance(payload["route_id"], str) or payload["route_id"] not in journey.ROUTES or payload["form_id"] is not None:
                fail("El recorrido no es válido.")
        elif payload["kind"] == "battery":
            if payload["route_id"] is not None or not isinstance(payload["form_id"], str) or payload["form_id"] not in self.forms:
                fail("El cuestionario no está disponible.")
        else:
            fail("El tipo de sesión no es válido.")
        self.purge()
        now, fp = time.time(), self.fingerprint(payload)
        with self.db.connection(transaction=True) as con:
            visit = self._visit(con, token)
            old = con.execute("SELECT * FROM activity_sessions WHERE event_id=?", (event_id,)).fetchone()
            if old:
                if old["visit_id"] != visit["id"] or not hmac.compare_digest(old["request_hash"], fp):
                    fail("Ese identificador ya se usó para otra sesión.", 409)
                return {"session_id": old["id"], "revision": old["revision"]}
            if con.execute("SELECT count(*) FROM activity_sessions WHERE visit_id=?", (visit["id"],)).fetchone()[0] >= 30:
                fail("Esta visita alcanzó el límite de recorridos.", 429)
            session_id = str(uuid.uuid4())
            snapshot = {"context": {}, "forms": {}, "omitted_form_ids": []}
            con.execute("INSERT INTO activity_sessions(id,visit_id,event_id,request_hash,kind,route_id,form_id,consent_enc,snapshot_enc,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        (session_id, visit["id"], event_id, fp, payload["kind"], payload["route_id"], payload["form_id"], self.db.encrypt(payload["consent"]), self.db.encrypt(snapshot), now, now))
            con.execute("UPDATE activity_visits SET updated_at=? WHERE id=?", (now, visit["id"]))
        return {"session_id": session_id, "revision": 0}

    def _expected(self, row, snapshot):
        return journey.expected_forms(row["route_id"], snapshot["context"]) if row["kind"] == "journey" else [row["form_id"]]

    def _prune_answers(self, form, answers, protected=None):
        """Mirror the browser's dependent-option pruning before bank validation.

        A changed inventory may invalidate the old main-event selection. That
        old dependent answer is removed rather than preventing the parent edit.
        The answer currently submitted is still validated, never silently removed.
        """
        result = dict(answers)
        for item in battery.form_items(form):
            qid = item["id"]
            if not battery.visible(item, result):
                result.pop(qid, None)
                continue
            rules = item.get("filter_options_by_answers", {})
            value = result.get(qid)
            if qid != protected and rules and battery._known(value, item) and not battery.visible({"show_if": rules.get(str(value), {"id": "__invalid", "equals": True})}, result):
                result.pop(qid, None)
        return battery.validate_answers(form, result)

    def _prune(self, row, snapshot):
        if row["kind"] == "journey":
            context = snapshot["context"]
            for q in journey.context_questions(row["route_id"]):
                if not battery.visible(q, context):
                    context.pop(q["id"], None)
            allowed = {q["id"] for q in journey.context_questions(row["route_id"])}
            snapshot["context"] = {k: v for k, v in context.items() if k in allowed}
        expected = set(self._expected(row, snapshot))
        snapshot["omitted_form_ids"] = [f for f in snapshot["omitted_form_ids"] if f in expected]
        for fid in list(snapshot["forms"]):
            if fid not in expected or fid in snapshot["omitted_form_ids"]:
                snapshot["forms"].pop(fid)
            else:
                snapshot["forms"][fid]["answers"] = self._prune_answers(self.forms[fid], snapshot["forms"][fid]["answers"])

    def _apply(self, row, snapshot, payload):
        typ, fid, qid, value = (payload[k] for k in ("type", "form_id", "question_id", "value"))
        if typ == "answer":
            if not isinstance(qid, str) or not qid or len(qid) > 80:
                fail("La pregunta no es válida.")
            if fid == "context":
                index = ({q["id"]: q for q in journey.context_questions(row["route_id"])}
                         if row["kind"] == "journey" else {"S3": journey.QUESTIONS["S3"]})
                q = index.get(qid)
                if q is None or not battery.visible(q, snapshot["context"]):
                    fail("Esa pregunta no corresponde a la rama activa.")
                if value is not None and (type(value) is not int or value not in {o["value"] for o in q["options"]}):
                    fail("La respuesta de contexto no está entre las opciones.")
                if value is None:
                    snapshot["context"].pop(qid, None)
                else:
                    snapshot["context"][qid] = value
            else:
                if not isinstance(fid, str) or fid not in self._expected(row, snapshot) or fid in snapshot["omitted_form_ids"]:
                    fail("El formulario no está incluido en esta sesión.")
                form = self.forms[fid]
                prior = snapshot["forms"].get(fid, {"version": form.get("version", "1.0"), "answers": {}})
                q = next((q for q in battery.form_items(form) if q["id"] == qid), None)
                if q is None or not battery.visible(q, prior["answers"]):
                    fail("Esa pregunta no corresponde a la rama activa.")
                candidate = dict(prior["answers"])
                if value is None:
                    candidate.pop(qid, None)
                else:
                    candidate[qid] = value
                try:
                    clean = self._prune_answers(form, candidate, protected=qid)
                except (battery.BatteryInputError, TypeError, ValueError, KeyError):
                    fail("La respuesta no coincide con las opciones de la pregunta.")
                snapshot["forms"][fid] = {"version": form.get("version", "1.0"), "answers": clean}
        elif typ in {"omit_form", "resume_form"}:
            if qid is not None or value is not None or not isinstance(fid, str) or fid not in self._expected(row, snapshot):
                fail("El formulario que quieres omitir o retomar no es válido.")
            omitted = snapshot["omitted_form_ids"]
            if typ == "omit_form" and fid not in omitted:
                omitted.append(fid)
            if typ == "resume_form" and fid in omitted:
                omitted.remove(fid)
        elif typ == "route_change":
            if fid is not None or qid is not None or row["kind"] != "journey" or not isinstance(value, str) or value not in journey.ROUTES:
                fail("El cambio de recorrido no es válido.")
            row = dict(row)
            row["route_id"] = value
        else:
            if fid is not None or qid is not None or (typ == "export" and value not in (None, "pdf", "json")) or (typ != "export" and value is not None):
                fail("El evento tiene datos que no corresponden a su tipo.")
            if typ in {"ai_request", "ai_error"} and row["kind"] != "journey":
                fail("Los cuestionarios individuales no envían una consulta a IA.")
        self._prune(row, snapshot)
        return row

    def event(self, session_id, payload, token):
        exact(payload, EVENT_KEYS)
        event_id = ident(payload["event_id"])
        if type(payload["sequence"]) is not int or not 1 <= payload["sequence"] <= MAX_EVENTS or not isinstance(payload["type"], str) or payload["type"] not in EVENT_TYPES:
            fail("El evento o su secuencia no son válidos.")
        if len(canonical(payload)) > 4096:
            fail("El evento es demasiado grande.", 413)
        fp, now = self.fingerprint(payload), time.time()
        self.purge()
        with self.db.connection(transaction=True) as con:
            row = self._session(con, session_id, token)
            old = con.execute("SELECT * FROM activity_events WHERE event_id=?", (event_id,)).fetchone()
            if old:
                if old["session_id"] != row["id"] or not hmac.compare_digest(old["request_hash"], fp):
                    fail("Ese evento ya se usó con otro contenido.", 409)
                return {"ok": True, "revision": old["sequence"], "received_sequence": old["sequence"]}
            if payload["sequence"] != row["revision"] + 1:
                fail("El orden de guardado cambió. Reintenta el envío pendiente antes de avanzar.", 409)
            snapshot = self.db.decrypt(row["snapshot_enc"])
            updated = self._apply(row, snapshot, payload)
            con.execute("INSERT INTO activity_events VALUES(?,?,?,?,?,?,?)", (event_id, row["id"], payload["sequence"], payload["type"], fp, self.db.encrypt(payload), now))
            completed = 1 if payload["type"] == "complete" else (0 if payload["type"] in {"answer", "omit_form", "resume_form", "route_change"} else row["completed"])
            con.execute("UPDATE activity_sessions SET route_id=?,snapshot_enc=?,revision=?,completed=?,answer_count=answer_count+?,updated_at=? WHERE id=?",
                        (updated["route_id"], self.db.encrypt(snapshot), payload["sequence"], completed, int(payload["type"] == "answer"), now, row["id"]))
            # Form completion is recomputed, never inferred from a button click.
            con.execute("UPDATE activity_session_forms SET completed=0,updated_at=? WHERE session_id=?", (now, row["id"]))
            for fid, form_payload in snapshot["forms"].items():
                complete = int(battery.score(self.forms[fid], form_payload["answers"])["complete"])
                con.execute("INSERT INTO activity_session_forms VALUES(?,?,?,?,?) ON CONFLICT(session_id,form_id) DO UPDATE SET updated_at=excluded.updated_at,completed=excluded.completed",
                            (row["id"], fid, now, now, complete))
            con.execute("UPDATE activity_visits SET updated_at=? WHERE id=?", (now, row["visit_id"]))
        return {"ok": True, "revision": payload["sequence"], "received_sequence": payload["sequence"]}

    def _report_matches(self, row, snapshot, report):
        if row["kind"] != "journey" or report.get("route", {}).get("id") != row["route_id"]:
            fail("El informe no corresponde a este recorrido.", 409)
        context = {q["id"]: q.get("value") for q in report.get("context", []) if q.get("value") is not None}
        if context != snapshot["context"]:
            fail("Guarda las respuestas de contexto antes de guardar el informe.", 409)
        submitted = {s["id"]: {a["id"]: a.get("value") for a in s.get("answers", []) if a.get("value") is not None} for s in report.get("scores", [])}
        expected = set(self._expected(row, snapshot)) - set(snapshot["omitted_form_ids"])
        if set(submitted) != expected:
            fail("El informe y los cuestionarios incluidos no coinciden.", 409)
        for fid in expected:
            stored = {k: v for k, v in snapshot["forms"].get(fid, {}).get("answers", {}).items() if v is not None}
            if submitted[fid] != stored:
                fail("Guarda todas las respuestas antes de guardar el informe.", 409)

    def begin_query(self, session_id, token, payload, clean):
        """Authenticate and persist an AI query before the provider can run."""
        request_id, fp, now = ident(clean["request_id"]), self.fingerprint(payload), time.time()
        self.purge()
        with self.db.connection(transaction=True) as con:
            row = self._session(con, session_id, token)
            if row["kind"] != "journey" or row["route_id"] != clean["route_id"]:
                fail("La consulta no corresponde a la sesión guardada.", 409)
            snapshot = self.db.decrypt(row["snapshot_enc"])
            expected = set(self._expected(row, snapshot)) - set(snapshot["omitted_form_ids"])
            if clean["context_answers"] != snapshot["context"] or set(clean["forms"]) != expected:
                fail("Guarda el contexto y los cuestionarios antes de crear el informe.", 409)
            for fid, data in clean["forms"].items():
                stored = {k: v for k, v in snapshot["forms"].get(fid, {}).get("answers", {}).items() if v is not None}
                submitted = {k: v for k, v in data["answers"].items() if v is not None}
                if stored != submitted:
                    fail("Guarda las respuestas pendientes antes de crear el informe.", 409)
            old = con.execute("SELECT * FROM activity_queries WHERE request_id=?", (request_id,)).fetchone()
            if old:
                if old["session_id"] != row["id"] or not hmac.compare_digest(old["request_hash"], fp):
                    fail("Ese identificador ya corresponde a otra consulta.", 409)
                if old["status"] == "succeeded":
                    return {"request_id": request_id, "session_id": row["id"], "cached": self.db.decrypt(old["result_enc"])}
                if old["status"] == "pending" and now - old["updated_at"] < 180:
                    fail("La consulta sigue en curso. Espera a que termine y vuelve a intentar.", 409)
                if old["attempt_count"] >= 10:
                    fail("Esta consulta alcanzó el límite de reintentos.", 429)
                con.execute("UPDATE activity_queries SET status='pending',error_code=NULL,attempt_count=attempt_count+1,revision=?,updated_at=? WHERE request_id=?", (row["revision"], now, request_id))
            else:
                con.execute("INSERT INTO activity_queries(request_id,session_id,request_hash,payload_enc,status,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                            (request_id, row["id"], fp, self.db.encrypt(payload), "pending", row["revision"], now, now))
        return {"request_id": request_id, "session_id": row["id"], "cached": None}

    def authorize_score(self, session_id, token, form_id, answers):
        """A public score can only describe already consented, saved answers."""
        self.purge()
        with self.db.connection() as con:
            row = self._session(con, session_id, token)
            if row["kind"] != "battery" or row["form_id"] != form_id:
                fail("El resultado no corresponde al cuestionario guardado.", 409)
            snapshot = self.db.decrypt(row["snapshot_enc"])
            try:
                cleaned = battery.validate_answers(self.forms[form_id], answers)
            except (battery.BatteryInputError, TypeError, ValueError, KeyError):
                fail("La respuesta no coincide con el cuestionario.")
            stored = {k: v for k, v in snapshot["forms"].get(form_id, {}).get("answers", {}).items() if v is not None}
            if {k: v for k, v in cleaned.items() if v is not None} != stored:
                fail("Guarda las respuestas pendientes antes de ver el resultado.", 409)

    def finish_query(self, query, packet):
        """Durably store the signed response before returning it to the browser."""
        now = time.time()
        report = packet["report"]
        with self.db.connection(transaction=True) as con:
            row = con.execute("SELECT * FROM activity_queries WHERE request_id=? AND session_id=?", (query["request_id"], query["session_id"])).fetchone()
            if row is None:
                fail("La sesión se eliminó mientras se creaba el informe.", 409)
            con.execute("UPDATE activity_queries SET status='succeeded',result_enc=?,error_code=NULL,updated_at=? WHERE request_id=?", (self.db.encrypt(packet), now, row["request_id"]))
            existing = con.execute("SELECT 1 FROM activity_reports WHERE session_id=? AND report_id=?", (row["session_id"], report["report_id"])).fetchone()
            if not existing:
                event_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "traza-query:" + row["session_id"] + ":" + row["request_id"]))
                con.execute("INSERT INTO activity_reports VALUES(?,?,?,?,?,?,?)", (event_id, row["session_id"], report["report_id"], self.fingerprint({"event_id": event_id, "packet": packet}), self.db.encrypt(packet), row["revision"], now))
                con.execute("UPDATE activity_sessions SET report_count=report_count+1,updated_at=? WHERE id=?", (now, row["session_id"]))

    def fail_query(self, query, error_code="internal_error"):
        # No provider messages, stack traces or body excerpts enter the log.
        if not isinstance(error_code, str) or not re.fullmatch(r"[a-z_]{1,64}", error_code):
            error_code = "internal_error"
        with self.db.connection(transaction=True) as con:
            con.execute("UPDATE activity_queries SET status='error',error_code=?,updated_at=? WHERE request_id=? AND session_id=? AND status='pending'", (error_code, time.time(), query["request_id"], query["session_id"]))

    def report(self, session_id, payload, token, journey_service):
        exact(payload, {"event_id", "packet"})
        event_id = ident(payload["event_id"])
        try:
            report = journey_service.verify_signed_report(payload["packet"])
        except journey.JourneyError as exc:
            fail(exc.detail, exc.status)
        fp, now = self.fingerprint(payload), time.time()
        self.purge()
        with self.db.connection(transaction=True) as con:
            row = self._session(con, session_id, token)
            receipt = con.execute("SELECT * FROM activity_report_receipts WHERE event_id=?", (event_id,)).fetchone()
            if receipt:
                if receipt["session_id"] != row["id"] or not hmac.compare_digest(receipt["request_hash"], fp):
                    fail("Ese envío ya se usó con otro informe.", 409)
                return {"ok": True, "report_id": receipt["report_id"], "saved": True}
            old = con.execute("SELECT * FROM activity_reports WHERE event_id=?", (event_id,)).fetchone()
            if old:
                if old["session_id"] != row["id"] or not hmac.compare_digest(old["request_hash"], fp):
                    fail("Ese envío ya se usó con otro informe.", 409)
                return {"ok": True, "report_id": old["report_id"], "saved": True}
            self._report_matches(row, self.db.decrypt(row["snapshot_enc"]), report)
            previous = con.execute("SELECT * FROM activity_reports WHERE session_id=? AND report_id=?", (row["id"], report["report_id"])).fetchone()
            if previous:
                if self.db.decrypt(previous["payload_enc"]) != payload["packet"]:
                    fail("El contenido no coincide con el informe guardado.", 409)
                con.execute("INSERT INTO activity_report_receipts VALUES(?,?,?,?,?)", (event_id, row["id"], fp, report["report_id"], now))
                return {"ok": True, "report_id": report["report_id"], "saved": True}
            con.execute("INSERT INTO activity_reports VALUES(?,?,?,?,?,?,?)", (event_id, row["id"], report["report_id"], fp, self.db.encrypt(payload["packet"]), row["revision"], now))
            con.execute("UPDATE activity_sessions SET report_count=report_count+1,updated_at=? WHERE id=?", (now, row["id"]))
        return {"ok": True, "report_id": report["report_id"], "saved": True}

    def delete(self, session_id, token):
        self.purge()
        with self.db.connection(transaction=True) as con:
            row = self._session(con, session_id, token)
            con.execute("DELETE FROM activity_sessions WHERE id=?", (row["id"],))
            self.audit(con, None, "activity_session_deleted")
        return {"ok": True, "deleted": True}

    def filters(self, params):
        allowed = {"from", "to", "device", "route", "form", "limit", "cursor"}
        if not set(params) <= allowed:
            fail("Hay filtros no admitidos.")
        try:
            today = datetime.now(timezone.utc).date()
            start = date.fromisoformat(params.get("from", str(today - timedelta(days=self.retention_days))))
            end = date.fromisoformat(params.get("to", str(today)))
            limit = int(params.get("limit", "25"))
        except (ValueError, TypeError):
            fail("Revisa las fechas y el límite de resultados.")
        if start > end or start < date(1970, 1, 1) or end > date(2100, 12, 31) or (end - start).days > 365 or not 1 <= limit <= 100:
            fail("El rango de fechas o el límite no son válidos.")
        device, route, form = (params.get(k) or None for k in ("device", "route", "form"))
        if device is not None and device not in DEVICE_KINDS or route is not None and route not in journey.ROUTES or form is not None and form not in self.forms:
            fail("El filtro de dispositivo, recorrido o cuestionario no es válido.")
        begin = datetime.combine(start, daytime.min, timezone.utc).timestamp()
        until = datetime.combine(end + timedelta(days=1), daytime.min, timezone.utc).timestamp()
        clauses, values = ["s.created_at>=?", "s.created_at<?"], [begin, until]
        if device:
            clauses.append("v.device_kind=?"); values.append(device)
        if route:
            clauses.append("s.route_id=?"); values.append(route)
        if form:
            clauses.append("(s.form_id=? OR EXISTS(SELECT 1 FROM activity_session_forms sf WHERE sf.session_id=s.id AND sf.form_id=?))")
            values.extend([form, form])
        cursor = None
        if params.get("cursor"):
            encoded = params["cursor"]
            if len(encoded) > 500:
                fail("El cursor no es válido.")
            try:
                decoded = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
                exact(decoded, {"at", "id", "sig"})
                if type(decoded["at"]) not in (int, float) or not isinstance(decoded["sig"], str):
                    raise ValueError()
                ident(decoded["id"])
                signed = {"at": decoded["at"], "id": decoded["id"]}
                if not hmac.compare_digest(decoded["sig"], self.fingerprint(signed)):
                    raise ValueError()
                cursor = signed
            except (ValueError, TypeError, UnicodeError, json.JSONDecodeError):
                fail("El cursor no es válido.")
        return {"where": " AND ".join(clauses), "values": values, "begin": begin, "until": until,
                "device": device, "route": route, "form": form, "limit": limit, "cursor": cursor}

    def _metadata(self, row):
        return {"id": row["id"], "created_at": iso(row["created_at"]), "updated_at": iso(row["updated_at"]),
                "kind": row["kind"], "route_id": row["route_id"], "form_id": row["form_id"],
                "device": {"kind": row["device_kind"], "os": row["os"], "browser": row["browser"]},
                "completed": bool(row["completed"]), "answer_count": row["answer_count"],
                "report_available": row["report_count"] > 0, "revision": row["revision"]}

    def dashboard(self, params, actor):
        filt = self.filters(params)
        self.purge()
        base = "FROM activity_sessions s JOIN activity_visits v ON v.id=s.visit_id WHERE " + filt["where"]
        args = filt["values"]
        with self.db.connection(transaction=True) as con:
            metrics = con.execute("SELECT count(*) started,coalesce(sum(s.completed),0) completed,coalesce(sum(s.report_count),0) reports,coalesce(sum(s.answer_count),0) answers " + base, args).fetchone()
            # Route/form filters restrict visit counts to matching sessions; an
            # unfiltered view also counts people who only opened the home page.
            visit_where, visit_args = ["v.created_at>=?", "v.created_at<?"], [filt["begin"], filt["until"]]
            if filt["device"]:
                visit_where.append("v.device_kind=?"); visit_args.append(filt["device"])
            if filt["route"] or filt["form"]:
                visit_where.append("v.id IN (SELECT s.visit_id " + base + ")"); visit_args.extend(args)
            vbase = "FROM activity_visits v WHERE " + " AND ".join(visit_where)
            visits = con.execute("SELECT count(*) " + vbase, visit_args).fetchone()[0]
            devices = [{"name": r["device_kind"], "count": r["n"]} for r in con.execute("SELECT v.device_kind,count(*) n " + vbase + " GROUP BY v.device_kind ORDER BY n DESC,v.device_kind", visit_args)]
            days = defaultdict(lambda: {"visits": 0, "started": 0, "completed": 0})
            for r in con.execute("SELECT date(v.created_at,'unixepoch') day,count(*) n " + vbase + " GROUP BY day", visit_args):
                days[r["day"]]["visits"] = r["n"]
            for r in con.execute("SELECT date(s.created_at,'unixepoch') day,count(*) n,sum(s.completed) completed " + base + " GROUP BY day", args):
                days[r["day"]].update(started=r["n"], completed=r["completed"])
            test_sql = "SELECT sf.form_id,count(*) started,sum(sf.completed) completed FROM activity_session_forms sf WHERE sf.session_id IN (SELECT s.id " + base + ") GROUP BY sf.form_id ORDER BY started DESC,sf.form_id"
            tests = [{"id": r["form_id"], "name": self.forms.get(r["form_id"], {}).get("name", r["form_id"]), "started": r["started"], "completed": r["completed"]} for r in con.execute(test_sql, args)]
            page_base, page_args = base, list(args)
            if filt["cursor"]:
                page_base += " AND (s.created_at<? OR (s.created_at=? AND s.id<?))"
                page_args.extend([filt["cursor"]["at"], filt["cursor"]["at"], filt["cursor"]["id"]])
            rows = list(con.execute("SELECT s.id,s.created_at,s.updated_at,s.kind,s.route_id,s.form_id,s.completed,s.answer_count,s.report_count,s.revision,v.device_kind,v.os,v.browser " + page_base + " ORDER BY s.created_at DESC,s.id DESC LIMIT ?", page_args + [filt["limit"] + 1]))
            more, rows = len(rows) > filt["limit"], rows[:filt["limit"]]
            cursor = None
            if more and rows:
                last = {"at": rows[-1]["created_at"], "id": rows[-1]["id"]}
                last["sig"] = self.fingerprint(last)
                cursor = base64.urlsafe_b64encode(canonical(last)).decode().rstrip("=")
            self.audit(con, actor, "activity_dashboard_viewed")
        return {"summary": {"visits": visits, **dict(metrics)}, "by_day": [{"date": k, **days[k]} for k in sorted(days)],
                "devices": devices, "tests": tests, "sessions": [self._metadata(r) for r in rows], "next_cursor": cursor,
                "retention_days": self.retention_days, "counting_note": "Visitas y sesiones; no representa personas únicas."}

    def detail(self, session_id, actor):
        session_id = ident(session_id)
        self.purge()
        with self.db.connection(transaction=True) as con:
            row = con.execute("SELECT s.*,v.device_kind,v.os,v.browser FROM activity_sessions s JOIN activity_visits v ON v.id=s.visit_id WHERE s.id=?", (session_id,)).fetchone()
            if row is None:
                fail("No se encontró esa sesión.", 404)
            snapshot = self.db.decrypt(row["snapshot_enc"])
            events = [{**self.db.decrypt(r["payload_enc"]), "at": iso(r["created_at"])} for r in con.execute("SELECT payload_enc,created_at FROM activity_events WHERE session_id=? ORDER BY sequence", (session_id,))]
            reports = list(con.execute("SELECT * FROM activity_reports WHERE session_id=? ORDER BY created_at DESC,event_id DESC", (session_id,)))
            queries = list(con.execute("SELECT * FROM activity_queries WHERE session_id=? ORDER BY created_at DESC LIMIT 25", (session_id,)))
            packet = self.db.decrypt(reports[0]["payload_enc"]) if reports else None
            self.audit(con, actor, "activity_session_viewed", session_id)
        return {"session": self._metadata(row), "answers": snapshot, "event_history": events, "report": packet,
                "reports": [{"event_id": r["event_id"], "report_id": r["report_id"], "created_at": iso(r["created_at"]), "revision": r["revision"]} for r in reports],
                "queries": [{"request_id": r["request_id"], "status": r["status"], "error_code": r["error_code"], "attempt_count": r["attempt_count"], "revision": r["revision"], "created_at": iso(r["created_at"]), "updated_at": iso(r["updated_at"]), "payload": self.db.decrypt(r["payload_enc"]), "packet": self.db.decrypt(r["result_enc"]) if r["result_enc"] else None} for r in queries],
                "scores": [battery.score(self.forms[fid], payload["answers"]) for fid, payload in snapshot["forms"].items()]}


def attach_activity(app, db, authorize_admin):
    service = ActivityService(db)
    app.state.activity = service

    async def body(request):
        raw = await request.body()
        if len(raw) > MAX_BODY:
            fail("La solicitud es demasiado grande.", 413)
        try:
            value = json.loads(raw)
        except (ValueError, UnicodeError, RecursionError):
            fail("Se requiere JSON válido.")
        return value

    async def admin(request):
        user = authorize_admin(request)
        if inspect.isawaitable(user):
            user = await user
        if user is None or user["role"] != "admin":
            fail("Sólo administración puede consultar estos registros.", 403)
        return user["id"]

    @app.post("/api/activity/visits")
    async def create_visit(request: Request):
        service.rate("visits", request.client.host if request.client else "unknown")
        return service.visit(await body(request))

    @app.post("/api/activity/sessions")
    async def create_session(request: Request):
        service.rate("sessions", request.client.host if request.client else "unknown")
        return service.session(await body(request), request.headers.get("X-Traza-Visit"))

    @app.post("/api/activity/sessions/{session_id}/events")
    async def session_event(session_id: str, request: Request):
        service.rate("events", request.client.host if request.client else "unknown")
        return service.event(session_id, await body(request), request.headers.get("X-Traza-Visit"))

    @app.post("/api/activity/sessions/{session_id}/report")
    async def session_report(session_id: str, request: Request):
        service.rate("reports", request.client.host if request.client else "unknown")
        return service.report(session_id, await body(request), request.headers.get("X-Traza-Visit"), app.state.journey)

    @app.delete("/api/activity/sessions/{session_id}")
    async def delete_session(session_id: str, request: Request):
        service.rate("delete", request.client.host if request.client else "unknown")
        return service.delete(session_id, request.headers.get("X-Traza-Visit"))

    @app.get("/api/admin/activity")
    async def read_activity(request: Request):
        actor = await admin(request)
        return service.dashboard(dict(request.query_params), actor)

    @app.get("/api/admin/activity/{session_id}")
    async def read_activity_session(session_id: str, request: Request):
        actor = await admin(request)
        return service.detail(session_id, actor)

    return service
