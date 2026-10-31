@file:Suppress("MissingPermission", "DEPRECATION")
package org.pipalab.core
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import java.util.ArrayDeque

class BleServer(private val context:Context,private val handler:Handler,private val key:ByteArray,
    private val state:(Boolean)->Unit,private val message:(String)->Unit) {
    private val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter=manager.adapter
    private var server:BluetoothGattServer?=null
    private var peer:BluetoothDevice?=null
    private var secure:SecureSession?=null
    private var mtu=23
    private var assembler=LocalProtocol.Assembler()
    private val packets=ArrayDeque<ByteArray>()
    private var sending=false
    private var progress=0L
    private var running=false
    private val tx=BluetoothGattCharacteristic(LocalProtocol.TX,BluetoothGattCharacteristic.PROPERTY_INDICATE,BluetoothGattCharacteristic.PERMISSION_READ)
    private val ad=object:AdvertiseCallback() {
        override fun onStartFailure(errorCode:Int) { android.util.Log.e("PipaLink","Advertising error "+errorCode) }
    }
    private val cb=object:BluetoothGattServerCallback() {
        override fun onServiceAdded(status:Int,service:BluetoothGattService) { handler.post { if(status==0) advertise() } }
        override fun onConnectionStateChange(d:BluetoothDevice,s:Int,new:Int) { handler.post {
            if(new==BluetoothProfile.STATE_CONNECTED) {
                if(peer!=null && peer!=d) { server?.cancelConnection(d); return@post }
                peer=d; progress=System.currentTimeMillis()
            } else if(peer==d) reset()
        } }
        override fun onMtuChanged(d:BluetoothDevice,m:Int) { handler.post { if(peer==d) mtu=m } }
        override fun onDescriptorWriteRequest(d:BluetoothDevice,id:Int,desc:BluetoothGattDescriptor,prepared:Boolean,response:Boolean,offset:Int,value:ByteArray) {
            handler.post {
                val valid=peer==d && !prepared && offset==0 && desc.uuid==LocalProtocol.CCC && value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                if(response) server?.sendResponse(d,id,if(valid) 0 else BluetoothGatt.GATT_FAILURE,0,null)
            }
        }
        override fun onCharacteristicWriteRequest(d:BluetoothDevice,id:Int,c:BluetoothGattCharacteristic,prepared:Boolean,response:Boolean,offset:Int,value:ByteArray) {
            val bytes=value.copyOf(); handler.post {
                val valid=peer==d && !prepared && offset==0 && c.uuid==LocalProtocol.RX
                if(response) server?.sendResponse(d,id,if(valid) 0 else BluetoothGatt.GATT_FAILURE,0,null)
                if(!valid) return@post
                runCatching {
                    val frame=assembler.accept(bytes) ?: return@runCatching
                    progress=System.currentTimeMillis()
                    if(secure==null) {
                        val client=LocalProtocol.verifyHello(key,frame); val nonce=LocalProtocol.random()
                        secure=SecureSession(key,client,nonce,false)
                        enqueue(LocalProtocol.reply(key,client,nonce))
                        state(true)
                    } else message(secure!!.decrypt(frame))
                }.onFailure { disconnect() }
            }
        }
        override fun onNotificationSent(d:BluetoothDevice,status:Int) { handler.post {
            if(d!=peer) return@post
            if(status!=0) disconnect() else { sending=false; progress=System.currentTimeMillis(); pump() }
        } }
    }
    fun start() {
        running=true
        server=manager.openGattServer(context,cb) ?: error("GATT no disponible")
        tx.addDescriptor(BluetoothGattDescriptor(LocalProtocol.CCC,BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service=BluetoothGattService(LocalProtocol.SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(LocalProtocol.RX,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(tx); check(server!!.addService(service))
        handler.postDelayed(watchdog,5000)
    }
    private fun advertise() {
        adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(LocalProtocol.SERVICE)).setIncludeDeviceName(false).build(),ad)
            ?: error("Publicidad BLE no disponible")
    }
    /** false means live backlog was skipped; durable files remain available for synchronization. */
    fun send(text:String,live:Boolean=false):Boolean {
        val s=secure ?: return false
        if(live && packets.size>120) return false
        enqueue(s.encrypt(text)); return true
    }
    private fun enqueue(frame:ByteArray) {
        packets.addAll(LocalProtocol.packets(frame,mtu))
        if(packets.size>2500) { disconnect(); return }; pump()
    }
    private fun pump() {
        if(sending || packets.isEmpty()) return
        val d=peer ?: return
        sending=true; progress=System.currentTimeMillis(); tx.value=packets.removeFirst()
        if(server?.notifyCharacteristicChanged(d,tx,true)!=true) disconnect()
    }
    private fun reset() { peer=null; secure=null; mtu=23; packets.clear(); sending=false; assembler=LocalProtocol.Assembler(); state(false) }
    private fun disconnect() { peer?.let{server?.cancelConnection(it)}; reset() }
    private val watchdog=object:Runnable { override fun run() {
        if(!running) return
        if(peer!=null && System.currentTimeMillis()-progress>30000) disconnect()
        handler.postDelayed(this,5000)
    } }
    fun stop() { running=false; handler.removeCallbacks(watchdog); disconnect(); adapter.bluetoothLeAdvertiser?.stopAdvertising(ad); server?.close(); key.fill(0) }
}
