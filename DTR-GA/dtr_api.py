"""Anonymous, transient DTR-GA integration; no clinical records or user accounts."""
import asyncio
from collections import defaultdict,deque
import hashlib
import hmac
import copy
import json
import math
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile
import time
from fastapi import HTTPException,Request
from fastapi.responses import JSONResponse, Response

ROOT=Path(__file__).resolve().parent
SCHEMA=json.loads((ROOT/'dtr-schema.json').read_text(encoding='utf-8'))
FIELDS={f['key']:f for f in SCHEMA['fields']}
COMPONENTS={c['key']:c for c in SCHEMA['components']}
TTL=1800
COOKIE='nemy_dtr_session'
REQUIRED={'edad','dias_uso_ultimos_30','anios_uso','dias_abstinencia_actual','craving_eva','etapa_cambio','horas_disponibles_semana','presupuesto_semanal_mxn'}
SAFETY={'riesgo_suicida','psicosis_actual','sintomas_cardiacos_agudos'}
MEDICAL={'bup','ntx','mirt','mod','nac','rtms'}
DISCLAIMER=('Sistema en desarrollo. Las pruebas técnicas de esta versión verifican el funcionamiento esperado de sus reglas, cálculos, controles y exportación. '
             'La información se apoya en fuentes citadas y en supuestos explícitos del modelo; no cuenta con validación clínica independiente para predecir resultados individuales. '
             'No es un diagnóstico, una prescripción ni una garantía de recuperación. Revisa las opciones de atención con un profesional.')
def number(v,low,high,integer=False):
    return type(v) in (int,float) and math.isfinite(v) and low<=v<=high and (not integer or type(v) is int)

def validate(data):
    if not isinstance(data,dict) or not set(data)<= {'perfil','contexto','mode','consent','understood','scope_confirmed'}:
        raise HTTPException(422,'Campos no permitidos.')
    if data.get('consent') is not True or data.get('understood') is not True or data.get('scope_confirmed') is not True:
        raise HTTPException(422,'Confirma el consentimiento, el alcance para metanfetamina y los límites del sistema.')
    if data.get('mode','quick') not in ('quick','complete'): raise HTTPException(422,'Modo inválido.')
    p=data.get('perfil')
    if not isinstance(p,dict) or set(p)!=(set(FIELDS)-{'id'}):
        raise HTTPException(422,'El perfil debe incluir cada pregunta; usa null si desconoces una respuesta opcional.')
    stopping=any(p.get(k) is None for k in SAFETY) or p.get('psicosis_actual') is True or p.get('sintomas_cardiacos_agudos') is True or p.get('riesgo_suicida') in ('moderado','alto')
    for key,f in FIELDS.items():
        if key=='id':continue
        v=p[key];typ=f['type']
        if v is None and (key not in REQUIRED or stopping and key!='edad'):continue
        good= (type(v) is bool if typ=='boolean' else v in f['options'] if typ=='enum' and isinstance(v,str) else
               isinstance(v,str) and bool(re.fullmatch(r'[A-Za-z0-9_-]{3,40}',v)) if typ=='text' else
               number(v,*(f['range'] or [0,100000]),integer=typ=='integer') if typ in ('number','integer') else False)
        if not good: raise HTTPException(422,'Valor inválido en: '+f['label'])
    ctx=data.get('contexto',{})
    if not isinstance(ctx,dict) or not set(ctx)<= {'disponibles','niveles_no_disponibles','costos_mxn','max_farmacos','r2_perfil'}:
        raise HTTPException(422,'Contexto local inválido.')
    for name in ('disponibles','niveles_no_disponibles','costos_mxn'):
        values=ctx.get(name,{})
        if not isinstance(values,dict) or not set(values)<=set(COMPONENTS): raise HTTPException(422,'Componente desconocido en '+name)
        for key,value in values.items():
            size=len(COMPONENTS[key]['levels'])
            valid=type(value) is bool if name=='disponibles' else isinstance(value,list) and (
                all(type(n) is int and 0<=n<size for n in value) if name=='niveles_no_disponibles' else
                len(value)==size and all(number(n,0,1e7) for n in value) and value[0]==0)
            if not valid: raise HTTPException(422,'Valores inválidos en '+name+'.'+key)
    if 'max_farmacos' in ctx and not number(ctx['max_farmacos'],0,3,integer=True): raise HTTPException(422,'Máximo de fármacos: entero entre 0 y 3.')
    if 'r2_perfil' in ctx and not number(ctx['r2_perfil'],0,1): raise HTTPException(422,'R² debe estar entre 0 y 1.')
    entered=copy.deepcopy(p)
    unknown=[k for k,v in p.items() if v is None]
    assumed={k:FIELDS[k]['default'] for k in unknown}
    profile={k:(assumed[k] if k in assumed else v) for k,v in p.items()}
    profile['id']='NEMY-'+secrets.token_hex(4).upper()
    # Unknown history is never clearance for medication or neuromodulation.
    if unknown:
        ctx=copy.deepcopy(ctx)
        ctx.setdefault('disponibles',{}).update({k:False for k in MEDICAL})
        ctx['max_farmacos']=0
    return {'perfil':profile,'contexto':ctx,'mode':data.get('mode','quick'),
            'entered_profile':entered,'assumptions':assumed,'unknown':unknown}

