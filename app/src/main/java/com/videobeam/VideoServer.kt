package com.videobeam

import android.content.Context
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Tiny HTTP server: / (web page), /list.json, /v/<id> (download, supports resume via Range). */
class VideoServer(private val ctx: Context, private val port: Int) {
    @Volatile private var running = false
    private var ss: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(6)

    fun start() {
        ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        running = true
        Thread {
            while (running) {
                try { val s = ss!!.accept(); pool.execute { handle(s) } } catch (e: Exception) { if (!running) break }
            }
        }.start()
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 30000
            s.sendBufferSize = 524288
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val req = reader.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("Range:", true)) range = l.substring(6).trim()
            }
            val path = req.split(" ").getOrNull(1) ?: "/"
            val out = BufferedOutputStream(s.getOutputStream(), 262144)
            val vids = ShareService.videos
            when {
                path.startsWith("/v/") -> {
                    val id = path.substring(3).substringBefore('?').toLongOrNull()
                    val v = vids.firstOrNull { it.id == id }
                    if (v == null) reply(out, "404 Not Found", "text/plain", "Not found".toByteArray())
                    else video(out, v, range)
                }
                path.startsWith("/list.json") -> {
                    val arr = JSONArray()
                    vids.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("mime", it.mime)) }
                    reply(out, "200 OK", "application/json; charset=utf-8", arr.toString().toByteArray())
                }
                else -> reply(out, "200 OK", "text/html; charset=utf-8", page(vids).toByteArray())
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun reply(out: OutputStream, code: String, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
    }

    private fun video(out: OutputStream, v: Vid, range: String?) {
        val pfd = ctx.contentResolver.openFileDescriptor(v.uri, "r") ?: return
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ins ->
            val total = pfd.statSize
            var start = 0L
            var end = total - 1
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), end) }
                else if (b.isNotEmpty()) start = maxOf(0L, total - b.toLong())
            }
            if (start > end || start >= total) { reply(out, "416 Range Not Satisfiable", "text/plain", ByteArray(0)); return }
            val name = URLEncoder.encode(v.name, "UTF-8").replace("+", "%20")
            val head = StringBuilder(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ${v.mime}\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
            if (m != null) head.append("Content-Range: bytes $start-$end/$total\r\n")
            head.append("Content-Disposition: attachment; filename*=UTF-8''$name\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            ins.channel.position(start)
            var left = end - start + 1
            val buf = ByteArray(262144)
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun page(v: List<Vid>): String {
        val sb = StringBuilder("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>")
        sb.append("<title>VideoBeam</title><body style='font-family:sans-serif'><h3>${v.size} videos</h3>")
        sb.append("<button onclick='dlAll()'>Download all</button><ul>")
        v.forEach { sb.append("<li><a href='/v/${it.id}'>${TextUtils.htmlEncode(it.name)}</a> (${it.size shr 20} MB)</li>") }
        sb.append("</ul><script>function dlAll(){var a=document.querySelectorAll('li a'),i=0;(function n(){if(i<a.length){a[i++].click();setTimeout(n,1500)}})()}</script>")
        return sb.toString()
    }
}
