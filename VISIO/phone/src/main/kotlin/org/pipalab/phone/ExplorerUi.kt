package org.pipalab.phone

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pipalab.core.TimeWindow
import kotlin.math.*

private val Danger=Color(0xFFFF7185)
internal data class RawQuery(val session:String,val stream:String,val from:Long,val to:Long)
internal fun sensorDescription(metric:Metric):String=when(metric.label) {
    "Pulso" -> "Latidos por minuto estimados por el sensor óptico. Aporta contexto fisiológico para comparar antes y después de un evento; por sí solo no identifica una actividad. Se excluyen las lecturas que el SDK marca inválidas."
    "Movimiento","Acelerómetro" -> "El acelerómetro mide aceleración en tres ejes, incluida la gravedad. Ayuda a reconocer elevación, pausa y retorno de la muñeca. No mide tu ubicación ni guarda GPS."
    "Giro","Giroscopio" -> "El giroscopio mide la velocidad de rotación de la muñeca en tres ejes. Complementa al acelerómetro para distinguir trayectorias y pausas de un gesto."
    "IBI" -> "Tiempo entre latidos, en milisegundos. Sirve para estudiar cambios y variabilidad con ventanas suficientes y buena calidad. El promedio usa todos los intervalos válidos, no solo el último; se asignan al minuto del lote de origen."
    "Piel","Temperatura de piel" -> "Temperatura superficial de la piel y del entorno del sensor. Aporta una tendencia lenta; el contacto y el ambiente influyen. No equivale a temperatura corporal central y no está disponible a cada instante."
    "EDA" -> "Conductancia eléctrica de la piel, relacionada con la actividad de las glándulas sudoríparas. Puede aportar contexto de activación, sin identificar su causa. Este Galaxy Watch no ofrece EDA al SDK."
    "PPG","PPG IR","PPG rojo" -> "Señal óptica obtenida con luz verde, infrarroja o roja. Permite estudiar la forma de la onda y artefactos por movimiento. Estos valores son cuentas del sensor; no representan por sí mismos oxígeno en sangre."
    "Uso" -> "Indica si el reloj detecta contacto con la muñeca. Evita interpretar movimientos o señales ópticas cuando está fuera del cuerpo. 1 = puesto; 0 = fuera; desconocido no se promedia."
    "Batería" -> "Carga del reloj. Ayuda a evaluar autonomía y a explicar interrupciones del registro. Es un estado auxiliar de la sesión."
    else -> "Señal auxiliar del registro local. Conserva su hora y estado de calidad."
}

@Composable internal fun ChartExplorer(metric:Metric,index:Int,select:(Int)->Unit,points:List<Sample>,events:List<EventInfo>,window:TimeWindow,
    first:Long,last:Long,live:Boolean,onWindow:(TimeWindow)->Unit,onTransform:(Double,Double,Double)->Unit,onFollow:()->Unit,onPoint:(Sample)->Unit,onEvent:(EventInfo)->Unit,onAbout:()->Unit) {
    var expanded by remember {mutableStateOf(false)}
    val confirmed=events.filter{it.state=="confirmed" && it.origin!="test" && eventWall(it) in window.from..window.to}
    @Composable fun Content(big:Boolean) {
        SurfaceCard {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(metric.label+" · "+metric.unit,fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                TextButton(onClick={expanded=!big}){Text(if(big)"Cerrar" else "Ampliar",fontSize=12.sp)}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                metrics.forEachIndexed{i,m->if(!live || m.stream in setOf("heart_rate","temperatura","acelerometro","giroscopio"))FilterChip(selected=index==i,onClick={select(i)},label={Text(m.label,fontSize=11.sp)})}
            }
            ExplorerPlot(points,metric,confirmed,window,if(big)310 else 190,
                transform=onTransform,onPoint=onPoint)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                OutlinedButton(onClick={onWindow(window.transform(1.0,.7,.5,first,last))}){Text("←",fontSize=16.sp)}
                OutlinedButton(onClick={onWindow(window.transform(.5,0.0,.5,first,last))}){Text("−",fontSize=18.sp)}
                OutlinedButton(onClick={onWindow(window.transform(2.0,0.0,.5,first,last))}){Text("+",fontSize=18.sp)}
                OutlinedButton(onClick={onWindow(window.transform(1.0,-.7,.5,first,last))}){Text("→",fontSize=16.sp)}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(10000L to "10 s",60000L to "1 min",300000L to "5 min",3600000L to "1 h").forEach{(span,label)->
                    TextButton(onClick={onWindow(TimeWindow(window.to-span,window.to).constrain(first,last))}){Text(label,fontSize=11.sp)}
                }
                TextButton(onClick={onWindow(TimeWindow(first,last.coerceAtLeast(first+1)))}){Text("Todo",fontSize=11.sp)}
                TextButton(onClick=onFollow){Text(if(live)"● En directo" else "Volver al final",fontSize=11.sp,color=Lime)}
            }
            Text("Pellizca para acercar/alejar. Arrastra para recorrer. Toca un punto para ver su medición.",fontSize=11.sp,color=Muted,lineHeight=15.sp)
            if(points.any{it.source=="minute"})Text("○ Promedio de minuto · el detalle continuo no se conserva fuera de los segmentos.",fontSize=11.sp,color=Amber,lineHeight=15.sp)
            confirmed.forEach{e->TextButton(onClick={onEvent(e)}){Text("✓ "+title(e.kind)+" · "+time(eventWall(e)),color=Danger,fontSize=12.sp)}}
            TextButton(onClick=onAbout){Text("¿Qué mide y cómo ayuda?",fontSize=12.sp)}
        }
    }
    Content(false)
    if(expanded)Dialog(onDismissRequest={expanded=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding().padding(10.dp).verticalScroll(rememberScrollState())){Content(true)}
    }
}
internal fun eventWall(e:EventInfo):Long=if(e.origin=="manual")runCatching{JSONObject(e.features).optLong("watch_wall_estimate_ms",e.time)}.getOrDefault(e.time) else e.time