def attach_dtr(app):
    jobs={};attempts=defaultdict(deque);process_tasks={};reaper=None
    app.state.dtr_jobs=jobs
    salt=secrets.token_bytes(32)
    def hashed(value):return hmac.new(salt,value.encode(),hashlib.sha256).hexdigest()
    def identity(request,create=False):
        token=request.cookies.get(COOKIE,'')
        if not re.fullmatch(r'[A-Za-z0-9_-]{43}',token):
            if not create:raise HTTPException(404,'La sesión temporal no está disponible. Inicia una nueva captura.')
            token=secrets.token_urlsafe(32)
        return token,hashed(token)
    def public_job(job):return {k:v for k,v in job.items() if k not in ('owner','started','finished','pdf')}
    def prune():
        now=time.monotonic()
        for key,job in list(jobs.items()):
            if job['status']!='running' and now-job['finished']>=TTL: jobs.pop(key,None)
        for uid,q in list(attempts.items()):
            while q and now-q[0]>900:q.popleft()
            if not q:attempts.pop(uid,None)
    async def expire():
        while jobs or process_tasks or attempts:
            await asyncio.sleep(30)
            prune()
    def owned(key,user):
        prune();job=jobs.get(key)
        if not job or job['owner']!=user:raise HTTPException(404,'La simulación no existe o ya venció.')
        return job
    async def execute(key,data):
        proc=None
        try:
            # Production uses tmpfs exclusively. Windows is for synthetic local QA.
            parent='/dev/shm' if sys.platform.startswith('linux') else None
            with tempfile.TemporaryDirectory(prefix='nemy-dtr-',dir=parent) as folder:
                if sys.platform.startswith('linux'):os.chmod(folder,0o700)
                env={k:v for k,v in os.environ.items() if k in ('PATH','SYSTEMROOT','WINDIR','LANG','LC_ALL','TMP','TEMP')}
                env.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',MKL_NUM_THREADS='1',PYTHONUTF8='1',MPLCONFIGDIR=folder)
                proc=await asyncio.create_subprocess_exec(sys.executable,str(ROOT/'dtr_worker.py'),folder,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL,cwd=str(ROOT),env=env)
                try:
                    out,_=await asyncio.wait_for(proc.communicate(json.dumps(data,allow_nan=False).encode()),timeout=600)
                    result=json.loads(out) if proc.returncode==0 else {'status':'failed','detail':'El motor no pudo completar esta simulación. Revisa el perfil antes de reintentar.'}
                    if key in jobs:jobs[key].update(result,finished=time.monotonic())
                except asyncio.TimeoutError:
                    if key in jobs:jobs[key].update(status='failed',detail='Se alcanzó el límite de 10 minutos. Puedes probar el modo rápido.',finished=time.monotonic())
                finally:
                    if proc.returncode is None:
                        proc.kill();await proc.wait()
        except asyncio.CancelledError:
            raise
        except Exception:
            if key in jobs:jobs[key].update(status='failed',detail='No fue posible iniciar la simulación. Vuelve a intentarlo más tarde.',finished=time.monotonic())
        finally:
            data.clear();process_tasks.pop(key,None)

    @app.get('/api/dtr/schema')
    async def schema(request:Request):
        return {**SCHEMA,'fields':[{k:v for k,v in f.items() if k!='default'} for f in SCHEMA['fields'] if f['key']!='id'],'assumption_values':{k:f['default'] for k,f in FIELDS.items() if k not in REQUIRED|SAFETY|{'id'}},'required':sorted(REQUIRED),'safety':sorted(SAFETY),'disclaimer':DISCLAIMER}

    @app.post('/api/dtr/jobs')
    async def start(request:Request):
        nonlocal reaper
        token,user=identity(request,create=True)
        try: data=validate(await request.json())
        except (ValueError,TypeError):raise HTTPException(422,'JSON inválido.')
        prune()
        if process_tasks:raise HTTPException(429,'Hay una simulación en curso. Inténtalo al finalizar; tu perfil no se ha guardado.')
        if len(jobs)>=20:raise HTTPException(429,'El espacio temporal está ocupado. Borra un resultado anterior o espera a que venza.')
        buckets=[attempts['session:'+user],attempts['network:'+hashed(request.client.host if request.client else 'unknown')]]
        if any(len(q)>=3 for q in buckets):raise HTTPException(429,'Máximo de tres simulaciones por 15 minutos desde esta conexión. Espera antes de reintentar.')
        for q in buckets:q.append(time.monotonic())
        key=secrets.token_urlsafe(24)
        jobs[key]={'owner':user,'status':'running','started':time.monotonic()}
        process_tasks[key]=asyncio.create_task(execute(key,data))
        if reaper is None or reaper.done():reaper=asyncio.create_task(expire())
        response=JSONResponse({'id':key,'status':'running','expires_after_completion_seconds':TTL},202)
        response.set_cookie(COOKIE,token,max_age=3600,httponly=True,secure=os.environ.get('TRAZA_SECURE_COOKIE','1')=='1',samesite='strict',path='/api/dtr')
        return response

    @app.get('/api/dtr/jobs/{key}')
    async def result(key:str,request:Request):
        _,user=identity(request);job=owned(key,user)
        return public_job(job)

    @app.get('/api/dtr/jobs/{key}/pdf')
    async def pdf(key:str,request:Request):
        _,user=identity(request);job=owned(key,user)
        if job['status'] not in ('completed','interlock','needs_review'):raise HTTPException(409,'El informe todavía no está listo.')
        if 'pdf' not in job:
            from dtr_pdf import render_pdf
            job['pdf']=await asyncio.to_thread(render_pdf,public_job(job))
        return Response(job['pdf'],media_type='application/pdf',headers={'Content-Disposition':'attachment; filename="Nemy-DTR-GA-orientacion.pdf"'})

    @app.delete('/api/dtr/jobs/{key}')
    async def delete(key:str,request:Request):
        _,user=identity(request);owned(key,user)
        task=process_tasks.get(key)
        if task:
            task.cancel()
            try:await task
            except asyncio.CancelledError:pass
        process_tasks.pop(key,None)
        jobs.pop(key,None)
        return {'ok':True}

    @app.on_event('shutdown')
    async def shutdown():
        tasks=list(process_tasks.values())
        if reaper:tasks.append(reaper)
        for task in tasks:task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
        jobs.clear();attempts.clear()
