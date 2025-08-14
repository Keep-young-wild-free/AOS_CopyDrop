package com.copydrop.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.copydrop.android.network.NetworkManager
import kotlinx.coroutines.*

/**
 * 네트워크 연결을 관리하는 서비스
 */
class NetworkService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var networkManager: NetworkManager
    
    companion object {
        const val ACTION_START_DISCOVERY = "start_discovery"
        const val ACTION_STOP_DISCOVERY = "stop_discovery"
    }
    
    override fun onCreate() {
        super.onCreate()
        networkManager = NetworkManager(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DISCOVERY -> {
                startDiscovery()
            }
            ACTION_STOP_DISCOVERY -> {
                stopDiscovery()
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun startDiscovery() {
        serviceScope.launch {
            try {
                networkManager.startDiscovery()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun stopDiscovery() {
        networkManager.cleanup()
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        networkManager.cleanup()
    }
}
