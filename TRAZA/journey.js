(() => {
  'use strict';
  const VERSION = '4.0';
  // Display names from the canonical battery; no question content is loaded here.
  const FORM_NAMES = Object.freeze({PHQ9: 'PHQ-9', GAD7: 'GAD-7', PCPTSD5: 'PC-PTSD-5', ITQ: 'ITQ', APOYOS: 'Apoyos y disposición al cambio', ASRS6: 'ASRS v1.1 6-item', LEC5: 'LEC-5 extendida', DEST: 'DES-T · 8 ítems', SOCRATES8A: 'SOCRATES 8A · Alcohol', SOCRATES: 'SOCRATES 8D · Otras drogas', MSPSS: 'MSPSS · Apoyo que percibes', CCQBRIEF: 'CCQ-Brief · Chile 2026', ECR12CL: 'ECR-12 · Relaciones de pareja', BRSCL: 'BRS · Recuperarte del estrés'});
  const main = document.getElementById('main');
  const modalRoot = document.getElementById('modal-root');
  const ux = window.TRAZA_UX;
  let saveTimer = null, pendingText = null;
  const activity = new window.TRAZA_ACTIVITY.Activity('home', activityStatus);
  function activityStatus(status, message = '') { state.saveStatus = status; if (message) state.saveError = message; else if (status === 'saved') state.saveError = ''; const node = document.getElementById('save-status'); if(node) { node.textContent = status === 'saving' ? 'Guardando…' : status === 'error' ? 'Sin guardar · vuelve a intentar' : 'Respuestas guardadas'; node.dataset.status = status; } }
  function registerAnswer(question, value, block = state.block) { activity.event('answer', block, question.id, value == null ? null : value).catch(error => { state.saveError = error.message; activityStatus('error'); }); }
  function flushText() { clearTimeout(saveTimer); if(pendingText) { const item = pendingText; pendingText = null; registerAnswer(item.question,item.value,item.block); } }
  async function saved() { flushText(); try { await activity.flush(); state.saveError = ''; return true; } catch(error) { state.error = error.message; state.saveError = error.message; render(false); return false; } }
  async function tracked(type, form = null, question = null, value = null) { if(!(await saved())) return false; try { await activity.event(type,form,question,value); return true; } catch(error) { state.error = error.message; render(false); return false; } }
  async function startStorage() { try { await activity.start({kind:'journey',route_id:state.route.id,form_id:null}); state.consented = true; state.view = 'intro'; render(); } catch(error) { state.error = error.message; render(false); } }
  const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const list = value => Array.isArray(value) ? value : value == null ? [] : [value];
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
  const paragraphs = value => list(value).filter(Boolean).map(text => `<p>${esc(text)}</p>`).join('');
  const primary = (label, action, disabled = false, extra = '') => `<button class="primary" data-action="${action}"${disabled ? ' disabled' : ''}${extra}>${esc(label)} <span class="arrow" aria-hidden="true">→</span></button>`;
  const state = {schema: null, schemaError: '', route: null, forms: {}, answers: {}, context: {}, omitted: {}, seen: {}, block: 'context', index: 0, view: 'home', resumeView: 'intro', pauseView: 'question', saveStatus: '', saveError: '', storage: false, adult: false, processing: false, consented: false, ai: false, busy: '', error: '', packet: null, requestId: null, editReview: false, phqChecked: false, dangerNext: 'next', pendingRoute: null, exiting: false};
  const missing = (value, question) => value == null || (question?.type === 'text' && typeof value === 'string' && !value.trim()) || (question?.type === 'number' ? value === 'skipped' || value === 'unknown' : value === 98 || value === 99) || (Array.isArray(value) && (!value.length || value.some(part => part === 98 || part === 99)));
  const multiple = question => !!question.multiple || ['multi', 'multi_select'].includes(question.type);
  const textLimit = question => Math.min(500, Math.max(1, Number.isInteger(question.max_length) ? question.max_length : 500));
  const missingOptions = question => question.type === 'number' ? [{value: 'skipped', label: 'Pasar esta pregunta'}, {value: 'unknown', label: 'No sé'}] : [{value: 98, label: 'Pasar esta pregunta'}, {value: 99, label: 'No sé'}];
  function matches(rule, answers) {
    if (!rule) return true;
    if (Array.isArray(rule)) return rule.every(part => matches(part, answers));
    if (rule.all) return list(rule.all).every(part => matches(part, answers));
    if (rule.any) return list(rule.any).some(part => matches(part, answers));
    const value = answers[rule.id];
    if (missing(value)) return false;
    if (own(rule, 'equals')) return value === rule.equals;
    if (own(rule, 'includes')) return Array.isArray(value) && value.some(part => part === rule.includes);
    if (own(rule, 'not_in')) return value != null && !list(rule.not_in).some(part => part === value);
    return false;
  }
  const contextItems = () => list(state.schema?.context_questions).filter(question => !question.route_ids || question.route_ids.includes(state.route?.id));
  const formItems = id => [...list(state.forms[id]?.items), ...list(state.forms[id]?.additional_items)];
  const blockItems = block => block === 'context' ? contextItems() : formItems(block);
  const blockAnswers = block => block === 'context' ? state.context : state.answers[block] || {};
  const visibleItems = (block = state.block) => blockItems(block).filter(question => matches(question.show_if, blockAnswers(block)));
  const optionsFor = (question, block = state.block) => list(question.options).filter(option => !question.filter_options_by_answers?.[option.value] || matches(question.filter_options_by_answers[option.value], blockAnswers(block)));
  const allOptions = question => { const options = optionsFor(question); return [...options, ...missingOptions(question).filter(option => !options.some(existing => existing.value === option.value))]; };
  const expectedFormIds = () => state.route ? [...new Set([...list(state.route.form_ids), ...list(state.route.conditional_forms).filter(form => matches(form.show_if, state.context)).map(form => form.id)])] : [];
  const blockOrder = () => ['context', ...expectedFormIds()];
  const blockName = block => block === 'context' ? 'Tu contexto' : state.forms[block]?.name || FORM_NAMES[block] || 'Cuestionario';
  const hasAnswer = (question, block = state.block) => own(blockAnswers(block), question.id) && (question.type !== 'text' || typeof blockAnswers(block)[question.id] !== 'string' || !!blockAnswers(block)[question.id].trim()) && (!Array.isArray(blockAnswers(block)[question.id]) || blockAnswers(block)[question.id].length > 0);
  const hasProgress = () => Object.keys(state.context).length > 0 || Object.values(state.answers).some(answers => Object.keys(answers).length > 0) || Object.keys(state.omitted).length > 0;
  const blocked = () => state.context.S1 === 0 || state.context.S3 === 1;
  const highDistress = () => typeof state.context.S2 === 'number' && state.context.S2 >= 8 && state.context.S2 <= 10;
  const shouldCheckDanger = () => !state.phqChecked && typeof state.answers.PHQ9?.PHQ9_9 === 'number' && state.answers.PHQ9.PHQ9_9 >= 1 && state.answers.PHQ9.PHQ9_9 <= 3;
  const errorBox = () => (state.error || state.saveError) ? `<div class="error-box" role="alert">${esc(state.error || state.saveError)}${state.saveError ? '<button class="text-button" data-action="retry-save">Reintentar guardado</button>' : ''}</div>` : '';
  function invalidate() { state.packet = null; state.requestId = null; state.ai = false; state.error = ''; }
  function pruneBlock(block) {
    const answers = blockAnswers(block);
    let changed = true;
    while (changed) {
      changed = false;
      for (const question of blockItems(block)) {
        if (!own(answers, question.id)) continue;
        if (!matches(question.show_if, answers)) { delete answers[question.id]; changed = true; continue; }
        if (!question.filter_options_by_answers || missing(answers[question.id], question)) continue;
        const options = optionsFor(question, block);
        if (Array.isArray(answers[question.id])) {
          const values = answers[question.id].filter(value => options.some(option => option.value === value));
          if (values.length !== answers[question.id].length) { if (values.length) answers[question.id] = values; else delete answers[question.id]; changed = true; }
        } else if (!options.some(option => option.value === answers[question.id])) { delete answers[question.id]; changed = true; }
      }
    }
  }
  function prune() {
    const allowedContext = new Set(contextItems().map(question => question.id));
    for (const id of Object.keys(state.context)) if (!allowedContext.has(id)) delete state.context[id];
    pruneBlock('context');
    const active = new Set(expectedFormIds());
    for (const id of Object.keys(state.answers)) if (!active.has(id) || state.omitted[id]) delete state.answers[id]; else pruneBlock(id);
    for (const id of Object.keys(state.omitted)) if (!active.has(id)) delete state.omitted[id];
  }
  function saveAnswer(question, value, block = state.block) {
    const answers = block === 'context' ? state.context : (state.answers[block] ||= {});
    if ((Array.isArray(value) && !value.length) || (question.type === 'text' && typeof value === 'string' && !value.trim())) delete answers[question.id];
    else answers[question.id] = value;
    if (question.id === 'PHQ9_9') state.phqChecked = false;
    prune(); invalidate();
    const cleanValue = own(answers,question.id) ? answers[question.id] : null;
    if(question.type === 'text' || question.type === 'number') { pendingText = {question,value:cleanValue,block}; clearTimeout(saveTimer); saveTimer = setTimeout(flushText,700); } else registerAnswer(question,cleanValue,block);
  }
  function answerLabel(question, block) {
    const value = blockAnswers(block)[question.id];
    if (value == null || (Array.isArray(value) && !value.length) || (question.type === 'text' && typeof value === 'string' && !value.trim())) return 'Sin responder';
    if (question.type === 'text' && typeof value === 'string') return value;
    if (question.type === 'number') return value === 'skipped' ? 'Pasada' : value === 'unknown' ? 'No sé' : `${value}${question.unit ? ` ${question.unit}` : ''}`;
    if (value === 98) return 'Pasada';
    if (value === 99) return 'No sé';
    return list(value).map(part => list(question.options).find(option => option.value === part)?.label ?? String(part)).join(' · ');
  }
  function safeLink(url, label) { try { const parsed = new URL(url, location.origin); return ['https:', 'http:'].includes(parsed.protocol) ? `<a href="${esc(parsed.href)}" target="_blank" rel="noopener noreferrer">${esc(label)} ↗</a>` : ''; } catch (_) { return ''; } }
  function progressView() {
    const order = blockOrder();
    const included = order.filter(block => block === 'context' || !state.omitted[block]);
    const total = included.reduce((sum, block) => sum + visibleItems(block).length, 0);
    const done = included.reduce((sum, block) => sum + visibleItems(block).filter(question => hasAnswer(question, block)).length, 0);
    return `<div class="journey-progress"><div class="journey-progress-row"><span>${esc(state.route?.name)} · Bloque ${Math.max(1, order.indexOf(state.block) + 1)} de ${order.length}</span><span>${done} de ${total} preguntas visibles</span></div><progress value="${done}" max="${Math.max(1, total)}" aria-label="Avance del recorrido"></progress></div>`;
  }
  function render(focus = true) {
    const views = {home: homeView, routes: routesView, consent: consentView, intro: introView, question: questionView, review: reviewView, result: resultView, loading: loadingView, paused: pausedView, safety: safetyView, danger: dangerView};
    main.innerHTML = (views[state.view] || homeView)();
    document.body.classList.toggle('has-question-dock', ['question','danger'].includes(state.view));
    if (state.view !== 'home' && !main.querySelector('.back-button')) main.insertAdjacentHTML('afterbegin', '<div class="universal-back"><button class="back-button" data-action="go-back">← Regresar</button></div>');
    document.getElementById('pause-button').hidden = !['intro', 'question', 'review', 'result'].includes(state.view);
    document.querySelectorAll('.journey-nav [data-action]').forEach(button => { if (button.dataset.action === state.view) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current'); });
    document.title = state.view === 'result' ? 'Tu informe · TRAZA' : state.route && !['home', 'routes'].includes(state.view) ? `${state.route.name} · TRAZA` : 'TRAZA · Proyecto Nemy';
    if (focus) { main.focus({preventScroll: true}); window.scrollTo({top: 0, behavior: 'instant'}); }
  }
  function homeView() {
    return `<section class="view journey-home"><span class="journey-project">Del proyecto Nemy · Para mayores de 18 años</span><h1>Entiende lo que te pasa.<br><em>Conecta las piezas.</em></h1><p class="lead">TRAZA es una herramienta del proyecto Nemy para ayudarte a identificar posibles traumas emocionales y entender cómo pueden relacionarse con lo que sientes hoy.</p><p class="journey-example">Una experiencia difícil puede dejar huellas: estar siempre en alerta, dormir mal o que te cueste confiar. Aquí puedes explorar si hay relación, sin tener que contar tu historia.</p><div class="home-visual">${window.TRAZA_SCENES?.render('path','welcome-scene') || ''}</div><div class="home-route-shortcuts" aria-label="Elige tu recorrido">${list(state.schema?.routes).map(route => `<button class="home-route-button" data-action="choose-route" data-route="${esc(route.id)}"><span>${esc(route.name)}</span><small>${esc(route.minutes?.join('–') || '')} minutos · Empezar →</small></button>`).join('')}</div><div class="home-action">${primary(state.route && hasProgress() ? 'Continuar mi recorrido' : 'Elegir mi recorrido', state.route && hasProgress() ? 'resume-route' : 'routes')}<span class="time-note">Tú eliges la profundidad.<strong>Tres recorridos, desde 10–15 min.</strong></span></div><div class="journey-how"><article><b>1</b><h2>Elige</h2><p>Un recorrido breve, intermedio o de mayor cobertura.</p></article><article><b>2</b><h2>Responde</h2><p>Una pregunta por pantalla. Puedes corregir, pausar u omitir.</p></article><article><b>3</b><h2>Conecta</h2><p>Recibe una síntesis con IA, un perfil orientativo y un PDF para conversar en consulta.</p></article></div><p class="journey-boundary">TRAZA ofrece orientación y no sustituye la evaluación ni el acompañamiento de un profesional de salud mental.</p><p class="journey-library-link">¿Prefieres un tema puntual? ${safeLink('/instrumentos', 'Abrir los cuestionarios individuales')}</p>${state.schemaError ? `<div class="error-box" role="alert">${esc(state.schemaError)} <button class="text-button" data-action="retry-schema">Volver a cargar</button></div>` : ''}</section>`;
  }
  function routesView() {
    if (!state.schema) return `<section class="view journey-narrow"><p class="eyebrow">Recorridos</p><h1>Elige cuánto quieres explorar.</h1>${state.schemaError ? `<div class="error-box" role="alert">${esc(state.schemaError)}</div>${primary('Volver a cargar', 'retry-schema')}` : '<p role="status">Cargando los recorridos…</p>'}</section>`;
    return `<section class="view"><p class="eyebrow">Una ruta para empezar</p><h1>Elige cuánto quieres explorar.</h1><p class="lead">Todos terminan con un informe integrado. Puedes dejar preguntas o cuestionarios sin responder.</p><div class="journey-route-grid">${state.schema.routes.map(route => `<article class="journey-route"><span class="small-tag ${route.id === 'basico' ? 'lilac' : ''}">${esc(Array.isArray(route.minutes) ? route.minutes.join('–') : route.minutes || '')} min aprox.</span><h2>${esc(route.name)}</h2><p>${esc(route.description)}</p><details><summary>Qué incluye</summary><p>${list(route.form_ids).length} cuestionarios y preguntas de contexto.</p><ul>${list(route.form_ids).map(id => `<li>${esc(state.forms[id]?.name || FORM_NAMES[id] || 'Cuestionario')}</li>`).join('')}</ul>${list(route.conditional_forms).length ? '<p>Los cuestionarios de consumo se añaden solo si eliges explorarlo.</p>' : ''}</details>${primary(state.route?.id === route.id && hasProgress() ? 'Continuar' : 'Elegir este recorrido', 'choose-route', false, ` data-route="${esc(route.id)}"`)}</article>`).join('')}</div><p class="journey-route-note">«Completo» significa mayor cobertura de temas. No equivale a un diagnóstico ni incluye todos los instrumentos de la biblioteca. El tiempo y el número de preguntas pueden variar según tus respuestas.</p>${errorBox()}</section>`;
  }
  function consentView() {
    return `<section class="view journey-narrow"><div class="screen-top"><button class="back-button" data-action="routes">← Recorridos</button><span class="step-label">${esc(state.route.name)}</span></div><div class="panel"><p class="eyebrow">Antes de empezar</p><h1>Tu recorrido, paso a paso.</h1><p class="lead">Avanza a tu ritmo. Tus respuestas se guardarán cifradas en TRAZA para revisar el uso de la herramienta y consultar las sesiones desde su panel privado. Al terminar decidirás si generas el informe con IA.</p><div class="check-list"><label class="consent-check"><input id="adult-consent" type="checkbox"${state.adult ? ' checked' : ''}><span>Tengo <strong>18 años o más</strong>.</span></label><label class="consent-check"><input id="processing-consent" type="checkbox"${state.processing ? ' checked' : ''}><span>Acepto que TRAZA procese mis respuestas para preparar el informe que solicite.</span></label><label class="consent-check"><input id="storage-consent" type="checkbox"${state.storage ? ' checked' : ''}><span>Acepto guardar mis respuestas e informes cifrados hasta 90 días y que el equipo autorizado de TRAZA los consulte en el panel privado de administración.</span></label></div><p class="small muted">Sin nombre, correo ni cuenta. Si recargas o cierras esta pestaña, se pierde el progreso.</p><div class="action-row spread"><button class="text-button" data-action="privacy">Cómo se usan mis datos</button>${primary('Empezar', 'consent-next', !(state.adult && state.processing && state.storage))}</div></div>${errorBox()}</section>`;
  }
  function sourceView(form) {
    if (!form) return '';
    const notes = ['translation_warning', 'intended_use', 'presentation_note', 'source_scope', 'population_note', 'limitations'].flatMap(key => list(form[key])).filter(Boolean);
    return `<div class="journey-source"><details><summary>Versión, fuente y alcance</summary>${form.version ? `<p><strong>Versión:</strong> ${esc(form.version)}</p>` : ''}${form.attribution ? `<p>${esc(form.attribution)}</p>` : ''}${form.content_license ? `<p>${esc(form.content_license)}</p>` : ''}${notes.map(note => `<p>${esc(note)}</p>`).join('')}${safeLink(form.source?.url, 'Consultar fuente')}</details></div>`;
  }
  function introView() {
    const form = state.forms[state.block];
    const intro = state.block === 'context';
    const instructions = intro ? '<p class="journey-instructions">Primero, cómo estás ahora y qué experiencias reconoces. Después vienen los cuestionarios del recorrido.</p>' : `${form?.instructions ? `<p class="journey-instructions">${esc(form.instructions)}</p>` : ''}${form?.intro ? `<p class="journey-instructions">${esc(form.intro)}</p>` : ''}`;
    const notices = intro ? [] : [...new Set((form?.intro_notice ? ['intro_notice'] : ['translation_warning', 'intended_use']).flatMap(key => list(form?.[key])).filter(Boolean))];
    return `<section class="view journey-narrow"><div class="screen-top"><button class="back-button" data-action="previous-block">← Anterior</button><span class="step-label">${esc(state.route.name)}</span></div>${progressView()}<div class="panel"><p class="eyebrow">${intro ? 'Un poco de contexto' : 'Siguiente cuestionario'}</p><h1>${intro ? 'Empecemos por ti.' : esc(form?.name || state.block)}</h1>${!intro && form?.title && form.title !== form.name ? `<p class="lead">${esc(form.title)}</p>` : ''}<div class="milestone" aria-label="Nuevo bloque"><span aria-hidden="true">✦</span> Un tema a la vez</div><div class="journey-block-tally"><span class="small-tag lilac">${visibleItems().length} preguntas visibles</span></div>${instructions}${notices.map(note => `<p class="journey-notice">${esc(note)}</p>`).join('')}<div class="action-row">${primary('Continuar', 'begin-block')}${!intro ? '<button class="quiet-button" data-action="omit-block">Omitir este cuestionario</button>' : ''}</div></div>${!intro ? sourceView(form) : ''}${errorBox()}</section>`;
  }
  function optionView(question, option, index, selected, compact = false) {
    const unknown = option.value === 98 || option.value === 99 || option.value === 'skipped' || option.value === 'unknown';
    return `<label class="option${selected ? ' chosen' : ''}${unknown ? ' unknown' : ''}${option.shaded ? ' journey-original-shaded' : ''}"><input type="${multiple(question) && !unknown ? 'checkbox' : 'radio'}" name="journey-answer" value="${index}" data-question="${esc(question.id)}"${selected ? ' checked' : ''}><span>${esc(option.label)}</span>${compact ? '' : '<span class="option-check" aria-hidden="true">✓</span>'}</label>`;
  }
  function questionView() {
    const questions = visibleItems(); state.index = Math.min(state.index, Math.max(0, questions.length - 1));
    const question = questions[state.index]; if (!question) return reviewView();
    const value = blockAnswers(state.block)[question.id];
    const options = optionsFor(question); const all = allOptions(question);
    const marked = option => Array.isArray(value) ? value.includes(option.value) : value === option.value;
    const normal = all.filter(option => !missing(option.value, question));
    const unknown = all.filter(option => missing(option.value, question));
    const compact = !multiple(question) && normal.length >= 6 && normal.every(option => typeof option.value === 'number' && String(option.label).length < 22);
    let field;
    if (question.type === 'text') {
      const text = typeof value === 'string' ? value : '';
      field = `<label class="journey-text-label" for="journey-field">${esc(question.input_label || (question.optional ? 'Respuesta breve (opcional)' : 'Tu respuesta'))}</label><textarea class="journey-text" id="journey-field" name="journey-text" rows="5" maxlength="${textLimit(question)}" autocomplete="off" aria-describedby="journey-text-count${question.input_hint ? ' journey-input-hint' : ''}">${esc(text)}</textarea>${question.input_hint ? `<p class="small muted" id="journey-input-hint">${esc(question.input_hint)}</p>` : ''}<p class="journey-text-count" id="journey-text-count">${Array.from(text).length} / ${textLimit(question)} caracteres</p>`;
    } else if (question.type === 'number') field = `<label class="journey-text-label" for="journey-field">${esc(question.input_label || 'Tu respuesta')}</label><input class="journey-number" id="journey-field" name="journey-number" type="number" inputmode="numeric" min="${esc(question.min ?? 0)}" max="${esc(question.max ?? 100)}" step="1" value="${typeof value === 'number' ? esc(value) : ''}" autocomplete="off"><p class="small muted">${esc(question.min ?? 0)} a ${esc(question.max ?? 100)} ${esc(question.unit || '')}</p>`;
    else if(ux?.sliderSpec(question)) field = ux.slider(question,value,'journey-slider');
    else field = `<div class="${compact ? 'journey-scale-grid' : 'options'}">${normal.map(option => optionView(question, option, all.indexOf(option), marked(option), compact)).join('')}</div>${compact ? `<div class="journey-scale-ends"><span>${esc(normal[0]?.label)}</span><span>${esc(normal[normal.length - 1]?.label)}</span></div>` : ''}`;
    const instruction = question.context_instructions ?? question.instruction ?? question.instructions ?? question.context ?? '';
    const previous = questions[state.index - 1]; const previousInstruction = previous && (previous.context_instructions ?? previous.instruction ?? previous.instructions ?? previous.context ?? '');
    const context = instruction ? `<div class="journey-question-context">${instruction.length > 230 && instruction === previousInstruction ? `<details><summary>Instrucciones de este bloque</summary><p>${esc(instruction)}</p></details>` : esc(instruction)}</div>` : '';
    return `<section class="view journey-narrow"><div class="screen-top"><button class="back-button" data-action="previous">← Anterior</button><span class="step-label">${esc(blockName(state.block))}</span></div>${progressView()}<div class="progress-row"><span class="question-block">${esc(question.group || (state.block === 'context' ? (question.block === 'safety' ? 'Cómo estás ahora' : 'Lo que reconoces') : blockName(state.block)))}</span><span class="question-step">Pregunta ${state.index + 1} de ${questions.length}</span></div>${context}${ux?.support(question.id,state.block) || ''}<fieldset class="question-form"><legend class="question-title">${esc(question.display_text || question.text)}</legend>${question.hint ? `<p class="question-hint">${esc(question.hint)}</p>` : ''}${multiple(question) ? `<p class="multiple-note">${question.max_choices && question.max_choices < options.length ? `Elige hasta ${esc(question.max_choices)} opciones.` : 'Puedes elegir varias opciones.'}</p>` : ''}${field}${options.some(option => own(option, 'shaded')) ? '<p class="journey-shading-note">Las zonas sombreadas pertenecen al formato original.</p>' : ''}<div class="unknown-options">${unknown.map(option => optionView(question, option, all.indexOf(option), marked(option))).join('')}</div></fieldset>${highDistress() && state.block === 'context' && question.id === 'S2' ? '<div class="journey-support-note"><p>Puedes hacer una pausa o seguir con el recorrido.</p><button class="text-button" data-action="pause">Pausar</button> · <button class="text-button" data-action="help">Ver apoyo disponible</button></div>' : ''}<div class="question-actions question-dock"><button class="dock-back" data-action="previous" aria-label="Regresar a la pregunta anterior">← Regresar</button><div class="dock-status"><span id="save-status" role="status" data-status="${esc(state.saveStatus)}">${state.saveStatus === 'saving' ? 'Guardando…' : state.saveStatus === 'error' ? 'Sin guardar · reintenta' : 'A tu ritmo'}</span><button class="text-button" data-action="review">Ver lo que llevo</button></div>${primary(state.editReview ? 'Volver a revisión' : state.index === questions.length - 1 ? 'Terminar bloque' : 'Continuar', 'next', !question.optional && !hasAnswer(question))}</div><div class="journey-question-help">${state.block !== 'context' ? '<button class="text-button" data-action="omit-block">Omitir este cuestionario</button><button class="text-button" data-action="instructions">Instrucciones y fuente</button>' : ''}</div>${errorBox()}</section>`;
  }
  function responseList(block) {
    return `<ol class="journey-review-list">${visibleItems(block).map((question, index) => `<li><p>${esc(question.display_text || question.text)}</p><span class="answer-value${missing(blockAnswers(block)[question.id], question) ? ' skipped' : ''}">${esc(answerLabel(question, block))}</span><button data-action="edit-question" data-block="${esc(block)}" data-index="${index}" aria-label="Cambiar respuesta ${index + 1}">Cambiar</button></li>`).join('')}</ol>`;
  }
  function reviewView() {
    const ids = expectedFormIds();
    return `<section class="view journey-narrow"><div class="screen-top"><button class="back-button" data-action="return-question">← Volver al recorrido</button><span class="step-label">${esc(state.route.name)}</span></div><p class="eyebrow">Antes del informe</p><h1>Revisa lo que llevas.</h1><p class="lead">Puedes omitir preguntas o cuestionarios. Para preparar una síntesis, el informe necesita algunas respuestas sobre reacciones actuales y su contexto.</p>${blockOrder().map(block => { const omitted = state.omitted[block]; const questions = visibleItems(block); const answered = questions.filter(question => !missing(blockAnswers(block)[question.id], question)).length; return `<section class="journey-review-block"><div class="journey-review-title"><div><h2>${esc(blockName(block))}</h2><p>${omitted ? 'Cuestionario omitido' : `${answered} de ${questions.length} preguntas respondidas`}</p></div><button class="secondary" data-action="${omitted ? 'include-block' : 'edit-block'}" data-block="${esc(block)}">${omitted ? 'Incluir' : 'Revisar'}</button></div>${!omitted && questions.length ? `<details><summary>Ver respuestas</summary>${responseList(block)}</details>` : ''}</section>`; }).join('')}<div class="review-ai-card"><h2>Une las piezas con IA.</h2><p>Recibirás una síntesis de tus respuestas, un perfil orientativo y temas para conversar con un profesional.</p><label class="consent-check"><input type="checkbox" id="ai-consent"${state.ai ? ' checked' : ''}><span>Acepto enviar mis selecciones y las puntuaciones calculadas por TRAZA a OpenAI para generar la síntesis de este recorrido.</span></label><details class="journey-ai-details"><summary>Qué se comparte y qué queda fuera</summary><p>Se comparten selecciones y puntuaciones, sin nombre ni correo. Las preguntas de seguridad y las descripciones libres del LEC-5 quedan fuera del envío a OpenAI. Esos textos pueden figurar en tu informe y descarga.</p><p>Las respuestas, consultas e informes de esta sesión se guardan cifrados hasta 90 días y pueden consultarse en el panel privado por el equipo autorizado de TRAZA. No se pide nombre ni correo. Se solicita a OpenAI que no guarde la respuesta; el proveedor puede conservar registros de seguridad hasta 30 días.</p><button class="text-button" data-action="privacy">Ver privacidad completa</button></details><div class="action-row">${primary(state.busy ? 'Preparando…' : 'Generar mi informe', 'generate', !state.ai || !state.schema?.ai_available || !!state.busy)}</div>${!state.schema?.ai_available ? '<p class="small">La IA no está disponible ahora. Puedes descargar tu registro y volver a comprobar más tarde.</p><button class="text-button" data-action="retry-schema">Volver a comprobar</button>' : ''}</div>${errorBox()}<div class="export-actions"><button class="secondary" data-action="preliminary">Descargar registro sin síntesis</button></div><p class="tiny muted">${ids.length} cuestionarios previstos en este recorrido. Las omisiones se indicarán en el informe.</p></section>`;
  }
  function evidenceView(ids, report) {
    const evidence = new Map(list(report.evidence).map(item => [item.id, item]));
    const selected = list(ids).map(id => evidence.get(id)).filter(Boolean);
    return selected.length ? `<details class="simple-details"><summary>Respuestas que sustentan esta lectura</summary><ul class="evidence-list">${selected.map(item => `<li>${esc(item.question)}<span>${esc(item.label)}</span></li>`).join('')}</ul></details>` : '';
  }
  function resultView() {
    const report = state.packet?.report; if (!report) return reviewView();
    const analysis = report.analysis; const profile = analysis.profile; const coverage = report.coverage;
    const professionalLabels = {synthesis: 'Síntesis del autorreporte', hypotheses: 'Hipótesis para explorar', differential: 'Aspectos que conviene diferenciar', priorities: 'Prioridades de entrevista', limitations: 'Límites de esta síntesis'};
    return `<section class="view"><div class="screen-top"><button class="back-button" data-action="review">← Revisar respuestas</button><span class="step-label">${esc(report.route.name)}</span></div><p class="eyebrow">Tu informe</p><h1 class="result-title">Las piezas de tu recorrido.</h1><div class="result-summary">${paragraphs(analysis.summary)}</div><div class="journey-coverage"><span class="small-tag">${list(coverage.completed_form_ids).length} cuestionarios completos</span>${list(coverage.incomplete_form_ids).length ? `<span class="small-tag lilac">${list(coverage.incomplete_form_ids).length} incompletos</span>` : ''}${list(coverage.omitted_form_ids).length ? `<span class="small-tag lilac">${list(coverage.omitted_form_ids).length} omitidos</span>` : ''}</div><div class="journey-final-actions">${primary(state.busy === 'pdf' ? 'Preparando PDF…' : 'Descargar mi PDF', 'pdf', !!state.busy)}<a class="secondary" href="/donar">Hacer donativo · opcional ♥</a><button class="secondary" data-action="final-json"${state.busy ? ' disabled' : ''}>Descargar informe JSON</button></div>${errorBox()}${ux?.results(report.scores || []) || ''}<section class="journey-profile"><h2>Tu perfil orientativo.</h2>${paragraphs(profile.description)}<div class="journey-profile-grid"><div><h3>Lo emocional</h3>${paragraphs(profile.emotional_patterns)}</div><div><h3>En el día a día</h3>${paragraphs(profile.daily_impact)}</div><div><h3>Tus recursos</h3>${paragraphs(profile.resources)}</div></div>${evidenceView(profile.evidence_ids, report)}</section><section class="journey-section"><h2>Posibles conexiones para explorar.</h2>${list(analysis.patterns).length ? list(analysis.patterns).map(pattern => `<article class="pattern-card"><h3>${esc(pattern.title)}</h3>${paragraphs(pattern.explanation)}${pattern.professional_work ? `<p><strong>Para conversar en consulta:</strong> ${esc(pattern.professional_work)}</p>` : ''}${pattern.uncertainty ? `<p class="uncertainty">${esc(pattern.uncertainty)}</p>` : ''}${evidenceView(pattern.evidence_ids, report)}</article>`).join('') : '<p class="small muted">Con las respuestas disponibles no se estableció una conexión suficientemente sustentada entre una experiencia reconocida y reacciones actuales.</p>'}</section><section class="journey-section"><h2>Por dónde seguir.</h2><ul class="reflection-list">${list(analysis.next_steps).map(step => `<li>${esc(step)}</li>`).join('')}</ul></section><section class="journey-section"><h2>Preguntas para llevar a consulta.</h2><ul class="reflection-list">${list(analysis.questions_for_professional).map(question => `<li>${esc(question)}</li>`).join('')}</ul></section><details class="journey-professional"><summary>Sección para profesionales · Generada por IA para revisión</summary><p class="small muted">Esta sección también está incluida en la última hoja del PDF.</p>${Object.entries(professionalLabels).map(([key, label]) => `<h3>${label}</h3>${paragraphs(analysis.professional[key])}`).join('')}</details><details class="simple-details journey-section"><summary>Puntuaciones y cobertura de los cuestionarios</summary>${list(report.scores).map(score => `<article class="journey-score-row"><div class="journey-score-head"><h3>${esc(score.name)}</h3><strong>${!score.complete ? 'Incompleto' : score.total == null ? 'Registro descriptivo' : `${esc(score.total)}${Array.isArray(score.range) ? ` / ${esc(score.range[1])}` : ''}`}</strong></div>${score.band ? `<p>${esc(typeof score.band === 'string' ? score.band : score.band.label || '')}</p>` : ''}${paragraphs(score.summary)}${list(score.subscales).length ? `<ul>${score.subscales.map(part => `<li>${esc(part.name || part.id)}: ${esc(part.value ?? part.total ?? part.score ?? '')}${part.max != null ? ` / ${esc(part.max)}` : ''}</li>`).join('')}</ul>` : ''}<p class="tiny muted">${esc(score.answered)} de ${esc(score.required)} respuestas requeridas · ${esc(score.version)}</p>${safeLink(score.source_url, 'Fuente del instrumento')}</article>`).join('')}${list(coverage.omitted_form_ids).length ? `<p class="small muted">Omitidos: ${esc(coverage.omitted_form_ids.map(id => state.forms[id]?.name || FORM_NAMES[id] || 'Cuestionario').join(', '))}.</p>` : ''}</details><details class="journey-meta"><summary>Cómo se preparó este informe</summary><p>Generado por ${esc(report.meta.provider)} · ${esc(report.meta.model)} · ${esc(report.meta.prompt_version)}.</p><p>Informe ${esc(report.report_id)} · ${esc(formatDate(report.generated_at))}</p>${paragraphs(report.limitations)}${paragraphs(report.safety_note)}<ul>${list(report.references).map(reference => `<li>${safeLink(reference.url, reference.title)}</li>`).join('')}</ul></details><div class="action-row"><button class="secondary" data-action="routes">Ver los recorridos</button><a class="quiet-button" href="/instrumentos">Cuestionarios individuales ↗</a></div></section>`;
  }
  function formatDate(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value || '') : date.toLocaleString('es-MX', {dateStyle: 'medium', timeStyle: 'short'}); }
  function resources() { return '<div class="resource-card"><h3>Si hay peligro inmediato</h3><p>Llama al 911 o pide a alguien que te acompañe.</p><a class="call" href="tel:911">Llamar al 911 ↗</a></div><div class="resource-card"><h3>Orientación en salud mental</h3><a class="call" href="tel:+528009112000?oai_link_source=model_response_hotline">800 911 2000 ↗</a><a class="source-link" href="https://www.gob.mx/conasama/articulos/linea-de-la-vida-800-911-2000?oai_link_source=model_response_hotline" target="_blank" rel="noopener noreferrer">Información oficial de la Línea de la Vida ↗</a></div>'; }
  function safetyView() { return `<section class="view journey-narrow safety-view"><p class="eyebrow">Atención ahora</p><h1>Busca apoyo para estar a salvo.</h1><p class="lead">Señalaste peligro o riesgo actual. El informe queda en pausa.</p>${resources()}<div class="action-row"><button class="secondary" data-action="correct-safety">Corregir mi respuesta</button><button class="quiet-button" data-action="preliminary">Guardar mi registro</button></div></section>`; }
  function dangerView() { const question = contextItems().find(item => item.id === 'S3'); const options = question?.options || []; return `<section class="view journey-narrow"><p class="eyebrow">Una comprobación actual</p><h1 class="question-title">${esc(question?.text || '¿Hay riesgo de que te hagas daño ahora o en las próximas horas?')}</h1>${ux?.support('S3','context') || ''}<p class="question-hint">La respuesta del cuestionario puede referirse a otros momentos. Esta pregunta es sobre ahora.</p><div class="options">${options.map((option, index) => `<label class="option"><input type="radio" name="journey-danger" value="${index}"><span>${esc(option.label)}</span></label>`).join('')}</div><div class="action-row question-dock"><button class="dock-back" data-action="go-back">← Regresar</button>${primary('Continuar', 'danger-next', true)}</div></section>`; }
  function pausedView() { return `<section class="view journey-narrow paused-view"><div class="block-icon" aria-hidden="true">Ⅱ</div><h1>Recorrido en pausa.</h1><p class="lead">Tus respuestas confirmadas están guardadas. Para continuar aquí, conserva esta pestaña abierta.</p><div class="action-row">${primary('Continuar', 'resume')}<button class="secondary" data-action="preliminary">Guardar registro sin síntesis</button></div></section>`; }
  function loadingView() { return `<section class="view journey-narrow loading-view"><div class="loading-symbol" aria-hidden="true"><div class="spinner"></div></div><h1>${state.busy === 'report' ? 'Conectando tus respuestas.' : 'Preparando tu recorrido.'}</h1><p class="lead journey-loading-message">${state.busy === 'report' ? 'La IA está preparando la síntesis y la sección para consulta. Puede tardar un momento.' : 'Cargando los formularios de esta ruta…'}</p></section>`; }
  async function request(url, options = {}) {
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 95000);
    try {
      const response = await fetch(url, {credentials: 'same-origin', cache: 'no-store', ...options, signal: controller.signal, headers: {Accept: 'application/json', ...options.headers}});
      let data; try { data = await response.json(); } catch (_) { throw new Error('El servidor no devolvió una respuesta válida. Tus respuestas siguen en esta pestaña.'); }
      if (!response.ok) { const detail = data.detail; const error = new Error(typeof detail === 'string' ? detail : detail?.message || data.message || 'No se pudo completar la solicitud.'); error.code = data.code || detail?.code; error.status = response.status; throw error; }
      return data;
    } catch (error) { if (error.name === 'AbortError') throw new Error('La solicitud tardó demasiado. Tus respuestas siguen aquí; puedes volver a intentar.'); throw error; }
    finally { clearTimeout(timer); }
  }
  async function loadSchema() {
    state.schemaError = '';
    try { const schema = await request('/api/journey/schema'); if (schema.version !== VERSION || !Array.isArray(schema.routes) || !Array.isArray(schema.context_questions)) throw new Error('Los recorridos no están disponibles en este momento.'); state.schema = schema; }
    catch (error) { state.schemaError = error.message; }
    render(false);
  }
  async function ensureForms() {
    const needed = expectedFormIds().filter(id => !state.forms[id]);
    const results = await Promise.allSettled(needed.map(async id => { const form = await request(`/api/battery/${encodeURIComponent(id)}`); if (form.id !== id || !Array.isArray(form.items)) throw new Error('No se pudo preparar ' + id + '.'); state.forms[id] = form; }));
    const failed = results.find(result => result.status === 'rejected'); if (failed) throw failed.reason;
  }
  async function chooseRoute(id, reset = true) {
    const route = state.schema?.routes.find(item => item.id === id); if (!route) return;
    if (state.route?.id === id && hasProgress()) { resumeRoute(); return; }
    if (reset) { if(!(await saved())) return; try { activity.reset(); } catch(error) { state.error = error.message; render(false); return; } state.consented = false; state.context = {}; state.answers = {}; state.omitted = {}; state.seen = {}; state.block = 'context'; state.index = 0; state.phqChecked = false; state.editReview = false; invalidate(); }
    state.route = route; state.busy = 'forms'; state.view = 'loading'; render();
    try { await ensureForms(); state.view = state.consented ? 'intro' : 'consent'; state.resumeView = 'intro'; }
    catch (error) { state.error = error.message; state.view = 'routes'; }
    finally { state.busy = ''; render(); }
  }
  function resumeRoute() { if (!state.route) { state.view = 'routes'; render(); return; } state.view = blocked() ? 'safety' : state.packet ? 'result' : state.resumeView || 'intro'; if (['home', 'routes', 'loading', 'paused'].includes(state.view)) state.view = 'intro'; render(); }
  async function enterReview() {
    if(!(await saved())) return;
    prune(); state.error = ''; state.busy = 'forms'; state.view = 'loading'; render();
    try { await ensureForms(); state.view = 'review'; state.resumeView = 'review'; }
    catch (error) { state.error = error.message; state.view = 'question'; }
    finally { state.busy = ''; render(); }
  }
  async function advance() {
    if(!(await saved())) return;
    if (blocked()) { state.view = 'safety'; render(); return; }
    if (shouldCheckDanger()) { state.dangerNext = 'next'; state.view = 'danger'; render(); return; }
    if (state.editReview) { state.editReview = false; await enterReview(); return; }
    if (state.index < visibleItems().length - 1) { state.index++; state.view = 'question'; state.resumeView = 'question'; render(); return; }
    await nextBlock();
  }
  async function nextBlock() {
    state.busy = 'forms'; state.view = 'loading'; render();
    try { await ensureForms(); const order = blockOrder(); const next = order.slice(order.indexOf(state.block) + 1).find(block => !state.omitted[block]); if (!next) { state.view = 'review'; state.resumeView = 'review'; } else { state.block = next; state.index = 0; state.editReview = false; state.view = state.seen[next] ? 'question' : 'intro'; state.resumeView = state.view; } }
    catch (error) { state.error = error.message; state.view = 'question'; }
    finally { state.busy = ''; render(); }
  }
  function previousBlock() { const order = blockOrder(); const previous = order.slice(0, order.indexOf(state.block)).reverse().find(block => !state.omitted[block]); if (previous) { state.block = previous; state.index = Math.max(0, visibleItems(previous).length - 1); state.view = 'question'; } else state.view = 'routes'; state.resumeView = state.view === 'routes' ? 'intro' : state.view; render(); }
  async function omitBlock() { if (state.block === 'context') return; if(!(await tracked('omit_form',state.block))) return; const block = state.block; state.omitted[block] = true; delete state.answers[block]; invalidate(); state.editReview = false; await nextBlock(); }
  function payload() {
    prune();
    const forms = {};
    for (const id of expectedFormIds()) if (!state.omitted[id] && state.forms[id]) forms[id] = {version: state.forms[id].version || '1.0', answers: {...(state.answers[id] || {})}};
    return {version: VERSION, route_id: state.route.id, request_id: state.requestId || (state.requestId = crypto.randomUUID()), consent: {adult: state.adult, processing: state.processing, ai: state.ai, version: VERSION}, context_answers: {...state.context}, forms, safety_state: {current_risk: state.context.S3 === 1, unsafe: state.context.S1 === 0}};
  }
  function validPacket(packet) { return packet && typeof packet.signature === 'string' && packet.signature.length > 0 && packet.report?.version === VERSION && packet.report.analysis && packet.report.coverage && packet.report.route && packet.report.analysis.profile && ['synthesis', 'hypotheses', 'differential', 'priorities', 'limitations'].every(key => typeof packet.report.analysis.professional?.[key] === 'string'); }
  async function generate() {
    if (state.busy || !state.ai || !state.adult || !state.processing || !state.schema?.ai_available) return;
    if (blocked()) { state.view = 'safety'; render(); return; }
    if (shouldCheckDanger()) { state.dangerNext = 'report'; state.view = 'danger'; render(); return; }
    state.error = ''; state.busy = 'report'; state.view = 'loading'; render();
    try { if(!(await tracked('ai_request'))) { state.view = 'review'; return; } await ensureForms(); const packet = state.packet || await request('/api/journey/report', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Traza-Activity-Session':activity.session.session_id,'X-Traza-Visit':activity.visit.visit_token}, body: JSON.stringify(payload())}); if (!validPacket(packet)) throw new Error('El informe llegó incompleto. Conservamos tus respuestas para que puedas intentar de nuevo.'); state.packet = packet; await activity.report(packet); await activity.event('complete'); state.view = 'result'; state.resumeView = 'result'; }
    catch (error) { activity.event('ai_error').catch(()=>{}); state.error = error.message; state.view = error.code === 'safety_blocked' ? 'safety' : 'review'; }
    finally { state.busy = ''; render(); }
  }
  function downloadBlob(blob, filename) { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
  async function preliminary() { if (!state.route) return; if(!(await tracked('export',null,null,'json'))) return; const packet = payload(); const data = {product: 'TRAZA', version: VERSION, document_status: 'preliminary_without_ai', notice: 'Registro de respuestas. No contiene síntesis de IA ni sección profesional y no es el informe final.', created_at: new Date().toISOString(), route: {id: state.route.id, name: state.route.name}, context_answers: packet.context_answers, forms: packet.forms, omitted_form_ids: expectedFormIds().filter(id => state.omitted[id])}; downloadBlob(new Blob([JSON.stringify(data, null, 2)], {type: 'application/json;charset=utf-8'}), `TRAZA-registro-sin-sintesis-${state.route.id}.json`); }
  async function finalJson() { if(!(await tracked('export',null,null,'json'))) return; if (!validPacket(state.packet)) return; downloadBlob(new Blob([JSON.stringify(state.packet, null, 2)], {type: 'application/json;charset=utf-8'}), `TRAZA-informe-${state.packet.report.report_id}.json`); }
  async function downloadPdf() {
    if (state.busy || !validPacket(state.packet)) return; if(!(await tracked('export',null,null,'pdf'))) return; state.busy = 'pdf'; state.error = ''; render(false);
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 95000);
    try { const response = await fetch('/api/journey/pdf', {method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: {'Content-Type': 'application/json', Accept: 'application/pdf'}, body: JSON.stringify({report: state.packet.report, signature: state.packet.signature})}); if (!response.ok) { let data = {}; try { data = await response.json(); } catch (_) {} throw new Error(typeof data.detail === 'string' ? data.detail : 'No se pudo preparar el PDF. Tu informe sigue disponible aquí.'); } const blob = await response.blob(); if (!(await blob.slice(0, 5).text()).startsWith('%PDF-')) throw new Error('El servidor no devolvió un PDF válido. Tu informe sigue disponible aquí.'); downloadBlob(blob, `TRAZA-informe-${state.packet.report.report_id}.pdf`); }
    catch (error) { state.error = error.name === 'AbortError' ? 'El PDF tardó demasiado. Puedes volver a intentar; tu informe sigue aquí.' : error.message; }
    finally { clearTimeout(timer); state.busy = ''; render(false); }
  }
  let modalFocus = null;
  function showModal(title, content) { modalFocus = document.activeElement; modalRoot.innerHTML = `<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1"><div class="modal-head"><h2 id="modal-title">${esc(title)}</h2><button class="modal-close" data-action="close-modal" aria-label="Cerrar">×</button></div>${content}</section></div>`; modalRoot.querySelector('.modal-close').focus(); }
  function closeModal() { modalRoot.innerHTML = ''; modalFocus?.focus?.(); }
  function privacy() { showModal('Tus datos, en claro', '<p>Sin nombre ni correo. Al abrir TRAZA se registra una visita con tipo de dispositivo, sistema y navegador; el registro no guarda el agente de usuario completo ni usa una huella para reconocer personas entre visitas.</p><p>Solo después de aceptar el registro se guardan tus respuestas, cambios, consultas e informes cifrados en la base interna durante un máximo de 90 días. El equipo autorizado puede consultarlos desde el panel privado de administración. El progreso para continuar se mantiene en esta pestaña; no hay una cuenta personal ni recuperación al recargar.</p><p>Al aceptar la síntesis se envían a OpenAI selecciones y puntuaciones. Las respuestas de seguridad y las descripciones libres del LEC-5 quedan fuera de ese envío. Se solicita al proveedor que no guarde la respuesta; puede conservar registros de seguridad hasta 30 días.</p><p>El PDF se prepara en TRAZA sin otra llamada a OpenAI. Los archivos descargados quedan en tu dispositivo. Puedes solicitar el borrado de la sesión actual aquí; no elimina archivos ya descargados ni copias de seguridad anteriores.</p><button class="secondary" data-action="erase-session">Borrar esta sesión guardada</button>'); }
  function clearAndExit() { state.exiting = true; state.context = {}; state.answers = {}; state.packet = null; state.omitted = {}; location.replace('https://www.google.com/'); }
  document.addEventListener('input', event => {
    const target = event.target; if (target.name === 'journey-slider' && !state.busy) { const question = visibleItems()[state.index]; if(!question || question.id !== target.dataset.question) return; const value = Number(target.value); const spec = ux?.sliderSpec(question); if(!spec?.options.some(option=>option.value===value)) return; saveAnswer(question,value); const output=document.getElementById('slider-output'); if(output)output.textContent=spec.options.find(option=>option.value===value).label; target.setAttribute('aria-valuetext',output?.textContent||String(value)); target.closest('.answer-slider')?.classList.add('touched'); const hint=document.getElementById('slider-instruction');if(hint)hint.textContent='Puedes ajustar la barra o pasar la pregunta.'; const progress=main.querySelector('.journey-progress');if(progress)progress.outerHTML=progressView(); const next = main.querySelector('[data-action="next"]'); if(next)next.disabled=false; return; }
    if (!['journey-text', 'journey-number'].includes(target.name) || state.busy) return;
    const question = visibleItems()[state.index]; if (!question) return;
    if (target.name === 'journey-text' && question.type === 'text') { const value = Array.from(target.value).slice(0, textLimit(question)).join(''); target.value = value; saveAnswer(question, value); const count = document.getElementById('journey-text-count'); if (count) count.textContent = `${Array.from(value).length} / ${textLimit(question)} caracteres`; }
    else if (target.name === 'journey-number' && question.type === 'number') { const value = target.value.trim(); const number = Number(value); if (value && Number.isInteger(number) && number >= (question.min ?? 0) && number <= (question.max ?? 100)) saveAnswer(question, number); else { const answers = state.answers[state.block] ||= {}; delete answers[question.id]; prune(); invalidate(); pendingText={question,value:null,block:state.block}; clearTimeout(saveTimer); saveTimer=setTimeout(flushText,700); } }
    main.querySelectorAll('input[name="journey-answer"]').forEach(input => { input.checked = false; input.closest('.option').classList.remove('chosen'); });
    const progress=main.querySelector('.journey-progress');if(progress)progress.outerHTML=progressView();
    const next = main.querySelector('[data-action="next"]'); if (next) next.disabled = !question.optional && !hasAnswer(question);
  });
  document.addEventListener('change', event => {
    const target = event.target; if (state.busy) return;
    if (target.id === 'adult-consent' || target.id === 'processing-consent' || target.id === 'storage-consent') { state.adult = document.getElementById('adult-consent').checked; state.processing = document.getElementById('processing-consent').checked; state.storage = document.getElementById('storage-consent').checked; const next = main.querySelector('[data-action="consent-next"]'); if (next) next.disabled = !(state.adult && state.processing && state.storage); return; }
    if (target.id === 'ai-consent') { state.ai = target.checked; const button = main.querySelector('[data-action="generate"]'); if (button) button.disabled = !state.ai || !state.schema?.ai_available; return; }
    if (target.name === 'journey-danger') { const question = contextItems().find(item => item.id === 'S3'); const selected = question?.options[Number(target.value)]; if (!selected) return; state.context.S3 = selected.value; registerAnswer(question,selected.value,'context'); state.phqChecked = true; state.packet = null; state.requestId = null; const next = main.querySelector('[data-action="danger-next"]'); if (next) next.disabled = false; return; }
    if (target.name !== 'journey-answer') return;
    const question = visibleItems()[state.index]; if (!question || question.id !== target.dataset.question) return;
    const selected = allOptions(question)[Number(target.value)]; if (!selected) return; let value = selected.value;
    if (multiple(question) && !missing(value, question)) { const previous = Array.isArray(blockAnswers(state.block)[question.id]) ? blockAnswers(state.block)[question.id] : []; const exclusive = list(question.exclusive_values); value = target.checked ? exclusive.includes(value) ? [value] : [...previous.filter(part => part !== value && !exclusive.includes(part)), value] : previous.filter(part => part !== value); if (question.max_choices && value.length > question.max_choices) { state.error = `Elige hasta ${question.max_choices} opciones.`; render(false); return; } }
    saveAnswer(question, value); render(false); main.querySelector(`input[name="journey-answer"][value="${Number(target.value)}"]`)?.focus({preventScroll: true});
  });
  document.addEventListener('click', async event => {
    const button = event.target.closest('[data-action]'); if (!button || button.disabled) return; const action = button.dataset.action;
    const serial=['next','danger-next','consent-next','choose-route','confirm-route','omit-block','generate','include-block','erase-session'].includes(action); if(serial && state.navBusy)return; if(serial)state.navBusy=true; try {
    if (button.tagName === 'A') event.preventDefault();
    if (state.busy && !['help', 'privacy', 'close-modal', 'exit', 'motion', 'go-back'].includes(action)) return;
    if (action === 'retry-save') { await saved(); return; }
    if (action === 'erase-session') {
      const previousBusy=state.busy; state.busy='erase'; flushText();
      try {
        await activity.erase();
        clearTimeout(saveTimer); saveTimer=null; pendingText=null;
        Object.assign(state,{route:null,context:{},answers:{},packet:null,omitted:{},seen:{},consented:false,adult:false,processing:false,storage:false,ai:false,requestId:null,block:'context',index:0,resumeView:'intro',pauseView:'question',editReview:false,phqChecked:false,dangerNext:'next',pendingRoute:null,error:'',saveError:'',saveStatus:'',busy:'',view:'home'});
        closeModal(); render();
      } catch(error) { state.busy=previousBusy; state.error=error.message; closeModal(); render(false); }
      return;
    }
    if (action === 'go-back') { state.view = state.view === 'routes' ? 'home' : state.view === 'loading' ? 'routes' : state.view === 'danger' ? 'question' : state.view === 'paused' ? state.pauseView : state.route ? 'intro' : 'home'; render(); return; }
    if (['home','routes','previous','previous-block','return-question','edit-block','include-block','edit-question','pause','preliminary','confirm-route'].includes(action) && !(await saved())) return;
    if (action === 'home' || action === 'routes') { if (['question', 'intro', 'review', 'result'].includes(state.view)) state.resumeView = state.view; state.view = action; state.error = ''; render(); }
    else if (action === 'resume-route') resumeRoute();
    else if (action === 'retry-schema') loadSchema();
    else if (action === 'choose-route') { if (state.route && state.route.id !== button.dataset.route && hasProgress()) { state.pendingRoute = button.dataset.route; showModal('Cambiar de recorrido', `<p>Se borrarán las respuestas y el informe de este recorrido en la pestaña. Puedes descargar tu registro antes de cambiar.</p><div class="action-row"><button class="secondary" data-action="preliminary">Descargar registro</button><button class="quiet-button" data-action="close-modal">Conservar mi recorrido</button>${primary('Cambiar recorrido', 'confirm-route')}</div>`); } else await chooseRoute(button.dataset.route); }
    else if (action === 'confirm-route') { const id = state.pendingRoute; closeModal(); state.pendingRoute = null; await chooseRoute(id); }
    else if (action === 'consent-next' && state.adult && state.processing && state.storage) { await startStorage(); }
    else if (action === 'begin-block') { state.seen[state.block] = true; state.view = 'question'; state.resumeView = 'question'; render(); }
    else if (action === 'next') await advance();
    else if (action === 'previous') { state.editReview = false; if (state.index > 0) { state.index--; render(); } else { state.view = 'intro'; render(); } }
    else if (action === 'previous-block') previousBlock();
    else if (action === 'omit-block') await omitBlock();
    else if (action === 'review') await enterReview();
    else if (action === 'return-question') { if (state.omitted[state.block]) { state.block = blockOrder().slice(0, blockOrder().indexOf(state.block)).reverse().find(block => !state.omitted[block]) || 'context'; state.index = Math.max(0, visibleItems().length - 1); } state.view = 'question'; render(); }
    else if (action === 'edit-block' || action === 'include-block') { state.block = button.dataset.block; if (state.omitted[state.block]) { if(!(await tracked('resume_form',state.block))) return; delete state.omitted[state.block]; invalidate(); } state.index = 0; state.editReview = false; state.view = state.seen[state.block] || state.block === 'context' ? 'question' : 'intro'; render(); }
    else if (action === 'edit-question') { state.block = button.dataset.block; state.index = Number(button.dataset.index); state.editReview = true; state.view = 'question'; render(); }
    else if (action === 'generate') await generate();
    else if (action === 'pdf') downloadPdf();
    else if (action === 'final-json') finalJson();
    else if (action === 'preliminary') preliminary();
    else if (action === 'instructions') { const form = state.forms[state.block]; showModal(form?.name || 'Instrucciones', `${form?.instructions ? `<p class="journey-instructions">${esc(form.instructions)}</p>` : ''}${sourceView(form)}`); }
    else if (action === 'privacy') privacy();
    else if (action === 'help') showModal('Apoyo disponible', resources());
    else if (action === 'close-modal') closeModal();
    else if (action === 'pause') { state.pauseView = state.view; state.view = 'paused'; render(); }
    else if (action === 'resume') { state.view = blocked() ? 'safety' : state.pauseView; render(); }
    else if (action === 'correct-safety') { state.block = 'context'; const id = state.context.S1 === 0 ? 'S1' : 'S3'; state.index = Math.max(0, visibleItems('context').findIndex(question => question.id === id)); state.editReview = false; state.view = 'question'; render(); }
    else if (action === 'danger-next') { if(!(await saved())) return; if (blocked()) { state.view = 'safety'; render(); } else if (state.dangerNext === 'report') await generate(); else await advance(); }
    else if (action === 'motion') { const reduced = document.documentElement.classList.toggle('reduce-motion'); document.getElementById('motion-toggle').setAttribute('aria-pressed', String(reduced)); }
    else if (action === 'exit') clearAndExit();
  } finally { if(serial)state.navBusy=false; }
  });
  document.addEventListener('click', event => { if (event.target.classList.contains('modal-backdrop')) closeModal(); });
  let escapes = [];
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { if (modalRoot.childElementCount) { closeModal(); return; } const now = Date.now(); escapes = [...escapes.filter(time => now - time < 1800), now]; if (escapes.length >= 3) clearAndExit(); }
    if (event.key === 'Tab' && modalRoot.childElementCount) { const controls = [...modalRoot.querySelectorAll('button,a[href],input,textarea,[tabindex="0"]')].filter(control => !control.disabled); const first = controls[0], last = controls[controls.length - 1]; if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); } }
  });
  window.addEventListener('beforeunload', event => { if (!state.exiting && hasProgress()) { event.preventDefault(); event.returnValue = ''; } });
  render(false);
  loadSchema();
})();

