package com.copydrop.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.copydrop.android.CopyDropApplication
import com.copydrop.android.MainActivity
import com.copydrop.android.R
import com.copydrop.android.data.ClipboardData
import com.copydrop.android.data.DeviceInfo
import com.copydrop.android.network.NetworkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.*

/**
 * 클립보드 동기화를 담당하는 포어그라운드 서비스
 */
class ClipboardSyncService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var networkManager: NetworkManager
    private var isServiceRunning = false
    private var deviceId: String = ""
    
    companion object {
        private const val SYNC_INTERVAL = 2000L // 2초마다 동기화 확인
        const val ACTION_START_SYNC = "start_sync"
        const val ACTION_STOP_SYNC = "stop_sync"
        const val EXTRA_MAC_ADDRESS = "mac_address"
        const val EXTRA_MAC_PORT = "mac_port"
    }
    
    override fun onCreate() {
        super.onCreate()
        clipboardManager = ClipboardManager(this)
        networkManager = NetworkManager(this)
        deviceId = generateDeviceId()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val macAddress = intent.getStringExtra(EXTRA_MAC_ADDRESS)
                val macPort = intent.getIntExtra(EXTRA_MAC_PORT, 8080)
                
                if (macAddress != null) {
                    startForegroundService()
                    startSync(macAddress, macPort)
                }
            }
            ACTION_STOP_SYNC -> {
                stopSync()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * 포어그라운드 서비스 시작
     */
    private fun startForegroundService() {
        val notification = createNotification("클립보드 동기화 준비 중...")
        startForeground(CopyDropApplication.CLIPBOARD_SYNC_NOTIFICATION_ID, notification)
    }
    
    /**
     * 동기화 시작
     */
    private fun startSync(macAddress: String, macPort: Int) {
        if (isServiceRunning) return
        
        serviceScope.launch {
            try {
                isServiceRunning = true
                networkManager.setMacServerAddress(macAddress, macPort)
                
                // 기기 등록
                registerDevice()
                
                // 클립보드 모니터링 시작
                clipboardManager.startMonitoring()
                
                // 동기화 루프 시작
                startSyncLoop()
                
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("연결 실패: ${e.message}")
            }
        }
    }
    
    /**
     * 기기 등록
     */
    private suspend fun registerDevice() {
        try {
            val deviceInfo = DeviceInfo(
                deviceId = deviceId,
                deviceName = android.os.Build.MODEL,
                deviceType = "android",
                ipAddress = getLocalIpAddress() ?: "",
                port = 0 // 안드로이드는 클라이언트만 동작
            )
            
            val api = networkManager.getApi()
            val response = api?.registerDevice(deviceInfo)
            
            if (response?.isSuccessful == true) {
                updateNotification("Mac과 연결됨")
            } else {
                updateNotification("등록 실패")
            }
        } catch (e: Exception) {
            updateNotification("등록 오류: ${e.message}")
        }
    }
    
    /**
     * 동기화 루프
     */
    private suspend fun startSyncLoop() {
        // 로컬 클립보드 변경 감지 및 전송
        serviceScope.launch {
            clipboardManager.clipboardData.collect { clipboardData ->
                if (clipboardData != null) {
                    sendClipboardToMac(clipboardData)
                }
            }
        }
        
        // Mac으로부터 클립보드 데이터 수신
        serviceScope.launch {
            while (isServiceRunning) {
                try {
                    receiveClipboardFromMac()
                    delay(SYNC_INTERVAL)
                } catch (e: Exception) {
                    e.printStackTrace()
                    delay(SYNC_INTERVAL * 2) // 오류 시 더 긴 간격
                }
            }
        }
    }
    
    /**
     * Mac으로 클립보드 데이터 전송
     */
    private suspend fun sendClipboardToMac(clipboardData: ClipboardData) {
        try {
            val api = networkManager.getApi() ?: return
            val dataWithDeviceId = clipboardData.copy(deviceId = deviceId)
            val response = api.sendClipboard(dataWithDeviceId)
            
            if (response.isSuccessful) {
                updateNotification("클립보드 전송됨")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Mac으로부터 클립보드 데이터 수신
     */
    private suspend fun receiveClipboardFromMac() {
        try {
            val api = networkManager.getApi() ?: return
            val response = api.getClipboard()
            
            if (response.isSuccessful) {
                val clipboardData = response.body()
                if (clipboardData != null && clipboardData.deviceId != deviceId) {
                    // 다른 기기에서 온 데이터만 적용
                    clipboardManager.setClipboardContent(clipboardData.content)
                    updateNotification("클립보드 수신됨")
                }
            }
        } catch (e: Exception) {
            // 연결 오류는 로그만 남기고 계속 시도
        }
    }
    
    /**
     * 동기화 중지
     */
    private fun stopSync() {
        serviceScope.launch {
            try {
                // 기기 등록 해제
                val api = networkManager.getApi()
                api?.unregisterDevice(deviceId)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isServiceRunning = false
                clipboardManager.stopMonitoring()
                networkManager.cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    
    /**
     * 알림 생성
     */
    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CopyDropApplication.CLIPBOARD_SYNC_CHANNEL_ID)
            .setContentTitle("CopyDrop")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    /**
     * 알림 업데이트
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(CopyDropApplication.CLIPBOARD_SYNC_NOTIFICATION_ID, notification)
    }
    
    /**
     * 기기 ID 생성
     */
    private fun generateDeviceId(): String {
        return "android_" + UUID.randomUUID().toString().substring(0, 8)
    }
    
    /**
     * 로컬 IP 주소 가져오기
     */
    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        networkManager.cleanup()
    }
}
