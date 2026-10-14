package nl.stacklines.tvambilight

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Mini-webserver op de tv. De telefoon opent http://<tv-ip>:<poort>/ via de QR-code
 * en krijgt de editor (assets/editor.html). Die praat met de /api-adressen hieronder.
 * Local keys van de lampen gaan nooit terug naar de telefoon.
 */
class WebServer(private val ctx: Context) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    var port = 0
        private set

    fun start(): Boolean {
        if (running) return true
        for (p in listOf(8790, 8791, 8792, 0)) {
            try { serverSocket = ServerSocket(p).also { it.soTimeout = 1000 }; port = serverSocket!!.localPort; break } catch (_: Exception) { }
        }
        val ss = serverSocket ?: return false
        running = true
        Thread({
            while (running) {
                try {
                    val c = ss.accept()
                    Thread({ handle(c) }, "web-client").apply { isDaemon = true; start() }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (_: Exception) { }
            }
        }, "web-server").apply { isDaemon = true; start() }
        return true
    }

    fun stop() { running = false; try { serverSocket?.close() } catch (_: Exception) { } }

    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return null
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 8000
            val inp = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(inp) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
            var len = 0
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val body = if (len > 0) {
                val buf = ByteArray(len.coerceAtMost(2_000_000)); var read = 0
                while (read < buf.size) { val n = inp.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""
            val out = c.getOutputStream()
            fun send(status: String, type: String, content: ByteArray) {
                out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${content.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(content); out.flush()
            }
            fun json(o: Any) = send("200 OK", "application/json; charset=utf-8", o.toString().toByteArray())
            fun msg(ok: Boolean, text: String) = json(JSONObject().put("ok", ok).put("message", text))

            try {
                val trimmed = body.trim()
                val req = if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else "{}"
                when {
                    method == "GET" && path in STATIC -> {
                        val name = if (path == "/") "index.html" else path.removePrefix("/")
                        send("200 OK", STATIC.getValue(path), ctx.assets.open(name).use { it.readBytes() })
                    }
                    method == "GET" && path == "/qr.png" -> {
                        val bmp = QrCode.encode(phoneLink() ?: "geen netwerk", 360)
                        val bos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                        send("200 OK", "image/png", bos.toByteArray())
                    }
                    path == "/api/state" -> json(state())
                    path == "/api/save" && method == "POST" -> { save(JSONObject(req)); msg(true, "Opgeslagen") }
                    path == "/api/import" && method == "POST" -> msg(true, import(req))
                    path == "/api/identify" && method == "POST" -> {
                        if (Engine.running) msg(false, "Stop eerst de sync") else { Engine.blink(JSONObject(req).getString("id")); msg(true, "Lamp knippert") }
                    }
                    path == "/api/test" && method == "POST" -> {
                        val id = JSONObject(req).optString("id")
                        when {
                            Engine.running -> msg(false, "Stop eerst de sync")
                            id.isBlank() -> { Engine.testAll(); msg(true, "Alle lampen worden na elkaar getest") }
                            else -> { Engine.test(id); msg(true, "Rood, groen, blauw, wit, en dan terug") }
                        }
                    }
                    path == "/api/color" && method == "POST" -> {
                        val j = JSONObject(req)
                        if (Engine.running) msg(false, "Stop eerst de sync")
                        else { Engine.setColor(j.getString("id"), android.graphics.Color.parseColor(j.getString("color"))); msg(true, "Kleur gezet") }
                    }
                    path == "/api/sync" && method == "POST" -> {
                        val on = JSONObject(req).optBoolean("on")
                        Store.syncWanted = on; Store.save()
                        if (on) Engine.start() else Engine.stop()
                        msg(true, if (on) "Sync gestart" else "Sync gestopt")
                    }
                    path == "/api/pair/request" && method == "POST" -> {
                        JSONObject(req).optString("host").ifBlank { null }?.let { Store.tvHost = it; Store.save() }
                        val r = PhilipsTv.pairRequest()
                        msg(r == "OK", if (r == "OK") "Er staat nu een pincode op de tv" else r)
                    }
                    path == "/api/pair/grant" && method == "POST" -> {
                        val r = PhilipsTv.pairGrant(JSONObject(req).optString("pin").trim())
                        msg(r == "OK", if (r == "OK") "Tv gekoppeld" else r)
                    }
                    path == "/api/cloud" && method == "POST" -> {
                        val j = JSONObject(req)
                        j.optString("id").trim().ifBlank { null }?.let { Store.cloudId = it }
                        j.optString("secret").trim().ifBlank { null }?.let { Store.cloudSecret = it }
                        j.optString("region").ifBlank { null }?.let { Store.cloudRegion = it }
                        Store.save()
                        try { msg(true, TuyaCloud.refresh()) } catch (e: Exception) { msg(false, "Tuya weigerde: ${e.message}") }
                    }
                    path == "/api/cloud/refresh" && method == "POST" -> {
                        try { msg(true, TuyaCloud.refresh()) } catch (e: Exception) { msg(false, "Ophalen mislukt: ${e.message}") }
                    }
                    path == "/api/lamp/delete" && method == "POST" -> {
                        val id = JSONObject(req).getString("id")
                        synchronized(Store) { Store.lamps.removeAll { it.id == id } }
                        Store.save(); Engine.syncWorkers(); msg(true, "Lamp verwijderd")
                    }
                    else -> send("404 Not Found", "text/plain", "Niet gevonden".toByteArray())
                }
            } catch (e: Exception) {
                msg(false, "Fout: ${e.message}")
            }
        }
    }

    private fun state(): JSONObject {
        val lamps = JSONArray()
        for (l in Store.lampsSnapshot()) {
            val w = Engine.workers[l.id]
            lamps.put(l.toJson(false).apply {
                put("online", w?.online == true)
                put("color", "#%06x".format((w?.shownColor ?: 0x555555) and 0xffffff))
                put("supported", l.supported)
            })
        }
        val sample = Engine.lastSample
        return JSONObject().apply {
            put("layout", Store.layout)
            put("lamps", lamps)
            put("settings", Store.settings.toJson())
            put("tv", JSONObject().put("paired", Store.tvPaired).put("host", Store.tvHost).put("status", Engine.tvStatus))
            put("sync", Engine.running)
            put("cloud", JSONObject().put("configured", Store.cloudId != null && Store.cloudSecret != null).put("region", Store.cloudRegion))
            phoneLink()?.let { put("link", it) }
            if (sample != null) put("top", JSONArray().apply { sample.top.forEach { put("#%02x%02x%02x".format(it[0], it[1], it[2])) } })
        }
    }

    private fun save(o: JSONObject) {
        o.optJSONObject("layout")?.let { Store.layout = it }
        o.optJSONObject("settings")?.let { Store.settings.apply(it) }
        o.optJSONArray("lamps")?.let { a ->
            synchronized(Store) {
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    val l = Store.lamps.firstOrNull { it.id == j.optString("id") } ?: continue
                    l.name = j.optString("name", l.name).ifBlank { l.name }
                    l.x = j.optDouble("x", l.x); l.y = j.optDouble("y", l.y)
                    l.height = j.optString("height", l.height)
                    l.enabled = j.optBoolean("enabled", l.enabled)
                    j.optString("ip").takeIf { it.isNotBlank() }?.let { l.ip = it }
                }
            }
        }
        Store.save()
        Engine.syncWorkers()
    }

    /** Neemt een devices.json van tinytuya over (lijst met id, key, name, ip), eventueel met tv-gegevens. */
    private fun import(text: String): String {
        val arr: JSONArray
        var tvNote = ""
        if (text.startsWith("[")) arr = JSONArray(text) else {
            val o = JSONObject(text)
            arr = o.optJSONArray("devices") ?: JSONArray()
            o.optJSONObject("tv")?.let { t ->
                Store.tvUser = t.optString("device_id").ifBlank { null }
                Store.tvPass = t.optString("auth_key").ifBlank { null }
                t.optString("host").ifBlank { null }?.let { Store.tvHost = it }
                tvNote = " Tv-koppeling overgenomen."
            }
        }
        var added = 0; var updated = 0
        synchronized(Store) {
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val id = d.optString("id"); val key = d.optString("key")
                if (id.isBlank() || key.length != 16) continue
                val ex = Store.lamps.firstOrNull { it.id == id }
                if (ex != null) {
                    ex.key = key
                    d.optString("ip").ifBlank { null }?.let { ex.ip = it }
                    d.optString("version").ifBlank { null }?.let { ex.version = it }
                    updated++
                } else {
                    Store.lamps += Lamp(id, key, d.optString("name", id), d.optString("ip"), d.optString("version", "3.3").ifBlank { "3.3" })
                    added++
                }
            }
        }
        Store.save(); Engine.syncWorkers()
        return "$added lamp(en) toegevoegd, $updated bijgewerkt.$tvNote"
    }

    /** Adres dat de telefoon opent (via de QR-code). */
    fun phoneLink(): String? = localIp()?.let { "http://$it:$port/" }

    companion object {
        private val STATIC = mapOf(
            "/" to "text/html; charset=utf-8", "/index.html" to "text/html; charset=utf-8",
            "/tv.html" to "text/html; charset=utf-8", "/room.js" to "application/javascript; charset=utf-8",
            "/ui.css" to "text/css; charset=utf-8"
        )

        fun localIp(): String? {
            try {
                var fallback: String? = null
                for (ni in NetworkInterface.getNetworkInterfaces() ?: return null) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (a.isSiteLocalAddress) return ip
                        if (fallback == null) fallback = ip
                    }
                }
                return fallback
            } catch (_: Exception) { return null }
        }
    }
}
