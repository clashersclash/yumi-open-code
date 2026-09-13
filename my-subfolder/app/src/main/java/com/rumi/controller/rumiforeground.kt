package com.rumi.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class RumiForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "rumi_bridge_channel"
        val channel = NotificationChannel(
            channelId, 
            "Rumi Bridge Service", 
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        // Switched to native Notification.Builder to avoid AndroidX dependency crashes
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Rumi Bridge Active")
            .setContentText("Instant hardware & Spotify routing enabled")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)
    }

    // Fixed the return type from START_STICKY to Int
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}