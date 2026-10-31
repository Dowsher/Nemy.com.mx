@file:Suppress("MissingPermission", "DEPRECATION")
package org.pipalab.core

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import java.util.ArrayDeque

/** All state belongs to handler. Only authenticated, encrypted application messages leave this endpoint. */
class BleClient(private val context:Context,private val handler:Handler,private val key:ByteArray,
    private val status:(String,Boolean)->Unit,private val message:(String)->Unit) {
    private val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt:BluetoothGatt?=null
    private var rx:BluetoothGattCharacteristic?=null
    private var secure:SecureSession?=null
    private var assembler=LocalProtocol.Assembler()
    private var clientNonce=byteArrayOf()
    private var mtu=23
    private var running=false
    private var writing=false
    private val queue=ArrayDeque<ByteArray>()
    private var lastProgress=0L
    private val scanner=object:ScanCallback() {
        override fun onScanResult(type:Int,result:ScanResult) { handler.post {
            if(!running || gatt!=null) return@post
            adapter.bluetoothLeScanner?.stopScan(this)
            status("Autenticando reloj…",false)
            gatt=result.device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
            lastProgress=System.currentTimeMillis()
        } }
        override fun onScanFailed(code:Int) { handler.post { status("Bluetooth: escaneo "+code,false); handler.postDelayed({scan()},8000) } }
    }
    private val callback=object:BluetoothGattCallback() {
        override fun onConnectionStateChange(g:BluetoothGatt,s:Int,new:Int) { handler.post {
            if(g!==gatt) return@post
            if(s==BluetoothGatt.GATT_SUCCESS && new==BluetoothProfile.STATE_CONNECTED) { lastProgress=System.currentTimeMillis(); g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); g.discoverServices() }
            else if(new==BluetoothProfile.STATE_DISCONNECTED || s!=BluetoothGatt.GATT_SUCCESS) retry("Reloj fuera de alcance")
        } }
        override fun onServicesDiscovered(g:BluetoothGatt,s:Int) { handler.post {
            if(g!==gatt) return@post
            rx=g.getService(LocalProtocol.SERVICE)?.getCharacteristic(LocalProtocol.RX)
            if(s!=0 || rx==null) { retry("Servicio no disponible"); return@post }
            if(!g.requestMtu(517)) subscribe(g)
        } }
        override fun onMtuChanged(g:BluetoothGatt,value:Int,s:Int) { handler.post { if(g===gatt) { if(s==0) mtu=value; subscribe(g) } } }
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,s:Int) { handler.post {
            if(g!==gatt) return@post
            if(s!=0) { retry("No se pudo suscribir"); return@post }
            clientNonce=LocalProtocol.random(); sendFrame(LocalProtocol.hello(key,clientNonce))
        } }
        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,s:Int) { handler.post {
            if(g!==gatt) return@post
            if(s!=0) { retry("Enlace interrumpido"); return@post }
            writing=false; lastProgress=System.currentTimeMillis(); pump()
        } }
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray) { received(g,value.copyOf()) }
        @Deprecated("API legacy") override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
            if(android.os.Build.VERSION.SDK_INT<33) received(g,c.value.copyOf())
        }
    }
    private fun subscribe(g:BluetoothGatt) {
        val tx=g.getService(LocalProtocol.SERVICE)?.getCharacteristic(LocalProtocol.TX) ?: return retry("Servicio incompleto")
        g.setCharacteristicNotification(tx,true)
        val desc=tx.getDescriptor(LocalProtocol.CCC) ?: return retry("Sin canal de datos")
        desc.value=BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if(!g.writeDescriptor(desc)) retry("No se pudo abrir canal")
    }
    private fun received(g:BluetoothGatt,bytes:ByteArray) { handler.post {
        if(g!==gatt) return@post
        runCatching {
            val frame=assembler.accept(bytes) ?: return@runCatching
            lastProgress=System.currentTimeMillis()
            if(secure==null) {
                val server=LocalProtocol.verifyReply(key,clientNonce,frame)
                secure=SecureSession(key,clientNonce,server,true)
                status("Conectado · cifrado local",true)
                send("""{"type":"hello"}""")
            } else message(secure!!.decrypt(frame))
        }.onFailure { retry("No se pudo verificar el vínculo") }
    } }
    fun start() { running=true; scan(); handler.postDelayed(watchdog,5000) }
    private fun scan() {
        if(!running || gatt!=null) return
        if(!adapter.isEnabled) { status("Activa Bluetooth en el teléfono",false); handler.postDelayed({scan()},5000); return }
        status("Buscando tu reloj…",false)
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanner); adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(LocalProtocol.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanner) }
            .onFailure{status("Permite Dispositivos cercanos",false)}
    }
    fun send(text:String) { val s=secure ?: return; sendFrame(s.encrypt(text)) }
    fun priority(high:Boolean) { gatt?.requestConnectionPriority(if(high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
    private fun sendFrame(frame:ByteArray) {
        queue.addAll(LocalProtocol.packets(frame,mtu)); if(queue.size>2500) { retry("Reanudando transferencia"); return }; pump()
    }
    private fun pump() {
        if(writing || queue.isEmpty()) return
        val g=gatt ?: return; val r=rx ?: return
        r.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; r.value=queue.removeFirst()
        writing=true; lastProgress=System.currentTimeMillis()
        if(!g.writeCharacteristic(r)) retry("Reintentando envío")
    }
    private val watchdog=object:Runnable { override fun run() {
        if(!running) return
        if(gatt!=null && System.currentTimeMillis()-lastProgress>30000) retry("Reconectando…")
        handler.postDelayed(this,5000)
    } }
    private fun retry(reason:String) {
        status(reason,false); val old=gatt; gatt=null; old?.disconnect(); old?.close()
        secure=null; rx=null; queue.clear(); writing=false; assembler=LocalProtocol.Assembler(); mtu=23
        if(running) handler.postDelayed({scan()},3000)
    }
    fun stop() { running=false; handler.removeCallbacks(watchdog); runCatching { adapter.bluetoothLeScanner?.stopScan(scanner) }; retry("Desconectado"); key.fill(0) }
}
