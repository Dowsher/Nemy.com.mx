"""In-memory accessible text PDF for the anonymous DTR result. No persistence."""
from datetime import datetime,timezone
from io import BytesIO
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,Table,TableStyle,PageBreak,KeepTogether
from journey_pdf import _fonts
from dtr_presentation import NAMES,show_value

def render_pdf(data):
    regular,bold=_fonts()
    buffer=BytesIO();width=A4[0]-88
    doc=SimpleDocTemplate(buffer,pagesize=A4,rightMargin=44,leftMargin=44,topMargin=57,bottomMargin=53,
                          title='Nemy · DTR-GA · Orientación personal',author='Nemy',pageCompression=1)
    ink=colors.HexColor('#e6edf7');muted=colors.HexColor('#b4c0d2');accent=colors.HexColor('#b5ff62')
    styles={
        'title':ParagraphStyle('title',fontName=bold,fontSize=29,leading=34,textColor=ink,spaceAfter=15),
        'h2':ParagraphStyle('h2',fontName=bold,fontSize=15,leading=20,textColor=accent,spaceBefore=18,spaceAfter=9,keepWithNext=True),
        'h3':ParagraphStyle('h3',fontName=bold,fontSize=10,leading=15,textColor=ink,spaceBefore=8,spaceAfter=4,keepWithNext=True),
        'body':ParagraphStyle('body',fontName=regular,fontSize=9,leading=14,textColor=ink,spaceAfter=8),
        'small':ParagraphStyle('small',fontName=regular,fontSize=7.8,leading=11.5,textColor=muted,spaceAfter=6),
        'tag':ParagraphStyle('tag',fontName=bold,fontSize=9,leading=14,textColor=accent,spaceAfter=15)}
    def p(value,kind='body'):return Paragraph(escape(str(value)).replace('\n','<br/>'),styles[kind])
    def page(canvas,doc):
        canvas.saveState();canvas.setFillColor(colors.HexColor('#080e18'));canvas.rect(0,0,*A4,fill=1,stroke=0)
        canvas.setStrokeColor(accent);canvas.setLineWidth(2);canvas.line(44,A4[1]-35,A4[0]-44,A4[1]-35)
        canvas.setFont(bold,8);canvas.setFillColor(accent);canvas.drawString(44,30,'NEMY / DTR-GA')
        canvas.setFont(regular,7);canvas.setFillColor(muted);canvas.drawRightString(A4[0]-44,30,f'En desarrollo · Orientación, no prescripción · {doc.page}')
        canvas.restoreState()
    story=[p('NEMY / DTR-GA · INFORME ANÓNIMO','tag'),p('Un siguiente paso.\nCon más contexto.','title'),
           p('Generado el '+datetime.now(timezone.utc).strftime('%d/%m/%Y, %H:%M UTC')+' · Nemy 9.0.0 / motor DTR-GA v2','small'),
           p(data['scope_note']),p('Estado y alcance del sistema','h2'),p(data['disclaimer'],'small')]
    if data['status']!='completed':
        story += [p('La simulación se detuvo','h2')]+[p(t) for t in data['urgentes']]
    else:
        r=data['report'];ev=r['evaluacion_clinica']
        story+=[p('Lo primero que conviene revisar','h2')]
        alerts=ev.get('prioritarias',[])+r.get('avisos_dominio',[])
        story += [p('• '+t) for t in alerts] if alerts else [p('El motor no generó alertas prioritarias con los datos utilizados. Esto no confirma ausencia de riesgo.')]
        story += [p('Indicaciones para usar este resultado','h2')]
    for a in data['actions']:story+=[p(a['title'],'h3'),p(a['text'])]
    if data['status']=='completed':
        story += [PageBreak(),p('Opciones para conversar','title'),p('Estas sugerencias explican los componentes elegidos por el modelo. No establecen por sí solas un tratamiento individual.')]
        for s in data['suggestions']:story += [p(s['title'],'h3'),p(s['text'])]
        story += [p('Temas de atención que señala el motor','h2')]+[p('• '+t) for t in ev.get('obligatorios',[])]
        story += [PageBreak(),p('Escenario adaptativo','title'),p('El programa modela 24 semanas y 12 de seguimiento. Sus ramas dependen de la respuesta a etapas anteriores; no deben sumarse como si todas se realizaran. Confirma cualquier decisión con el equipo que te atienda.')]
        for pack in r['regimen_recomendado']:
            desc='; '.join(NAMES.get(k,k)+': '+v for k,v in pack['componentes'].items()) or 'Seguimiento sin componentes seleccionados.'
            story += [p(pack['paquete'],'h3'),p(desc),p(f"Costo semanal modelado: ${pack['costo_semana_mxn']:,.0f} MXN · Tiempo: {pack['horas_semana']:.1f} h. Los costos son ilustrativos, no cotizaciones.",'small')]
        story += [p('Resultados matemáticos e incertidumbre','h2'),p('Los porcentajes describen el modelo. No son tu probabilidad comprobada de recuperación. P10–P90 muestra variación simulada, no un intervalo clínico de confianza.','small')]
        for key,label in [('sost','Abstinencia sostenida: semanas 33–36'),('pct','Semanas sin consumo: semanas 1–36'),('ret24','Retención a la semana 24')]:
            m=r['resultados_recomendado'].get(key)
            if m:story.append(p(f"{label}: {m['media']*100:.1f}% · P10–P90: {m['p10']*100:.1f}%–{m['p90']*100:.1f}%",'small'))
        story += [PageBreak(),p('Tus datos y los supuestos','title'),p('Las respuestas no informadas permanecen identificadas. Para calcular, el motor utiliza los supuestos listados después; no son datos confirmados sobre ti.')]
        rows=[]
        for item in data['input_summary']:rows.append([p(item['label'],'small'),p(item['value'],'small')])
        table=Table([[p('Pregunta','h3'),p('Respuesta declarada','h3')]]+rows,colWidths=[width*.72,width*.28],repeatRows=1,hAlign='LEFT')
        table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('LINEBELOW',(0,0),(-1,0),.7,accent),('LINEBELOW',(0,1),(-1,-1),.25,colors.HexColor('#243145')),('TOPPADDING',(0,0),(-1,-1),5),('BOTTOMPADDING',(0,0),(-1,-1),5)]))
        story.append(table)
        story += [p('Supuestos utilizados para calcular','h2')]
        story += [p(a['label']+': '+show_value(a['value'])+' (supuesto; no informado).','small') for a in data.get('assumptions',[])] or [p('No hubo respuestas desconocidas. Todos los datos siguen siendo autodeclarados.','small')]
        story += [p('Fuentes y límites','h2'),p('ASAM/AAAP, Clinical Practice Guideline on the Management of Stimulant Use Disorder (2024): https://www.asam.org/quality-care/clinical-guidelines/stimulant-use-disorders','small'),p('Trivedi et al., ADAPT-2, NEJM (2021): doi:10.1056/NEJMoa2020214. La evidencia de una intervención no valida por sí sola esta combinación ni sus predicciones individuales.','small')]
    story += [p('Ayuda y privacidad','h2'),p('Peligro inmediato: 911. En México, Línea de la Vida: 800 911 2000. Más recursos: nemy.com.mx/ayuda.','small'),p('Sin cuenta ni nombre. El resultado temporal vence a los 30 minutos y puede borrarse antes en Nemy. Esta copia permanece en tu dispositivo; compártela sólo si tú lo decides.','small')]
    doc.build(story,onFirstPage=page,onLaterPages=page)
    return buffer.getvalue()