@Composable private fun ExplorerPlot(points:List<Sample>,metric:Metric,events:List<EventInfo>,window:TimeWindow,height:Int,
    transform:(Double,Double,Double)->Unit,onPoint:(Sample)->Unit) {
    val colors=listOf(Lime,Mint,Color(0xFF98B9FF))
    val series=remember(points,metric){metric.keys.map{k->points.map{value(it,k)}}}
    val all=series.flatten().filterNotNull().filter{it.isFinite()}
    val low=all.minOrNull() ?: 0.0;val high=all.maxOrNull() ?: 1.0
    val pad=max((high-low)*.15,.1);val minY=low-pad;val maxY=high+pad
    val action by rememberUpdatedState(transform);val pointAction by rememberUpdatedState(onPoint)
    val currentPoints by rememberUpdatedState(points);val currentWindow by rememberUpdatedState(window)
    Text(num(high,2),fontSize=10.sp,fontFamily=Mono,color=Muted)
    Box(Modifier.fillMaxWidth().height(height.dp)) {
        Canvas(Modifier.fillMaxSize().clipToBounds().semantics{contentDescription="Gráfica de "+metric.label+", zoom y recorrido temporal"}
            .pointerInput(metric.label){awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false)
                var horizontal=false;var total=Offset.Zero;var totalZoom=1f
                do {
                    val event=awaitPointerEvent()
                    if(event.changes.any{it.isConsumed})break
                    val pan=event.calculatePan();val zoom=event.calculateZoom();total+=pan;totalZoom*=zoom
                    val multiple=event.changes.count{it.pressed}>1
                    if(!horizontal) {
                        if(!multiple && abs(total.y)>viewConfiguration.touchSlop && abs(total.y)>abs(total.x))break
                        horizontal=(abs(total.x)>viewConfiguration.touchSlop && abs(total.x)>abs(total.y)) ||
                            (multiple && abs(1-totalZoom)*event.calculateCentroidSize(useCurrent=false)>viewConfiguration.touchSlop)
                    }
                    if(horizontal && (zoom!=1f || pan!=Offset.Zero)) {
                        val center=event.calculateCentroid(useCurrent=false)
                        if(center.x.isFinite() && pan.x.isFinite() && zoom.isFinite())action(zoom.toDouble(),pan.x/size.width.toDouble(),center.x/size.width.toDouble())
                        event.changes.forEach{if(it.positionChanged())it.consume()}
                    }
                } while(event.changes.any{it.pressed})
            }}
            .pointerInput(metric.label){detectTapGestures{pos->val wall=currentWindow.from.toDouble()+currentWindow.span.toDouble()*(pos.x.toDouble()/size.width);currentPoints.minByOrNull{abs(it.wall-wall)}?.let(pointAction)}}) {
            fun x(t:Long)=((t-window.from).toDouble()/window.span*size.width).toFloat()
            fun y(v:Double)=(size.height-(v-minY)/(maxY-minY)*size.height).toFloat()
            for(i in 0..4)drawLine(Edge,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1f)
            for(i in 0..6)drawLine(Edge.copy(alpha=.5f),Offset(size.width*i/6,0f),Offset(size.width*i/6,size.height),1f)
            events.forEach{e->val at=x(eventWall(e));drawLine(Danger,Offset(at,0f),Offset(at,size.height),2.dp.toPx());drawCircle(Danger,4.dp.toPx(),Offset(at,6.dp.toPx()))}
            series.forEachIndexed{channel,values->
                val path=Path();var pen=false
                values.forEachIndexed{i,v->
                    if(v==null || !v.isFinite())pen=false else {
                        val summary=points[i].source=="minute"
                        val gap=i>0 && (points[i].wall-points[i-1].wall>(if(summary || metric.stream=="temperatura")90000 else 5000) || points[i].source!=points[i-1].source)
                        if(!pen || gap)path.moveTo(x(points[i].wall),y(v)) else path.lineTo(x(points[i].wall),y(v))
                        pen=true
                        if(summary || values.size<20)drawCircle(colors[channel%3],3.dp.toPx(),Offset(x(points[i].wall),y(v)),style=if(summary)Stroke(1.4.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
                    }
                }
                drawPath(path,colors[channel%3],style=Stroke(1.5.dp.toPx(),cap=StrokeCap.Round))
            }
        }
        if(all.isEmpty())Text("SIN MEDICIONES EN ESTA VENTANA",fontSize=11.sp,color=Muted,modifier=Modifier.align(Alignment.Center))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(time(window.from,"HH:mm:ss"),fontSize=10.sp,color=Muted);Text(time(window.to,"HH:mm:ss"),fontSize=10.sp,color=Muted)}
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){metric.keys.forEachIndexed{i,key->Text(key.substringBefore('_'),fontSize=10.sp,color=colors[i%3])}}
}

