package com.videobeam

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.net.Inet4Address
import java.net.NetworkInterface

data class Vid(val id: Long, val name: String, val size: Long, val mime: String, val uri: Uri)

object Scanner {
    /** Lists every video MediaStore knows about (internal storage + SD card). */
    fun scan(ctx: Context): List<Vid> {
        val out = ArrayList<Vid>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE
        )
        ctx.contentResolver.query(base, cols, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Vid(id, c.getString(1) ?: "video_$id", c.getLong(2), c.getString(3) ?: "video/mp4",
                    ContentUris.withAppendedId(base, id))
            }
        }
        return out
    }

    /** Wi-Fi or hotspot IPv4 address of this phone. */
    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "unknown"
    } catch (e: Exception) { "unknown" }
}
