package com.copydrop.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class CopyDropApplication : Application() {
    
    companion object {
        const val CLIPBOARD_SYNC_CHANNEL_ID = "clipboard_sync_channel"
        const val CLIPBOARD_SYNC_NOTIFICATION_ID = 1001
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val clipboardSyncChannel = NotificationChannel(
                CLIPBOARD_SYNC_CHANNEL_ID,
                "클립보드 동기화",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mac과 클립보드를 동기화합니다"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(clipboardSyncChannel)
        }
    }
}
