package com.videobeam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var shareInfo: TextView
    private lateinit var shareBtn: Button
    private lateinit var urlBox: EditText
    private lateinit var recvInfo: TextView
    private lateinit var diag: TextView
    private lateinit var permBtn: Button
    private var msg = ""
    private lateinit var adapter: ArrayAdapter<String>

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 8.dp())
        }
        status = TextView(this).apply { textSize = 16f; text = "Tap Scan to list your videos" }
        shareInfo = TextView(this).apply { textSize = 15f }
        diag = TextView(this).apply { textSize = 12f; alpha = 0.7f }
        permBtn = Button(this).apply {
            text = "Open app permission settings"
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        shareBtn = Button(this).apply { setOnClickListener { toggleShare() } }
        val scanBtn = Button(this).apply { text = "Scan videos"; setOnClickListener { scan() } }
        urlBox = EditText(this).apply { hint = "Sender address, e.g. 192.168.1.5:8080"; setSingleLine() }
        val recvBtn = Button(this).apply { text = "Receive all videos from sender"; setOnClickListener { receive() } }
        recvInfo = TextView(this)
        val list = ListView(this).apply { adapter = this@MainActivity.adapter }
        listOf(status, scanBtn, shareBtn, shareInfo, permBtn, diag, urlBox, recvBtn, recvInfo).forEach { root.addView(it) }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        requestPermissions(perms(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = scan()

    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun canRead() = checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun fmt(b: Long) = if (b >= (1L shl 30)) "%.2f GB".format(b / 1073741824.0) else "${b shr 20} MB"
    private fun ui(t: String) = runOnUiThread { recvInfo.text = t }

    private fun scan() {
        if (!canRead()) { status.text = "Allow video access to continue"; return }
        status.text = "Scanning…"
        Thread {
            val v = Scanner.scan(this)
            ShareService.videos = v
            runOnUiThread {
                adapter.clear()
                adapter.addAll(v.map { "${it.name}  •  ${fmt(it.size)}" })
                status.text = "${v.size} videos • ${fmt(v.sumOf { it.size })}"
            }
        }.start()
    }

    private val tick = object : Runnable {
        override fun run() { refreshShare(); shareBtn.postDelayed(this, 1000) }
    }

    override fun onResume() { super.onResume(); tick.run() }
    override fun onPause() { super.onPause(); shareBtn.removeCallbacks(tick) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun toggleShare() {
        if (ShareService.fallback != null) {
            ShareService.fallback?.stop(); ShareService.fallback = null
            ShareService.running = false
            msg = "Sharing stopped"
            return
        }
        if (ShareService.running) {
            stopService(Intent(this, ShareService::class.java))
            msg = "Sharing stopped"
            return
        }
        if (!canRead()) {
            msg = "Video access is OFF. Tap the Open app permission settings button below, then allow Photos and videos (or Storage)."
            requestPermissions(perms(), 1)
            return
        }
        msg = "Starting…"
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
        try {
            ShareService.error = null
            startForegroundService(Intent(this, ShareService::class.java))
        } catch (e: Exception) {
            ShareService.error = e.message ?: e.toString()
        }
        shareBtn.postDelayed({
            if (!ShareService.running && ShareService.fallback == null) startFallback()
        }, 3000)
    }

    /** If the phone blocks the background service (MIUI), serve from the app itself while it stays open. */
    private fun startFallback() {
        try {
            Thread { ShareService.videos = Scanner.scan(applicationContext) }.start()
            var s: VideoServer? = null
            for (p in 8080..8099) {
                try { val v = VideoServer(applicationContext, p); v.start(); s = v; ShareService.port = p; break } catch (e: Exception) { }
            }
            if (s == null) { msg = "Could not open a network port."; return }
            ShareService.fallback = s
            ShareService.error = null
            ShareService.running = true
        } catch (e: Exception) {
            ShareService.error = e.message ?: e.toString()
        }
    }

    private fun refreshShare() {
        val on = ShareService.running
        val err = ShareService.error
        val ok = canRead()
        shareBtn.text = if (on) "Stop sharing" else "Start sharing over Wi-Fi"
        permBtn.visibility = if (ok) View.GONE else View.VISIBLE
        diag.text = "v2 • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MODEL}\nVideo access: ${if (ok) "YES" else "NO"} • Sharing: ${if (on) "ON" else "OFF"}"
        val ip = Scanner.localIp()
        shareInfo.text = when {
            on && ip == "unknown" -> "Sharing is on but no Wi-Fi address was found.\nTurn on Wi-Fi or a hotspot."
            on -> "On the other phone enter:\n$ip:${ShareService.port}\n(or open http://$ip:${ShareService.port} in a browser)"
            err != null -> "Error: $err"
            else -> msg
        }
    }

    private fun receive() {
        var base = urlBox.text.toString().trim().trimEnd('/')
        if (base.isEmpty()) return
        if (!base.startsWith("http")) base = "http://$base"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            var ok = 0
            var failed = 0
            try {
                val arr = JSONArray(URL("$base/list.json").readText())
                val total = (0 until arr.length()).sumOf { arr.getJSONObject(it).getLong("size") }
                var done = 0L
                var last = 0L
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("name")
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, o.getString("mime"))
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoBeam")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                    try {
                        val c = URL("$base/v/${o.getLong("id")}").openConnection() as HttpURLConnection
                        c.readTimeout = 30000
                        c.inputStream.use { inp ->
                            contentResolver.openOutputStream(uri)!!.use { out ->
                                val buf = ByteArray(262144)
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - last > 500) {
                                        last = now
                                        ui("Receiving ${i + 1}/${arr.length()}: $name\n${fmt(done)} / ${fmt(total)}")
                                    }
                                }
                            }
                        }
                        cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(uri, cv, null, null)
                        ok++
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        failed++
                    }
                }
                ui("Done: $ok received, $failed failed.\nSaved in Movies/VideoBeam")
            } catch (e: Exception) {
                ui("Error: ${e.message}")
            }
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }.start()
    }
}
