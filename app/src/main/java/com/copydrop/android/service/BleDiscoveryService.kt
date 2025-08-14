package com.copydrop.android.service

import android.app.Notification
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.copydrop.android.CopyDropApplication
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import android.os.ParcelUuid

class BleDiscoveryService : Service() {
    private val serviceUUID = UUID.fromString("12345678-1234-5678-9ABC-DEF012345678")
    private val charUUID = UUID.fromString("87654321-4321-6789-0ABC-DEF987654321")
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START_SCAN = "start_scan"
        const val ACTION_STOP_SCAN = "stop_scan"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> {
                startForegroundService()
                startScan()
            }
            ACTION_STOP_SCAN -> {
                stopScan()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScan()
        gatt?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = createNotification("Mac 찾는 중...")
        startForeground(CopyDropApplication.BLE_DISCOVERY_NOTIFICATION_ID, notification)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CopyDropApplication.BLE_DISCOVERY_CHANNEL_ID)
            .setContentTitle("CopyDrop BLE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(CopyDropApplication.BLE_DISCOVERY_NOTIFICATION_ID, notification)
    }

    private fun startScan() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        scanning = true
        updateNotification("Mac 찾는 중...")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        if (scanning) {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            scanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            updateNotification("Mac 발견! 연결 중...")
            stopScan()
            gatt = result.device.connectGatt(this@BleDiscoveryService, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            updateNotification("스캔 실패: $errorCode")
            stopSelf()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateNotification("Mac에 연결됨. 서비스 발견 중...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateNotification("Mac과 연결 끊김")
                    gatt.close()
                    stopSelf()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUUID)
                val char = service?.getCharacteristic(charUUID)
                
                if (char != null) {
                    updateNotification("데이터 읽는 중...")
                    gatt.readCharacteristic(char)
                } else {
                    updateNotification("서비스 특성을 찾을 수 없음")
                    stopSelf()
                }
            } else {
                updateNotification("서비스 발견 실패: $status")
                stopSelf()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    val offerJson = String(characteristic.value, Charsets.UTF_8)
                    val json = JSONObject(offerJson)
                    
                    val ws = json.getString("ws") // e.g. ws://192.168.0.12:8080/ws
                    val keyB64 = json.getString("key_b64")
                    val exp = json.getLong("exp") // epoch seconds

                    // Parse IP and port
                    val wsInfo = ws.removePrefix("ws://").removeSuffix("/ws").split(":")
                    val ip = wsInfo[0]
                    val port = wsInfo[1].toInt()

                    // Persist key/expiry for 24h session
                    val prefs = getSharedPreferences("copydrop", MODE_PRIVATE)
                    prefs.edit()
                        .putString("session_key_b64", keyB64)
                        .putLong("session_exp", exp)
                        .apply()

                    updateNotification("인증 성공! 클립보드 동기화 시작...")

                    // Launch ClipboardSyncService with address/port
                    val svcIntent = Intent(this@BleDiscoveryService, ClipboardSyncService::class.java).apply {
                        action = ClipboardSyncService.ACTION_START_SYNC
                        putExtra(ClipboardSyncService.EXTRA_MAC_ADDRESS, ip)
                        putExtra(ClipboardSyncService.EXTRA_MAC_PORT, port)
                        putExtra(ClipboardSyncService.EXTRA_KEY_B64, keyB64)
                        putExtra(ClipboardSyncService.EXTRA_SESSION_EXP, exp)
                    }
                    startForegroundService(svcIntent)

                    // Done
                    stopSelf()
                } catch (e: Exception) {
                    updateNotification("데이터 파싱 실패: ${e.message}")
                    stopSelf()
                }
            } else {
                updateNotification("데이터 읽기 실패: $status")
                stopSelf()
            }
        }
    }
}
