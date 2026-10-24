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
        shareBtn = Button(this).apply { setOnClickListener { toggleShare() } }
        val scanBtn = Button(this).apply { text = "Scan videos"; setOnClickListener { scan() } }
        urlBox = EditText(this).apply { hint = "Sender address, e.g. 192.168.1.5:8080"; setSingleLine() }
        val recvBtn = Button(this).apply { text = "Receive all videos from sender"; setOnClickListener { receive() } }
        recvInfo = TextView(this)
        val list = ListView(this).apply { adapter = this@MainActivity.adapter }
        listOf(status, scanBtn, shareBtn, shareInfo, urlBox, recvBtn, recvInfo).forEach { root.addView(it) }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        requestPermissions(perms(), 1)
        refreshShare()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = scan()
    override fun onResume() { super.onResume(); refreshShare() }

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

    private fun toggleShare() {
        if (ShareService.running) {
            stopService(Intent(this, ShareService::class.java))
        } else {
            if (!canRead()) { requestPermissions(perms(), 1); return }
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            startForegroundService(Intent(this, ShareService::class.java))
        }
        shareBtn.postDelayed({ refreshShare() }, 700)
    }

    private fun refreshShare() {
        shareBtn.text = if (ShareService.running) "Stop sharing" else "Start sharing over Wi-Fi"
        shareInfo.text = if (ShareService.running)
            "On the other phone, open VideoBeam and enter:\n${Scanner.localIp()}:${ShareService.PORT}\n(or open http://${Scanner.localIp()}:${ShareService.PORT} in any browser)"
        else ""
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