@Composable internal fun MinuteBrowser(store:Store,id:String,onRaw:(RawQuery)->Unit) {
    var open by remember(id){mutableStateOf(false)}
    var loading by remember(id){mutableStateOf(false)}
    var rows by remember(id){mutableStateOf<List<MinuteInfo>>(emptyList())}
    var expanded by remember(id){mutableStateOf<Long?>(null)}
    var page by remember(id){mutableIntStateOf(0)}
    LaunchedEffect(open,id){if(open && id.isNotBlank())while(isActive){loading=true;rows=withContext(Dispatchers.IO){store.ensureLegacyMinutes(id);store.minutes(id)};loading=false;delay(5000)}}
    SurfaceCard {
        TextButton(onClick={open=!open}){Text((if(open)"▾ " else "▸ ")+"Promedios minuto a minuto",fontSize=15.sp)}
        if(open) {
            Text("Medias de valores válidos; cada canal indica cuántas lecturas aportó. Los minutos sin señal no se rellenan.",fontSize=11.sp,color=Muted,lineHeight=16.sp)
            if(loading && rows.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!loading && rows.isEmpty())Text("Aún no hay minutos cerrados. El primer resumen aparece tras finalizar el minuto y recibir el lote.",fontSize=12.sp,color=Muted)
            val groups=rows.groupBy{it.minute}.entries.toList()
            groups.drop(page*15).take(15).forEach{(minute,items)->
                HorizontalDivider(color=Edge)
                TextButton(onClick={expanded=if(expanded==minute)null else minute}){Text((if(expanded==minute)"▾ " else "▸ ")+time(minute,"dd MMM · HH:mm")+" · "+items.sumOf{it.rows}+" muestras procesadas",fontSize=12.sp)}
                if(expanded==minute)items.forEach{row->
                    val metric=metrics.firstOrNull{it.stream==row.stream}
                    Text(metric?.label ?: row.stream,fontWeight=FontWeight.Bold,fontSize=12.sp)
                    val stats=JSONObject(row.stats)
                    if(stats.length()==0)Text("Sin lecturas válidas",fontSize=11.sp,color=Amber)
                    stats.keys().forEach{key->val s=stats.getJSONObject(key);Text(key+" · media "+num(s.optDouble("mean"),3)+" · n="+s.optLong("n")+"\nmín "+num(s.optDouble("min"),3)+" / máx "+num(s.optDouble("max"),3)+" · DE "+num(s.optDouble("sd"),3),fontSize=11.sp,lineHeight=16.sp,color=Muted)}
                    Text("Cobertura: "+time(row.first,"HH:mm:ss")+"–"+time(row.last,"HH:mm:ss"),fontSize=10.sp,color=Muted)
                    TextButton(onClick={onRaw(RawQuery(id,row.stream,minute,minute+59999))}){Text("Ver mediciones conservadas →",fontSize=11.sp)}
                }
            }
            Row {TextButton(onClick={page=(page-1).coerceAtLeast(0)},enabled=page>0){Text("Más recientes")};TextButton(onClick={page++},enabled=(page+1)*15<groups.size){Text("Más antiguos")}}
        }
    }
}

