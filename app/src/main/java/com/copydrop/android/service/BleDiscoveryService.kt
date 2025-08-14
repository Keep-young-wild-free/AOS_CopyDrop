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
                    updateNotification("Mac 발견! Wi-Fi 검색 시작...")
                    
                    // BLE 연결 성공 시 Wi-Fi 검색 시작
                    serviceScope.launch {
                        startWifiDiscovery()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateNotification("Mac과 연결 끊김")
                    gatt.close()
                    stopSelf()
                }
            }
        }
    }
    
    private suspend fun startWifiDiscovery() {
        try {
            // NetworkManager를 사용하여 Wi-Fi에서 Mac 서버 찾기
            val networkManager = com.copydrop.android.network.NetworkManager(this@BleDiscoveryService)
            
            // 1단계: Bonjour/mDNS로 검색
            updateNotification("Bonjour로 Mac 검색 중...")
            var serverAddress = networkManager.findServerByBonjour()
            
            // 2단계: 브로드캐스트로 검색
            if (serverAddress == null) {
                updateNotification("브로드캐스트로 Mac 검색 중...")
                serverAddress = networkManager.findServerByBroadcast()
            }
            
            // 3단계: IP 스캔
            if (serverAddress == null) {
                updateNotification("네트워크 스캔 중...")
                val result = networkManager.startDiscoveryWithDetails()
                serverAddress = result.address
            }
            
            if (serverAddress != null) {
                updateNotification("Mac 발견! 클립보드 동기화 시작...")
                
                // 세션 정보 저장 (24시간 유효)
                val prefs = getSharedPreferences("copydrop", MODE_PRIVATE)
                val exp = System.currentTimeMillis() / 1000 + 24 * 60 * 60 // 24시간 후
                prefs.edit()
                    .putLong("session_exp", exp)
                    .apply()
                
                // ClipboardSyncService 시작
                val svcIntent = Intent(this@BleDiscoveryService, ClipboardSyncService::class.java).apply {
                    action = ClipboardSyncService.ACTION_START_SYNC
                    putExtra(ClipboardSyncService.EXTRA_MAC_ADDRESS, serverAddress)
                    putExtra(ClipboardSyncService.EXTRA_MAC_PORT, 8080)
                    putExtra(ClipboardSyncService.EXTRA_SESSION_EXP, exp)
                }
                startForegroundService(svcIntent)
            } else {
                updateNotification("Mac 서버를 찾을 수 없음")
            }
            
            stopSelf()
        } catch (e: Exception) {
            updateNotification("Wi-Fi 검색 실패: ${e.message}")
            stopSelf()
        }
    }
}
