"""Plain-language, bounded presentation shared by the web result and its PDF."""
import copy
import json
from pathlib import Path

FIELDS={f['key']:f for f in json.loads((Path(__file__).parent/'dtr-schema.json').read_text(encoding='utf-8'))['fields']}
DISCLAIMER=('Sistema en desarrollo. Las pruebas técnicas de esta versión verifican el funcionamiento esperado de sus reglas, cálculos, controles y exportación. '
             'La información se apoya en fuentes citadas y en supuestos explícitos del modelo; no cuenta con validación clínica independiente para predecir resultados individuales. '
             'No es un diagnóstico, una prescripción ni una garantía de recuperación. Revisa las opciones de atención con un profesional.')
NAMES={'cm':'Manejo de contingencias','cra':'Reforzamiento comunitario','tcc':'Terapia cognitivo-conductual / Matrix','em':'Entrevista motivacional','ejer':'Actividad física','pares':'Apoyo de pares','fam':'Acompañamiento familiar','trauma':'Atención del trauma','viv':'Apoyo para vivienda','voc':'Apoyo laboral y educativo','gestion':'Gestión de apoyos','bup':'Bupropión','ntx':'Naltrexona','mirt':'Mirtazapina','rtms':'Estimulación magnética','mod':'Modafinilo','nac':'N-acetilcisteína'}
MEDICAL={'bup','ntx','mirt','rtms','mod','nac'}
EXPLANATIONS={
 'cm':'Pregunta por un programa que reconozca avances concretos con incentivos acordados. Necesita objetivos claros y un equipo capacitado; no equivale a castigos o vigilancia familiar.',
 'cra':'Explora actividades, vínculos y oportunidades que hagan posible una vida con menos consumo. El trabajo se adapta a lo que para ti tiene sentido.',
 'tcc':'Pregunta cómo identificar desencadenantes, practicar respuestas al impulso y revisar lo ocurrido después de una dificultad. Se trabaja con acompañamiento profesional.',
 'em':'Si aún hay dudas sobre cambiar, una conversación sin presión puede ayudarte a ordenar tus razones y elegir un siguiente paso.',
 'ejer':'Considera una actividad que disfrutes y puedas sostener, adecuada a tu salud. Si tienes síntomas físicos o dudas, consulta antes de empezar; no es una cura por sí sola.',
 'pares':'Busca un espacio de apoyo respetuoso y voluntario. Puedes conocer sus acuerdos y decidir cuánto compartir antes de participar.',
 'fam':'Si lo deseas y es seguro, acuerda con alguien cercano qué ayuda sí te sirve y cuáles son tus límites. El apoyo no debe convertirse en control.',
 'trauma':'Si hay experiencias que siguen afectándote, conversa con alguien capacitado en trauma. No necesitas forzarte a narrarlas ni atribuir todo tu consumo a una sola causa.',
 'viv':'Revisa qué apoyo social o comunitario puede acercarte a un lugar seguro y estable. Las necesidades básicas también forman parte del proceso.',
 'voc':'Explora pasos realistas para recuperar estudio, trabajo o actividades con sentido, sin exigir resolverlo todo de inmediato.',
 'gestion':'Pide ayuda para organizar citas, transporte y acceso a servicios. Lleva tus límites de tiempo y dinero a esa conversación.'}

def show_value(value):
    if value is None:return 'No informado'
    if type(value) is bool:return 'Sí' if value else 'No'
    return str(value).replace('_',' ')

def present(report,data):
    r=copy.deepcopy(report)
    entered=data.get('entered_profile',data['perfil'])
    unknown=data.get('unknown',[])
    r['perfil']={'id':report['perfil']['id'],**entered}
    # Public results describe options to discuss; drug dosages stay out of the public report.
    selected=set()
    for package in r['regimen_recomendado']:
        for key in package['componentes']:
            selected.add(key)
            if key in MEDICAL:package['componentes'][key]='Opción para evaluación médica. No iniciar, suspender ni ajustar por este informe.'
    # Internal component-monitoring strings may contain drug doses. Only the bounded presentation is returned.
    ev=r['evaluacion_clinica']
    ev.pop('estado_componentes',None)
    ev.pop('notas',None)
    r.pop('frente_pareto',None)
    assumptions=[{'key':k,'label':FIELDS[k]['label'],'value':v} for k,v in data.get('assumptions',{}).items()]
    suggestions=[{'title':NAMES[k],'text':EXPLANATIONS[k]} for k in NAMES if k in selected and k in EXPLANATIONS]
    if selected & MEDICAL:
        suggestions.append({'title':'Opciones médicas para conversar','text':'El modelo incluyó '+', '.join(NAMES[k] for k in NAMES if k in selected & MEDICAL)+'. Su selección matemática no establece que sean apropiadas para ti. Requieren valoración, contraindicaciones y seguimiento profesional; no se proporcionan dosis.'})
    actions=[{'title':'Elige un paso posible','text':'Escoge una de las opciones y escribe qué necesitarías para intentarla esta semana. Un paso pequeño puede servir para observar qué te ayuda.'},
             {'title':'Prepara una conversación','text':'Lleva este informe a una persona profesional y cuenta qué te preocupa, qué ya intentaste y qué apoyo sí puedes sostener. Revisa disponibilidad y costos reales.'},
             {'title':'Vuelve a observar','text':'Si tu situación cambia, revisa de nuevo las necesidades y señales de riesgo. Una dificultad no invalida tu esfuerzo ni debe ocultarse por vergüenza.'}]
    if entered.get('violencia_pareja') is True:
        actions.insert(0,{'title':'Cuida tu seguridad','text':'El acompañamiento de pareja o familia no debe exponerte a violencia. Busca apoyo individual y un entorno seguro; ante peligro inmediato, llama al 911.'})
    return {'status':'completed','report':r,'suggestions':suggestions,'actions':actions,'assumptions':assumptions,
            'disclaimer':DISCLAIMER,'scope_note':('Hay datos no informados. Se usan supuestos de cálculo listados por separado y se excluyen medicación y neuromodulación. El resultado es exploratorio y puede cambiar al completar los datos.' if unknown else 'Datos declarados por la persona, sin verificación clínica. Las opciones médicas, si aparecen, requieren consulta y no incluyen instrucciones de dosis.'),
            'input_summary':[{'label':FIELDS[k]['label'],'value':show_value(v)} for k,v in entered.items() if k in FIELDS]}

def stopped(data,urgent,uncertain=False):
    return {'status':'needs_review' if uncertain else 'interlock','urgentes':urgent,'disclaimer':DISCLAIMER,
            'scope_note':'No se ejecutó la optimización ni se produjo un plan de tratamiento.',
            'actions':[{'title':'Busca apoyo ahora' if not uncertain else 'Aclara primero la situación de seguridad',
                        'text':'Si hay peligro inmediato, llama al 911. Si te cuesta mantenerte a salvo, busca a alguien de confianza y atención profesional. No esperes un resultado del simulador.'},
                       {'title':'Lleva la información disponible','text':'Explica lo que estás sintiendo y qué sustancias o medicamentos has utilizado. Este documento no descarta una urgencia.'}],
            'input_summary':[{'label':FIELDS[k]['label'],'value':show_value(data.get('entered_profile',{}).get(k))} for k in ('sintomas_cardiacos_agudos','psicosis_actual','riesgo_suicida')]}