@Composable internal fun RawMeasurementsDialog(store:Store,query:RawQuery,onDismiss:()->Unit) {
    var page by remember(query){mutableIntStateOf(0)};var rows by remember(query){mutableStateOf<List<Sample>>(emptyList())};var expanded by remember(query,page){mutableIntStateOf(-1)}
    LaunchedEffect(query,page){rows=withContext(Dispatchers.IO){store.rawPage(query.session,query.stream,query.from,query.to,page)}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Mediciones · "+query.stream,fontSize=18.sp)},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(time(query.from,"dd/MM HH:mm:ss")+" – "+time(query.to,"HH:mm:ss"),fontSize=11.sp,color=Muted)
            if(rows.isEmpty())Text("No hay detalle conservado en este tramo. En modo compacto puede existir solo el promedio; no se inventan muestras.",fontSize=12.sp)
            rows.forEachIndexed{i,row->
                TextButton(onClick={expanded=if(expanded==i)-1 else i}){Text((if(expanded==i)"▾ " else "▸ ")+time(row.wall,"HH:mm:ss.SSS")+" · #"+row.index,fontSize=12.sp)}
                if(expanded==i)Text(row.file+"\n"+row.values.entries.joinToString("\n"){it.key+": "+it.value},fontFamily=Mono,fontSize=10.sp,lineHeight=15.sp)
            }
            Row{TextButton(onClick={page--},enabled=page>0){Text("Anterior")};TextButton(onClick={page++},enabled=rows.size==40){Text("Siguiente")}}
            Text("Página "+(page+1)+" · hasta 40 registros",fontSize=10.sp,color=Muted)
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Cerrar")}})
}

@Composable internal fun MeasurementCard(m:MeasurementInfo,now:Long,onClick:()->Unit) {
    SurfaceCard(onClick=onClick) {
        Text("Medición manual · "+time(m.start,"dd MMM HH:mm:ss"),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
        val remaining=((m.end-now+999)/1000).coerceIn(0,30)
        Text(when(m.status){"requested"->"Solicitando al reloj…";"open"->if(remaining>0)"Capturando · ${remaining}s restantes" else "Esperando cierre y sincronización";"closed"->"Captura finalizada · ver mediciones";"interrupted"->"Captura interrumpida · revisar cobertura";"failed"->"No se pudo iniciar: revisa el enlace y la sesión";else->"Pendiente de confirmar con el reloj"},fontSize=12.sp,color=if(m.status=="closed")Lime else Amber)
        if(m.status=="open" && remaining>0)LinearProgressIndicator(progress={1f-remaining/30f},modifier=Modifier.fillMaxWidth())
    }
}
@Composable internal fun MeasurementDetails(store:Store,m:MeasurementInfo,onDismiss:()->Unit,onRaw:(RawQuery)->Unit) {
    var stats by remember(m){mutableStateOf<List<org.pipalab.core.MinuteStats>>(emptyList())}
    LaunchedEffect(m){stats=withContext(Dispatchers.IO){store.measurementStats(m)}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Medición de 30 segundos")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        val status=when(m.status){"requested"->"Solicitada";"open"->"En curso";"closed"->"Finalizada";"interrupted"->"Interrumpida";"failed"->"No iniciada";else->"Sin confirmar"}
        Text(time(m.start,"dd MMM HH:mm:ss")+" – "+time(m.end)+"\nEstado: "+status,fontSize=12.sp)
        Text("Solo valores recibidos y válidos. No todos los sensores entregan una lectura dentro de 30 segundos.",fontSize=11.sp,color=Muted)
        if(stats.isEmpty())Text("Esperando señales conservadas",fontSize=12.sp,color=Amber)
        stats.forEach{s->Text(if(s.stream=="heart_rate")"Pulso e intervalos" else metrics.firstOrNull{it.stream==s.stream}?.label ?: s.stream,fontWeight=FontWeight.Bold,fontSize=13.sp);s.channels.forEach{(k,v)->Text(k+" · "+num(v.mean,3)+" · n="+v.count,fontSize=12.sp,color=Muted)};TextButton(onClick={onRaw(RawQuery(m.session,s.stream,m.start,m.end))}){Text("Abrir cada medición →",fontSize=11.sp)}}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Cerrar")}})
}
