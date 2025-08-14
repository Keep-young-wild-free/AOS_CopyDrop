package com.copydrop.android.service

import android.app.Notification
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.copydrop.android.CopyDropApplication
import com.copydrop.android.network.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Mac CopyDrop과 호환되는 WebSocket 기반 클립보드 동기화 서비스
 */
class ClipboardSyncService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var clipboardManager: ClipboardManager
    
    private var isServiceRunning = false
    private var lastClipboardHash: String? = null
    
    companion object {
        const val ACTION_START_SYNC = "start_sync"
        const val ACTION_STOP_SYNC = "stop_sync"
        const val EXTRA_MAC_ADDRESS = "mac_address"
        const val EXTRA_MAC_PORT = "mac_port"
    }
    
    override fun onCreate() {
        super.onCreate()
        webSocketManager = WebSocketManager(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // 클립보드 변경 리스너 등록
        clipboardManager.addPrimaryClipChangedListener {
            handleClipboardChange()
        }
        
        // WebSocket으로 받은 클립보드 데이터 처리
        serviceScope.launch {
            webSocketManager.receivedClipboard.collect { clipboardText ->
                if (clipboardText != null) {
                    setClipboard(clipboardText)
                }
            }
        }
        
        // 연결 상태 모니터링
        serviceScope.launch {
            webSocketManager.connectionState.collect { state ->
                val statusText = when (state) {
                    WebSocketManager.ConnectionState.DISCONNECTED -> "연결 끊김"
                    WebSocketManager.ConnectionState.CONNECTING -> "연결 중..."
                    WebSocketManager.ConnectionState.CONNECTED -> "Mac과 연결됨"
                    WebSocketManager.ConnectionState.RECONNECTING -> "재연결 시도 중..."
                }
                updateNotification(statusText)
            }
        }
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
     * WebSocket 연결 및 동기화 시작
     */
    private fun startSync(macAddress: String, macPort: Int) {
        if (isServiceRunning) return
        
        serviceScope.launch {
            try {
                isServiceRunning = true
                webSocketManager.connect(macAddress, macPort)
            } catch (e: Exception) {
                e.printStackTrace()
                updateNotification("연결 실패: ${e.message}")
            }
        }
    }
    
    /**
     * 동기화 중지
     */
    private fun stopSync() {
        isServiceRunning = false
        webSocketManager.disconnect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
    
    /**
     * 클립보드 변경 처리
     */
    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
            
            if (!text.isNullOrEmpty()) {
                val hash = sha256(text)
                if (hash != lastClipboardHash) {
                    lastClipboardHash = hash
                    webSocketManager.sendClipboard(text)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 클립보드에 텍스트 설정
     */
    private fun setClipboard(text: String) {
        try {
            val hash = sha256(text)
            if (hash != lastClipboardHash) {
                lastClipboardHash = hash
                val clip = ClipData.newPlainText("CopyDrop", text)
                clipboardManager.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 알림 생성
     */
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CopyDropApplication.CLIPBOARD_SYNC_CHANNEL_ID)
            .setContentTitle("CopyDrop")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }
    
    /**
     * 알림 업데이트
     */
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(CopyDropApplication.CLIPBOARD_SYNC_NOTIFICATION_ID, notification)
    }
    
    /**
     * SHA-256 해시 계산
     */
    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format("%02x", it) }
        return "sha256:$hex"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webSocketManager.cleanup()
        serviceScope.cancel()
    }
}