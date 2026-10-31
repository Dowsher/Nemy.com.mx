"""Isolated CPU worker. Input through stdin; output through stdout; no network."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'dtr_ga'))
def run(data,directory):
    from dtr_presentation import present,stopped
    unknown=set(data.get('unknown',[]))
    if unknown & {'riesgo_suicida','psicosis_actual','sintomas_cardiacos_agudos'}:
        return stopped(data,['Falta aclarar alguna respuesta de seguridad. No podemos tratar una respuesta desconocida como ausencia de riesgo.'],uncertain=True)
    if data['perfil']['riesgo_suicida']=='moderado':
        return stopped(data,['No tienes certeza de poder mantenerte a salvo. Busca apoyo inmediato antes de continuar.'])
    from dtr_base import validar_perfil, fusionar_contexto
    from dtr_clinico import evaluar_clinico
    p,_=validar_perfil(data['perfil']);ctx=fusionar_contexto(data.get('contexto',{}))
    ev=evaluar_clinico(p,ctx,investigacion=False)
    if ev.urgentes:
        return stopped(data,ev.urgentes)
    from dtr_main import correr
    import dtr_reporte
    # The web client plots the returned weekly curve. No matplotlib caches or images.
    dtr_reporte.grafica=lambda *args: None
    directory=Path(directory)
    input_path=directory/'input.json'
    input_path.write_text(json.dumps({'perfil':dict(p),'contexto':ctx},allow_nan=False),encoding='utf-8')
    with contextlib.redirect_stdout(io.StringIO()):
        code,files=correr(str(input_path),salida=str(directory),rapido=data.get('mode')!='complete',semilla=11,investigacion=False,ignorar_interlock=False,verbose=False)
    if code: raise RuntimeError('Simulation failed')
    report=json.loads(Path(files['json']).read_text(encoding='utf-8'))
    return present(report,data)

if __name__=='__main__':
    if hasattr(os,'nice'): os.nice(10)
    if sys.platform.startswith('linux'):
        import resource
        resource.setrlimit(resource.RLIMIT_CPU,(600,605))
        resource.setrlimit(resource.RLIMIT_AS,(1500000000,1500000000))
        resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    try:
        result=run(json.load(sys.stdin),sys.argv[1])
        sys.stdout.write(json.dumps(result,ensure_ascii=False,allow_nan=False))
    except Exception:
        sys.stdout.write(json.dumps({'status':'failed','detail':'No se pudo completar la simulación. Revisa el perfil y vuelve a intentarlo.'}))
        sys.exit(1)
