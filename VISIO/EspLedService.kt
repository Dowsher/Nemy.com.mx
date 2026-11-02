@file:Suppress("MissingPermission", "DEPRECATION")
package org.pipalab.phone
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.pipalab.core.LedProtocol
import org.pipalab.core.LocalProtocol
import java.util.UUID

data class EspDevice(val address:String,val name:String,val rssi:Int)
data class EspStatus(val text:String="Placa sin configurar",val ready:Boolean=false,val devices:List<EspDevice> = emptyList(),val last:String="")
object EspLive { val state=MutableStateFlow(EspStatus()); @Volatile var serviceRunning=false }
object EspTrigger {
    fun pulse(context:Context,event:String,source:String) {
        val prefs=context.getSharedPreferences("prefs",Context.MODE_PRIVATE)
        if(source=="automatic" && !prefs.getBoolean("esp_automatic",true)) return
        if(source=="demo_sound" && !prefs.getBoolean("audio_demo",false))return
        val duration=prefs.getInt("esp_duration",5000).coerceIn(100,10000)
        val id=UUID.randomUUID().toString()
        Store.get(context).ledRequest(id,event,source,duration)
        if(!EspLive.serviceRunning || !EspLive.state.value.ready) {
            Store.get(context).ledResult(id,"not_connected","No se envió: placa desconectada")
            EspLive.state.value=EspLive.state.value.copy(last="Sin envío · placa desconectada")
            return
        }
        runCatching { context.startService(Intent(context,EspLedService::class.java).setAction("PULSE").putExtra("id",id).putExtra("duration",duration).putExtra("source",source).putExtra("created",SystemClock.elapsedRealtime())) }
            .onFailure{Store.get(context).ledResult(id,"not_sent","No se pudo iniciar el envío")}
    }
}
class EspLedService:Service() {
    private lateinit var worker:HandlerThread
    private lateinit var h:Handler
    private lateinit var adapter:BluetoothAdapter
    private lateinit var store:Store
    private var gatt:BluetoothGatt?=null
    private var rx:BluetoothGattCharacteristic?=null
    private var ready=false
    private var scanning=false
    private var address=""
    private var discovering=false
    private var active:Command?=null
    private val queue=ArrayDeque<Command>()
    private data class Command(val id:String,val duration:Int,val source:String,val created:Long)
    private val scan=object:ScanCallback() {
        override fun onScanResult(type:Int,result:ScanResult) { h.post {
            val found=EspDevice(result.device.address,result.scanRecord?.deviceName ?: "VisioLED",result.rssi)
            val devices=(EspLive.state.value.devices.filterNot{it.address==found.address}+found).sortedByDescending{it.rssi}
            EspLive.state.value=EspLive.state.value.copy(devices=devices,text="Selecciona tu placa")
        } }
        override fun onScanFailed(code:Int) { h.post { status("No se pudo buscar: "+code) } }
    }
    private fun status(text:String) { EspLive.state.value=EspLive.state.value.copy(text=text,ready=ready) }
    private val bond=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            val device=intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            h.post {
                if(device.address!=address) return@post
                when(device.bondState) {
                    BluetoothDevice.BOND_BONDED -> discover()
                    BluetoothDevice.BOND_NONE -> { ready=false; status("Vinculación pendiente. Revisa el código de la placa.") }
                }
            }
        }
    }
    private val cb=object:BluetoothGattCallback() {
        override fun onConnectionStateChange(g:BluetoothGatt,code:Int,new:Int) { h.post {
            if(g!==gatt) return@post
            if(code==0 && new==BluetoothProfile.STATE_CONNECTED) {
                if(g.device.bondState==BluetoothDevice.BOND_BONDED) discover()
                else { status("Introduce en Android el código del monitor serie de la placa");g.device.createBond() }
            } else if(new==BluetoothProfile.STATE_DISCONNECTED || code!=0) {
                disconnect("Placa desconectada",false)
                if(address.isNotBlank()) h.postDelayed({if(EspLive.serviceRunning && gatt==null)connect(address)},5000)
            }
        } }
        override fun onServicesDiscovered(g:BluetoothGatt,code:Int) { h.post {
            if(g!==gatt) return@post
            val service=g.getService(LedProtocol.SERVICE); rx=service?.getCharacteristic(LedProtocol.RX)
            val tx=service?.getCharacteristic(LedProtocol.TX)
            if(code!=0 || rx==null || tx==null) { disconnect("Instala el firmware VisioLED compatible",false);return@post }
            g.setCharacteristicNotification(tx,true)
            val ccc=tx.getDescriptor(LocalProtocol.CCC) ?: return@post disconnect("Canal de confirmación ausente",false)
            ccc.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if(!g.writeDescriptor(ccc)) disconnect("No se pudo abrir el canal",false)
        } }
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,code:Int) { h.post {
            if(g!==gatt) return@post
            if(code==0) {ready=true;status("ESP32-S3 listo · vínculo cifrado")} else disconnect("Vuelve a vincular la placa en Android",false)
        } }
        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,code:Int) { h.post {
            if(g===gatt && code!=0) finish("not_sent","La escritura Bluetooth falló: "+code)
        } }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) { receive(g,value.copyOf()) }
        @Deprecated("API legacy") override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) { if(Build.VERSION.SDK_INT<33)receive(g,c.value.copyOf()) }
    }
    private fun receive(g:BluetoothGatt,bytes:ByteArray) { h.post {
        if(g!==gatt) return@post
        runCatching {
            val ack=LedProtocol.ack(bytes); val command=active ?: return@runCatching
            if(ack.id.toString()!=command.id) return@runCatching
            when(ack.status) {
                0 -> finish("acknowledged","Orden recibida · salida LED activada")
                1 -> finish("duplicate","Orden ya aplicada · no se repitió")
                2 -> finish("not_configured","Configura el GPIO y tipo de LED en el firmware")
                else -> finish("device_error","La placa reportó un error de salida")
            }
        }
    } }
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate();store=Store.get(this)
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("esp-led","Enlace ESP32-S3",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,91,Intent(this,EspLedService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"esp-led").setSmallIcon(R.drawable.ic_lab).setContentTitle("Visio · ESP32-S3")
            .setContentText("LED para nuevas detecciones").setContentIntent(Notices.open(this)).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Desconectar placa",stop).build()).build()
        startForeground(91,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        worker=HandlerThread("PipaLed").apply{start()};h=Handler(worker.looper)
        adapter=(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        EspLive.serviceRunning=true
        ContextCompat.registerReceiver(this,bond,IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int {
        when(intent?.action) {
            "SCAN" -> h.post{beginScan()}
            "CONNECT" -> {
                val selected=intent.getStringExtra("address") ?: getSharedPreferences("prefs",MODE_PRIVATE).getString("esp_address","").orEmpty()
                if(BluetoothAdapter.checkBluetoothAddress(selected)) h.post{connect(selected)} else h.post{beginScan()}
            }
            "PULSE" -> h.post {
                val key=intent.getStringExtra("id") ?: return@post
                if(!ready || queue.size>=8) {store.ledResult(key,"not_sent","La placa no está lista");return@post}
                queue.addLast(Command(key,intent.getIntExtra("duration",5000).coerceIn(100,10000),intent.getStringExtra("source").orEmpty(),intent.getLongExtra("created",0)));pump()
            }
            "STOP" -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun beginScan() {
        if(!adapter.isEnabled) {status("Activa Bluetooth");return}
        stopScan()
        EspLive.state.value=EspLive.state.value.copy(devices=emptyList())
        status("Buscando placas VisioLED…");scanning=true
        val scanner=adapter.bluetoothLeScanner ?: return status("Escáner Bluetooth no disponible")
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(LedProtocol.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan)
        h.postDelayed({stopScan();if(!ready)status(if(EspLive.state.value.devices.isEmpty())"Sin placas visibles. Puedes conectarla después." else "Selecciona tu placa")},15000)
    }
    private fun stopScan() {if(scanning) {adapter.bluetoothLeScanner?.stopScan(scan);scanning=false}}
    private fun connect(selected:String) {
        stopScan();disconnect("Conectando ESP32-S3…",true)
        address=selected
        getSharedPreferences("prefs",MODE_PRIVATE).edit().putString("esp_address",selected).apply()
        gatt=adapter.getRemoteDevice(selected).connectGatt(this,false,cb,BluetoothDevice.TRANSPORT_LE)
    }
    private fun discover() {if(!discovering){discovering=true;gatt?.discoverServices()}}
    private fun pump() {
        if(active!=null || queue.isEmpty() || !ready) return
        val command=queue.removeFirst();active=command
        if(command.source=="demo_sound" && (!getSharedPreferences("prefs",0).getBoolean("audio_demo",false) || !PhoneLive.state.value.recording || SystemClock.elapsedRealtime()-command.created !in 0..2000)) {
            finish("cancelled","Demo desactivada o impulso caducado");return
        }
        val r=rx ?: return finish("not_sent","Canal no disponible")
        r.value=LedProtocol.command(UUID.fromString(command.id),command.duration)
        r.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if(gatt?.writeCharacteristic(r)!=true) {finish("not_sent","No se pudo enviar");return}
        h.postDelayed({if(active?.id==command.id)finish("timeout","Sin confirmación de la placa")},7000)
    }
    private fun finish(result:String,detail:String) {
        val command=active ?: return
        store.ledResult(command.id,result,detail);active=null
        EspLive.state.value=EspLive.state.value.copy(last=detail)
        pump()
    }
    private fun disconnect(reason:String,clearAddress:Boolean) {
        ready=false;discovering=false
        active?.let{store.ledResult(it.id,"disconnected","Sin confirmación: enlace interrumpido")};active=null
        queue.forEach{store.ledResult(it.id,"not_sent","No se reenvían eventos antiguos")};queue.clear()
        val old=gatt;gatt=null;old?.disconnect();old?.close();rx=null
        if(clearAddress)address=""
        status(reason)
    }
    override fun onDestroy() {
        EspLive.serviceRunning=false
        unregisterReceiver(bond)
        h.post{stopScan();disconnect("Enlace ESP32-S3 apagado",true);h.removeCallbacksAndMessages(null);worker.quitSafely()}
        super.onDestroy()
    }
}
