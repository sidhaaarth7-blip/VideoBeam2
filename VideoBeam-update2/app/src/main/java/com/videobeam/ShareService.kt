package com.videobeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

class ShareService : Service() {
    companion object {
        const val PORT = 8080
        @Volatile var port = PORT
        @Volatile var running = false
        @Volatile var error: String? = null
        @Volatile var videos: List<Vid> = emptyList()
        @Volatile var fallback: VideoServer? = null
    }

    private var server: VideoServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        try {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("share", "Sharing", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, "share")
                .setContentTitle("VideoBeam is sharing your videos")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true).build()
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            error = "Service start failed: " + (e.message ?: e.toString())
            stopSelf()
            return START_NOT_STICKY
        }

        if (server == null) {
            for (p in 8080..8099) {
                try {
                    val s = VideoServer(this, p)
                    s.start()
                    server = s
                    port = p
                    break
                } catch (e: Exception) {
                    error = "Could not open a network port: " + (e.message ?: e.toString())
                }
            }
            if (server == null) { stopSelf(); return START_NOT_STICKY }
            error = null
            try { wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videobeam:share").apply { acquire() } } catch (e: Exception) {}
            try {
                @Suppress("DEPRECATION")
                wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "videobeam").apply { acquire() }
            } catch (e: Exception) {}
            Thread { videos = Scanner.scan(this) }.start()
            running = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null
        try { wake?.release() } catch (e: Exception) {}
        try { wifi?.release() } catch (e: Exception) {}
        running = false
        super.onDestroy()
    }
}
