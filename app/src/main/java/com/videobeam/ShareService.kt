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
        @Volatile var running = false
        @Volatile var videos: List<Vid> = emptyList()
    }

    private var server: VideoServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("share", "Sharing", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "share")
            .setContentTitle("VideoBeam is sharing your videos")
            .setContentText("http://${Scanner.localIp()}:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (server == null) {
            try {
                val s = VideoServer(this, PORT)
                s.start()
                server = s
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videobeam:share").apply { acquire() }
                @Suppress("DEPRECATION")
                wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "videobeam").apply { acquire() }
                Thread { videos = Scanner.scan(this) }.start()
                running = true
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null
        wake?.release(); wifi?.release()
        running = false
        super.onDestroy()
    }
}
