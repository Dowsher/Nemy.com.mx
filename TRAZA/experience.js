(() => {
  'use strict';
  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function support(id,form) { const item=window.TRAZA_SUPPORT?.lookup(id,form); if(!item) return ''; return `<div class="question-story"><figure class="question-scene">${window.TRAZA_SCENES?.render(item.scene,`scene-${form}-${id}`)||''}</figure><div class="question-explainer"><span class="explain-tag">En palabras sencillas</span><p>${esc(item.context)}</p></div></div>`; }
  function sliderSpec(question) {
    if(question.type==='number' || question.multiple) return null;
    const options=(question.options||[]).filter(option=>typeof option.value==='number' && option.value!==98 && option.value!==99);
    if(options.length<11 || options.length>11) return null;
    const values=options.map(option=>option.value).sort((a,b)=>a-b);
    const step=values[1]-values[0];
    if(![1,10].includes(step) || values[0]!==0 || !values.every((value,index)=>value===index*step)) return null;
    return {options,min:0,max:values[10],step};
  }
  function slider(question,value,name='visual-slider') {
    const spec=sliderSpec(question); if(!spec)return '';
    const selected=spec.options.find(option=>option.value===value);
    return `<div class="answer-slider${selected?' touched':''}"><label class="slider-label" for="answer-slider">${spec.max===100?'Frecuencia aproximada':'Elige un valor'}<output id="slider-output" for="answer-slider">${selected?esc(selected.label):'Sin responder'}</output></label><input id="answer-slider" name="${esc(name)}" data-question="${esc(question.id)}" type="range" min="${spec.min}" max="${spec.max}" step="${spec.step}" value="${selected?value:spec.max/2}" aria-valuetext="${selected?esc(selected.label):'Sin responder. Mueve la barra para elegir.'}" aria-describedby="slider-instruction"><div class="slider-ends"><span>${esc(spec.options[0].label)}</span><span>${esc(spec.options[spec.options.length-1].label)}</span></div><p id="slider-instruction" class="slider-instruction">${selected?'Puedes ajustar la barra o pasar la pregunta.':'Mueve la barra o usa las flechas del teclado. Aún no has elegido una respuesta.'}</p></div>`;
  }
  const ranges={PHQ9:[0,27],GAD7:[0,21],PCPTSD5:[0,5],ASRS6:[0,6],DEST:[0,100],MSPSS:[1,4],BRSCL:[1,5],ECR12CL:[1,7],CCQBRIEF:[1,4]};
  function meter(label,value,range,unit='puntos') {
    if(!Number.isFinite(value)||!Array.isArray(range)||range.length!==2||range[1]<=range[0])return '';
    return `<div class="result-meter"><div class="result-meter-label"><span>${esc(label)}</span><strong>${esc(value)} <small>${esc(unit)}</small></strong></div><meter min="${range[0]}" max="${range[1]}" value="${Math.min(range[1],Math.max(range[0],value))}" aria-label="${esc(label)}: ${value} ${esc(unit)}, rango ${range[0]} a ${range[1]}"></meter><div class="result-meter-ends"><span>${range[0]}</span><span>${range[1]} ${esc(unit)}</span></div></div>`;
  }
  function score(score) {
    const id=score.instrument_id||score.id;
    let graphic='';
    if(!score.complete)return `<article class="visual-score incomplete"><h3>${esc(score.name||id)}</h3><p>Faltan respuestas. No se representa como cero.</p><span class="coverage-visual">${esc(score.answered)} de ${esc(score.required)} respuestas requeridas</span></article>`;
    if(id==='PCPTSD5' && Array.isArray(score.answers) && score.answers.some(answer=>answer.id==='PCPTSD5_EXPOSURE' && answer.value===0))return `<article class="visual-score"><h3>${esc(score.name)}</h3><div class="filter-result"><span aria-hidden="true">✓</span><p>El filtro inicial cerró este cuestionario. No se interpretan las cinco reacciones como respuestas negativas.</p></div></article>`;
    const range=Array.isArray(score.range)?score.range:ranges[id];
    if(id!=='ITQ' && Number.isFinite(score.total) && range)graphic+=meter(id==='DEST'?'Frecuencia media de experiencias':score.name,score.total,range,id==='DEST'?'%':'puntos');
    for(const part of score.subscales||[]) { const val=part.value??part.total??part.score; let lo=id==='ITQ'?0: id==='SOCRATES'||id==='SOCRATES8A'?Math.round(part.max/5): ranges[id]?.[0]??0; graphic+=meter(part.name||part.id,val,[lo,part.max],id==='ITQ'?'puntos':'puntos'); }
    const note=id==='DEST'?'Este porcentaje expresa frecuencia, no gravedad ni porcentaje de trauma.':id==='ASRS6'?'Cuenta respuestas en las zonas señaladas por el formato original; no mide trauma.':id==='ITQ'?'Cada dimensión se interpreta junto con su impacto. No hay un total diagnóstico.':id==='MSPSS'?'Un valor mayor representa más apoyo social percibido.':id==='BRSCL'?'Un valor mayor representa mayor capacidad percibida para recuperarte del estrés.':id==='LEC5'?'Un registro de acontecimientos no mide su gravedad.':'';
    if(!graphic)return `<article class="visual-score descriptive"><h3>${esc(score.name||id)}</h3><p>Registro descriptivo · ${esc(score.answered)} respuestas registradas.</p>${note?`<p class="meter-note">${esc(note)}</p>`:''}</article>`;
    return `<article class="visual-score"><h3>${esc(score.name||id)}</h3>${graphic}${score.band?`<p class="meter-band">${esc(typeof score.band==='string'?score.band:score.band.label||'')}</p>`:''}${note?`<p class="meter-note">${esc(note)}</p>`:''}</article>`;
  }
  function results(scores) { return `<section class="visual-results"><div class="visual-results-heading"><span class="explain-tag">Tus resultados de un vistazo</span><h2>Cada pieza cuenta algo distinto.</h2><p>Lee cada escala por separado. Las barras muestran su propio rango; no equivalen a un porcentaje de trauma.</p></div><div class="visual-score-grid">${scores.map(score).join('')}</div></section>`; }
  // A tap at the unchanged thumb is an explicit choice, even when the native range emits no input.
  document.addEventListener('pointerup', event => {
    const target=event.target;
    if(!target || target.tagName!=='INPUT' || target.type!=='range')return;
    const container=target.closest('.answer-slider');
    if(container && !container.classList.contains('touched')) target.dispatchEvent(new Event('input',{bubbles:true}));
  });
  window.TRAZA_UX={support,sliderSpec,slider,results};
})();
