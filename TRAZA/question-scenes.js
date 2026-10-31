/* Contextual editorial photographs created for Nemy. These scenes do not describe the respondent. */
'use strict';
(()=>{
 const names={path:'Un recorrido a tu ritmo',safety:'Un espacio para detenerte',privacy:'Un momento propio',feelings:'Observar lo que sientes',support:'Un momento para escuchar',sleep:'Descanso y contexto',energy:'La energía del día',meals:'Una pausa cotidiana',worry:'Pensamientos y preocupaciones',calm:'Un momento de calma',focus:'Una cosa a la vez',organization:'Dar espacio a una decisión',memory:'Recuerdos y momentos',movement:'Recuperar el movimiento',selfworth:'Cómo te miras',connection:'Compartir con otras personas',distance:'Cercanía y distancia',grounding:'Estar en el presente',boundaries:'Un espacio propio',timeline:'Ubicar los momentos',loss:'Un espacio para una ausencia',events:'Mirar las experiencias',professional:'Conversar y escuchar',routine:'Las tareas del día',choices:'Elegir qué explorar',change:'Un paso de cambio',body:'Notar el cuerpo',creativity:'Actividades que acompañan'};
 const groups={
  'hero-valle':['path','choices','grounding','calm','privacy','boundaries'],
  'nuevo-amanecer':['safety','sleep','meals','routine','organization','memory','events','timeline','loss'],
  'emociones-ventana':['feelings','worry','energy','focus','body','creativity'],
  'acompanar-conversacion':['support','connection','professional','distance'],
  'impulso-montana':['movement','change'],
  'consumo-reflejo':['selfworth']
 };
 const fileFor=Object.freeze(Object.fromEntries(Object.entries(groups).flatMap(([file,ids])=>ids.map(id=>[id,file]))));
 function render(scene){
  const key=Object.prototype.hasOwnProperty.call(names,scene)?scene:'path';
  const file=fileFor[key]||'nuevo-amanecer';
  return `<picture class="traza-photo"><source media="(max-width:650px)" srcset="/media/${file}-mobile.webp"><img class="traza-scene" src="/media/${file}.webp" alt="${names[key]} · fotografía conceptual" width="1600" height="900" loading="lazy" decoding="async"></picture>`;
 }
 window.TRAZA_SCENES=Object.freeze({render,ids:Object.freeze(Object.keys(names)),version:'7.0'});
})();
