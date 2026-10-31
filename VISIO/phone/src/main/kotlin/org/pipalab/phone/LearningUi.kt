package org.pipalab.phone
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun LearningPanel(engine:LearningEngine,s:LearningState) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Aprendizaje local",fontSize=27.sp,fontWeight=FontWeight.SemiBold)
        Text("Mejora con tus ejemplos revisados y conserva cada versión.",color=Muted,fontSize=13.sp)
        SurfaceCard {
            Eyebrow(if(s.active.isBlank())"REUNIENDO EVIDENCIA" else "MODELO EXPERIMENTAL ACTIVO")
            Text(s.message,color=if(s.active.isBlank())Amber else Mint,fontSize=14.sp)
            if(s.busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth(),color=Lime)
            Row(verticalAlignment=Alignment.CenterVertically){Text("Actualizar automáticamente",modifier=Modifier.weight(1f),fontSize=14.sp);Switch(s.automatic,{engine.automatic(it)})}
            Text("Solo se habilita una versión si tiene suficientes ejemplos y supera la evaluación. No se crean etiquetas a partir de sus propias predicciones.",fontSize=12.sp,color=Muted)
            OutlinedButton(onClick={engine.refresh(true)},enabled=!s.busy){Text("Revisar aprendizaje ahora")}
        }
        SurfaceCard {
            Eyebrow("BASE REVISADA")
            Text("${s.trained} para aprender · ${s.reserved} para evaluar",fontSize=17.sp,color=Lime)
            Text("${s.skipped} registros pendientes, excluidos, incompletos o sin calidad suficiente",fontSize=12.sp,color=Muted)
            s.counts.forEach{(label,n)->Text("$label: ${n.first}/5 entrenamiento · ${n.second}/2 evaluación",fontSize=13.sp)}
            Text("Graba varias sesiones por tipo. Marca ‘Reservar para evaluar’ ANTES de registrar algunas de ellas. Incluye reposo y actividades parecidas, y repite en otros días.",fontSize=12.sp,color=Muted)
            s.reasons.take(6).forEach{Text(it,fontSize=11.sp,color=Amber)}
        }
        SurfaceCard {
            Eyebrow("CÓMO APRENDE")
            Text("Compara características de movimiento, pulso y temperatura. Un ejemplo es una sesión completa: las filas vecinas no se reparten entre entrenamiento y evaluación.",fontSize=13.sp,color=Muted)
            Text("Si una sugerencia es incorrecta, corrige el tipo en Sesiones → Revisar y describir. Al guardar se recalcula el modelo. Excluir una sesión también la retira de futuras versiones.",fontSize=13.sp,color=Muted)
            Text("El audio y las tomas de Zoom siguen como material etiquetado: este modelo de actividades todavía no aprende a reconocer encendedores por sonido. La demo sonora del LED es independiente.",fontSize=12.sp,color=Amber)
        }
        SurfaceCard {
            Eyebrow("VERSIONES Y EVALUACIÓN")
            if(s.versions.isEmpty())Text("Aún no hay una versión: hacen falta al menos dos tipos y dos sesiones válidas por tipo para preparar el primer modelo.",fontSize=13.sp,color=Muted)
            s.versions.take(12).forEach{v->
                HorizontalDivider(color=Edge)
                Text(time(v.created,"dd/MM HH:mm")+" · "+v.id.take(8)+(if(v.id==s.active)" · ACTIVO" else ""),fontSize=13.sp,color=Lime)
                Text("${v.train} entrenamiento · ${v.validation} evaluación",fontSize=12.sp)
                Text(if(v.validation==0)"Sin evaluación reservada" else "Acierto equilibrado: ${num(v.score*100)}% · respuestas: ${num(v.coverage*100)}%",fontSize=12.sp,color=Muted)
                Text(if(v.eligible)"Cumplió los mínimos internos al crearse" else "Provisional: no habilitado para sugerencias",fontSize=11.sp,color=Amber)
                OutlinedButton(enabled=v.eligible && v.id!=s.active && !s.busy,onClick={engine.select(v.id)}){Text("Usar esta versión",fontSize=12.sp)}
            }
            Text("Los porcentajes son de tu evaluación reservada; no demuestran precisión general. Al volver a una versión se comprueba que sus ejemplos sigan aceptados. Las sugerencias ambiguas responden ‘no sé’ y no activan el LED.",fontSize=11.sp,color=Muted)
        }
    }
}
