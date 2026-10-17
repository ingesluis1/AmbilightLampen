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
    private val clients = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "web-client").apply { isDaemon = true } }
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
                    clients.execute { handle(c) }
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

    /**
     * Eén verzoek afhandelen. Een verbinding die wegvalt (de pagina breekt een verzoek af, telefoon gaat op slot)
     * mag nooit de app laten crashen: alle fouten hier worden opgevangen.
     */
    private fun handle(client: Socket) {
        try { handleUnsafe(client) } catch (_: Throwable) { try { client.close() } catch (_: Exception) { } }
    }

    private fun handleUnsafe(client: Socket) {
        client.use { c ->
            c.soTimeout = 8000
            val inp = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(inp) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
            val query = (parts.getOrNull(1) ?: "").substringAfter('?', "")
            var len = 0
            var host = ""; var origin: String? = null; var contentType = ""
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                val v = h.substringAfter(':', "").trim()
                when {
                    h.startsWith("Content-Length:", true) -> len = v.toIntOrNull() ?: 0
                    h.startsWith("Host:", true) -> host = v
                    h.startsWith("Origin:", true) -> origin = v
                    h.startsWith("Content-Type:", true) -> contentType = v
                }
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

            if (!allowed(method, host, origin, contentType)) {
                send("403 Forbidden", "text/plain", "Niet toegestaan".toByteArray()); return
            }

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
                    method == "GET" && path == "/api/backup" -> {
                        val keys = query.split('&').contains("keys=1")
                        val name = "ambilight-lampen-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".json"
                        val content = Store.backup(keys).toString(2).toByteArray()
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${content.size}\r\n" +
                            "Content-Disposition: attachment; filename=\"$name\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(content); out.flush()
                    }
                    path == "/api/restore" && method == "POST" -> {
                        if (Engine.running || SceneEngine.active != null) msg(false, tr("Stop eerst de sync of scène", "Stop the sync or scene first"))
                        else {
                            val n = Store.restore(JSONObject(req))
                            Engine.syncWorkers()
                            msg(true, tr("Teruggezet: $n lamp(en) met kamer, groepen en instellingen.", "Restored: $n lamp(s) with room, groups and settings."))
                        }
                    }
                    path == "/api/save" && method == "POST" -> { save(JSONObject(req)); msg(true, tr("Opgeslagen", "Saved")) }
                    path == "/api/import" && method == "POST" -> msg(true, import(req))
                    path == "/api/identify" && method == "POST" -> {
                        if (Engine.running) msg(false, tr("Stop eerst de sync", "Stop the sync first")) else { Engine.blink(JSONObject(req).getString("id")); msg(true, tr("Lamp knippert", "Lamp is blinking")) }
                    }
                    path == "/api/test" && method == "POST" -> {
                        val id = JSONObject(req).optString("id")
                        when {
                            Engine.running -> msg(false, tr("Stop eerst de sync", "Stop the sync first"))
                            id.isBlank() -> { Engine.testAll(); msg(true, tr("Alle lampen worden na elkaar getest", "All lamps are tested one by one")) }
                            else -> { Engine.test(id); msg(true, tr("Rood, groen, blauw, wit, en dan terug", "Red, green, blue, white, and then back")) }
                        }
                    }
                    path == "/api/color" && method == "POST" -> {
                        val j = JSONObject(req)
                        if (Engine.running) msg(false, tr("Stop eerst de sync", "Stop the sync first"))
                        else { Engine.setColor(j.getString("id"), android.graphics.Color.parseColor(j.getString("color"))); msg(true, tr("Kleur gezet", "Colour set")) }
                    }
                    path == "/api/sync" && method == "POST" && JSONObject(req).optString("temp").isNotBlank() -> {
                        // Tijdelijk (testbeeld): niet onthouden als "sync aan"
                        val j = JSONObject(req)
                        if (j.optBoolean("on")) Engine.tempStart(j.getString("temp")) else Engine.tempEnd(j.getString("temp"))
                        msg(true, "")
                    }
                    path == "/api/sync" && method == "POST" -> {
                        val on = JSONObject(req).optBoolean("on")
                        if (!on) AutoDark.userStopped()
                        Store.syncWanted = on; Store.save()
                        if (on) Engine.start() else Engine.stop()
                        msg(true, if (on) tr("Sync gestart", "Sync started") else tr("Sync gestopt", "Sync stopped"))
                    }
                    path == "/api/lowcal" && method == "POST" -> {
                        val j = JSONObject(req)
                        val id = j.optString("id", "*")
                        if (!j.optBoolean("on", true)) { if (id == "*") Engine.endAllPreviews() else Engine.endPreview(id); msg(true, tr("Klaar", "Done")) }
                        else { Engine.previewLow(id, j.optDouble("level", 0.05), j.optDouble("hue", 190.0), j.optDouble("strength", 0.0), j.optString("base", "grey")); msg(true, "") }
                    }
                    path == "/api/lamp/revive" && method == "POST" -> msg(true, Engine.revive(JSONObject(req).getString("id")))
                    path == "/api/demo" && method == "POST" -> { Engine.demo(JSONObject(req).optString("k")); msg(true, "") }
                    path == "/api/calib" && method == "POST" -> {
                        val on = JSONObject(req).optBoolean("on")
                        Engine.calib(on)
                        msg(true, if (on) tr("Kijk naar de tv", "Look at the TV") else tr("Kalibratie klaar", "Calibration done"))
                    }
                    path == "/api/lamps/power" && method == "POST" -> {
                        val on = JSONObject(req).optBoolean("on")
                        Engine.allLamps(on)
                        msg(true, if (on) tr("Lampen aan", "Lamps on") else tr("Lampen uit", "Lamps off"))
                    }
                    path == "/api/pair/request" && method == "POST" -> {
                        JSONObject(req).optString("host").ifBlank { null }?.let { Store.tvHost = it; Store.save() }
                        val r = PhilipsTv.pairRequest()
                        msg(r == "OK", if (r == "OK") tr("Er staat nu een pincode op de tv", "A PIN code is now shown on the TV") else r)
                    }
                    path == "/api/pair/grant" && method == "POST" -> {
                        val r = PhilipsTv.pairGrant(JSONObject(req).optString("pin").trim())
                        msg(r == "OK", if (r == "OK") tr("Tv gekoppeld", "TV paired") else r)
                    }
                    path == "/api/cloud" && method == "POST" -> {
                        val j = JSONObject(req)
                        j.optString("id").trim().ifBlank { null }?.let { Store.cloudId = it }
                        j.optString("secret").trim().ifBlank { null }?.let { Store.cloudSecret = it }
                        j.optString("region").ifBlank { null }?.let { Store.cloudRegion = it }
                        Store.save()
                        try { msg(true, TuyaCloud.refresh()) } catch (e: Exception) { msg(false, tr("Tuya weigerde: ", "Tuya refused: ") + e.message) }
                    }
                    path == "/api/wiz/scan" && method == "POST" -> {
                        val n = Wiz.scan(3000)
                        val all = Store.lampsSnapshot().count { it.version == Wiz.VERSION }
                        msg(true, when {
                            n > 0 -> tr("$n nieuwe WiZ-lamp(en) gevonden", "$n new WiZ lamp(s) found")
                            all > 0 -> tr("Geen nieuwe WiZ-lampen, $all al bekend", "No new WiZ lamps, $all already known")
                            else -> tr("Geen WiZ-lampen gevonden. Staat Lokale communicatie aan in de WiZ-app?", "No WiZ lamps found. Is Local communication on in the WiZ app?")
                        })
                    }
                    path == "/api/hue/pair" && method == "POST" -> {
                        Hue.startPair(JSONObject(req).optString("ip"))
                        msg(true, tr("Bezig met koppelen", "Pairing"))
                    }
                    path == "/api/hue/refresh" && method == "POST" -> {
                        try { msg(true, Hue.refresh()) } catch (e: Exception) { msg(false, e.message ?: "") }
                    }
                    path == "/api/hue/area" && method == "POST" -> {
                        if (Engine.running || SceneEngine.active != null) msg(false, tr("Stop eerst de sync", "Stop the sync first"))
                        else { Store.hueArea = JSONObject(req).optString("id").ifBlank { null }; Store.save(); msg(true, Hue.loadAreas()) }
                    }
                    path == "/api/hue/unpair" && method == "POST" -> msg(true, Hue.unpair())
                    path == "/api/ikea/pair" && method == "POST" -> {
                        Dirigera.startPair(JSONObject(req).optString("ip"))
                        msg(true, tr("Bezig met koppelen", "Pairing"))
                    }
                    path == "/api/ikea/refresh" && method == "POST" -> {
                        try { msg(true, Dirigera.refresh()) } catch (e: Exception) { msg(false, e.message ?: "") }
                    }
                    path == "/api/ikea/unpair" && method == "POST" -> msg(true, Dirigera.unpair())
                    path == "/api/cloud/refresh" && method == "POST" -> {
                        try { msg(true, TuyaCloud.refresh()) } catch (e: Exception) { msg(false, tr("Ophalen mislukt: ", "Fetching failed: ") + e.message) }
                    }
                    path == "/api/report" && method == "POST" -> {
                        if (!Store.settings.reports) msg(false, tr("Fouten sturen staat uit (Instellingen)", "Sending problems is switched off (Settings)"))
                        else if (Reporter.report(JSONObject(req).optString("note"))) msg(true, tr("Verstuurd, dank je", "Sent, thank you"))
                        else msg(false, tr("Versturen lukte niet. Is er internet?", "Sending failed. Is there internet?"))
                    }
                    path == "/api/update/check" && method == "POST" -> {
                        Updater.check(force = true)
                        val cur = Updater.current(ctx)
                        when {
                            Updater.available(ctx) -> msg(true, tr("Nieuwe versie ${Updater.latest} beschikbaar. Druk op Update.", "New version ${Updater.latest} available. Press Update."))
                            Updater.latest == null -> msg(false, tr("Kon GitHub niet bereiken. Probeer het later opnieuw.", "Could not reach GitHub. Try again later."))
                            else -> msg(true, tr("Je hebt de nieuwste versie ($cur)", "You have the latest version ($cur)"))
                        }
                    }
                    path == "/api/update" && method == "POST" -> {
                        Updater.check(force = true)
                        if (Updater.available(ctx)) msg(true, Updater.install(ctx))
                        else msg(false, tr("Je hebt al de nieuwste versie", "You already have the latest version") + " (${Updater.current(ctx)})")
                    }
                    path == "/api/scene" && method == "POST" -> {
                        val id = JSONObject(req).optString("id")
                        val sc = SceneEngine.all().firstOrNull { it.id == id }
                        if (sc == null) { SceneEngine.stop(); msg(true, tr("Scène gestopt", "Scene stopped")) }
                        else { SceneEngine.start(sc); msg(true, tr("Scène: ", "Scene: ") + sc.name) }
                    }
                    path == "/api/scene/save" && method == "POST" -> {
                        val sc = SceneEngine.saveCurrent(JSONObject(req).optString("name").trim())
                        msg(true, tr("Opgeslagen als scène: ", "Saved as scene: ") + sc.name)
                    }
                    path == "/api/scene/delete" && method == "POST" -> {
                        val id = JSONObject(req).optString("id")
                        if (SceneEngine.active?.id == id) SceneEngine.stop()
                        Store.customScenes = Store.customScenes.filter { it.id != id }; Store.save()
                        msg(true, tr("Scène verwijderd", "Scene deleted"))
                    }
                    path == "/api/lamp/delete" && method == "POST" -> {
                        val id = JSONObject(req).getString("id")
                        synchronized(Store) {
                            Store.lamps.firstOrNull { it.id == id }?.let { Store.hidden[id] = it.name }
                            Store.lamps.removeAll { it.id == id }
                        }
                        Store.save(); Engine.syncWorkers(); msg(true, tr("Lamp verwijderd", "Lamp removed"))
                    }
                    path == "/api/lamp/forget" && method == "POST" -> {
                        val id = JSONObject(req).getString("id")
                        synchronized(Store) { Store.hidden.remove(id)?.let { Store.forgotten[id] = it } }
                        Store.save(); msg(true, tr("Lamp definitief verwijderd", "Lamp removed for good"))
                    }
                    path == "/api/lamp/restore" && method == "POST" && JSONObject(req).getString("id").startsWith("ikea-") && Store.ikeaToken == null ->
                        msg(false, tr("Koppel eerst de DIRIGERA, dan komt de lamp vanzelf terug", "Pair the DIRIGERA first, then the lamp comes back by itself"))
                    path == "/api/lamp/restore" && method == "POST" && JSONObject(req).getString("id").startsWith("hue-") && Store.hueKey == null ->
                        msg(false, tr("Koppel eerst de Hue Bridge, dan komt de lamp vanzelf terug", "Pair the Hue Bridge first, then the lamp comes back by itself"))
                    path == "/api/lamp/restore" && method == "POST" -> {
                        val id = JSONObject(req).getString("id")
                        synchronized(Store) { Store.hidden.remove(id) }
                        Store.save()
                        if (id.startsWith("ikea-")) {
                            try { Dirigera.refresh(); msg(true, tr("Lamp teruggezet", "Lamp restored")) } catch (e: Exception) { msg(false, tr("Terugzetten mislukt: ", "Restoring failed: ") + e.message) }
                        } else if (id.startsWith("hue-")) {
                            try { Hue.refresh(); msg(true, tr("Lamp teruggezet", "Lamp restored")) } catch (e: Exception) { msg(false, tr("Terugzetten mislukt: ", "Restoring failed: ") + e.message) }
                        } else if (id.startsWith("wiz-")) {
                            Wiz.scan(3000)
                            if (Store.lampsSnapshot().any { it.id == id }) msg(true, tr("Lamp teruggezet", "Lamp restored"))
                            else msg(true, tr("Lamp komt terug zodra de tv hem weer op het netwerk ziet", "The lamp comes back as soon as the TV sees it on the network again"))
                        } else if (Store.cloudConfigured) {
                            try { TuyaCloud.refresh(); msg(true, tr("Lamp teruggezet", "Lamp restored")) } catch (e: Exception) { msg(false, tr("Terugzetten mislukt: ", "Restoring failed: ") + e.message) }
                        } else msg(true, tr("Zoek de lamp opnieuw of upload devices.json", "Search for the lamp again or upload devices.json"))
                    }
                    else -> send("404 Not Found", "text/plain", "Niet gevonden".toByteArray())
                }
            } catch (e: java.net.SocketException) {
                // Verbinding al dicht: niets meer terug te sturen
            } catch (e: Exception) {
                AppLog.add("Web $method $path: " + (e.message ?: e.javaClass.simpleName))
                try { msg(false, tr("Fout: ", "Error: ") + e.message) } catch (_: Exception) { }
            }
        }
    }

    private fun state(): JSONObject {
        val lamps = JSONArray()
        for (l in Store.lampsSnapshot()) {
            val w = Engine.workers[l.id]
            lamps.put(l.toJson(false).apply {
                put("online", w?.online == true)
                val live = Engine.running || SceneEngine.active != null
                val col = (if (live) w?.shownColor else w?.idleColor()) ?: 0x555555
                put("color", "#%06x".format(col and 0xffffff))
                put("on", w?.powered != false)
                put("supported", l.supported)
                put("error", w?.lastFail ?: ""); put("reconnects", w?.reconnects ?: 0)
            })
        }
        val sample = Engine.lastSample
        Engine.checkCalib()
        return JSONObject().apply {
            put("calib", Engine.calibrating); put("calibBands", Engine.calibBands())
            put("lowRef", Engine.previewRef ?: "")
            put("demo", Engine.demoKey)
            put("layout", Store.layout)
            put("lamps", lamps)
            put("settings", Store.settings.toJson())
            put("groups", JSONArray().apply { Store.groups.forEach { put(it.toJson()) } })
            put("tv", JSONObject().put("paired", Store.tvPaired).put("host", Store.tvHost).put("status", Engine.tvStatus)
                .put("appAccess", Foreground.allowed()).put("app", Foreground.idle().let { Foreground.current } ?: ""))
            put("sync", Engine.running)
            put("lampsOff", Engine.lampsOff)
            put("sun", AutoDark.info())
            put("scenes", JSONArray().apply { SceneEngine.all().forEach { put(it.toJson()) } })
            put("activeScene", SceneEngine.active?.id ?: "")
            put("update", JSONObject().put("current", Updater.current(ctx)).put("latest", Updater.latest ?: "")
                .put("available", Updater.available(ctx)).put("status", Updater.status).put("notes", Updater.notes))
            put("hidden", JSONArray().apply { Store.hidden.forEach { (k, n) -> put(JSONObject().put("id", k).put("name", n)) } })
            put("hue", JSONObject().put("paired", Store.hueKey != null).put("ip", Store.hueIp ?: "")
                .put("pairing", Hue.pairing).put("status", Hue.status).put("stream", HueStream.status).put("live", HueStream.live)
                .put("entertainment", Store.hueClientKey != null).put("area", Store.hueArea ?: "")
                .put("areas", JSONArray().apply { Hue.areas.forEach { put(JSONObject().put("id", it.first).put("name", it.second).put("lamps", it.third)) } })
                .put("streamLamps", JSONArray().apply { HueStream.channels.keys.forEach { put(it) } }))
            put("ikea", JSONObject().put("paired", Store.ikeaToken != null).put("ip", Store.ikeaIp ?: "")
                .put("pairing", Dirigera.pairing).put("status", Dirigera.status))
            put("cloud", JSONObject().put("configured", Store.cloudConfigured).put("region", Store.cloudRegion))
            phoneLink()?.let { put("link", it) }
            if (sample != null) put("top", JSONArray().apply { sample.top.forEach { put("#%02x%02x%02x".format(it[0], it[1], it[2])) } })
        }
    }

    private fun save(o: JSONObject) {
        o.optJSONObject("layout")?.let { Store.layout = it }
        o.optJSONObject("settings")?.let { Store.settings.apply(it) }
        // Groepjes: volledige lijst (nieuw, hernoemd, verwijderd); bestaande houden hun instellingen tenzij meegestuurd
        o.optJSONArray("groups")?.let { a ->
            val old = Store.groups.associateBy { it.id }
            Store.groups = (0 until a.length()).mapNotNull { i ->
                val g = a.getJSONObject(i); val id = g.getString("id")
                if (!id.matches(SAFE_ID)) return@mapNotNull null
                val grp = old[id] ?: Group(id, g.optString("name", "Groepje"), Settings().apply { apply(Store.settings.toJson()) })
                grp.name = g.optString("name", grp.name).trim().take(40).ifBlank { grp.name }
                g.optJSONObject("settings")?.let { grp.settings.apply(it) }
                grp
            }
            val ids = Store.groups.map { it.id }.toSet()
            synchronized(Store) { Store.lamps.forEach { if (it.group.isNotEmpty() && it.group !in ids) it.group = "" } }
        }
        o.optJSONArray("lamps")?.let { a ->
            synchronized(Store) {
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    val l = Store.lamps.firstOrNull { it.id == j.optString("id") } ?: continue
                    l.name = j.optString("name", l.name).trim().take(60).ifBlank { l.name }
                    l.x = j.optDouble("x", l.x); l.y = j.optDouble("y", l.y)
                    l.height = j.optString("height", l.height)
                    if (j.has("heightY")) l.heightY = if (j.isNull("heightY")) null else j.getDouble("heightY").coerceIn(-1.5, 1.5)
                    l.shape = j.optString("shape", l.shape)
                    l.follow = j.optString("follow", l.follow)
                    l.layers = j.optInt("layers", l.layers).coerceIn(0, 5)
                    if (j.has("satBoost")) l.satBoost = j.optDouble("satBoost", l.satBoost)
                    if (j.has("look")) j.optString("look").takeIf { it in LOOKS }?.let { l.look = it }
                    if (j.has("maxBright")) l.maxBright = j.optDouble("maxBright", l.maxBright).coerceIn(0.1, 1.0)
                    if (j.has("lowHue")) l.lowHue = ((j.optDouble("lowHue", l.lowHue) % 360) + 360) % 360
                    if (j.has("lowStrength")) l.lowStrength = j.optDouble("lowStrength", l.lowStrength).coerceIn(0.0, 0.8)
                    if (j.has("group")) l.group = j.optString("group")
                    l.enabled = j.optBoolean("enabled", l.enabled)
                    j.optString("ip").trim().takeIf { it.matches(IPV4) }?.let { l.ip = it }
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
                tvNote = tr(" Tv-koppeling overgenomen.", " TV pairing imported.")
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
                    d.optString("ip").takeIf { it.matches(IPV4) }?.let { ex.ip = it }
                    d.optString("version").ifBlank { null }?.let { ex.version = it }
                    updated++
                } else {
                    Store.lamps += Lamp(id, key, d.optString("name", id).take(60), d.optString("ip").takeIf { it.matches(IPV4) }.orEmpty(), d.optString("version", "3.3").ifBlank { "3.3" })
                    Store.hidden.remove(id); Store.forgotten.remove(id)
                    added++
                }
            }
        }
        Store.save(); Engine.syncWorkers()
        if (added > 0) Discovery.lookupNew()
        return tr("$added lamp(en) toegevoegd, $updated bijgewerkt.", "$added lamp(s) added, $updated updated.") + tvNote
    }

    /**
     * Alleen verzoeken van de eigen pagina's. Een website die je op je telefoon opent, kan anders de tv bereiken:
     * - Host moet een ip-adres of localhost zijn (geen domeinnaam), tegen "DNS rebinding";
     * - opdrachten (POST) moeten JSON zijn: dat mag een andere website alleen na toestemming van deze server,
     *   en die geven we nooit (geen CORS-kopregels);
     * - stuurt de browser een Origin mee, dan moet die gelijk zijn aan het adres van deze pagina.
     */
    private fun allowed(method: String, host: String, origin: String?, contentType: String): Boolean {
        val name = host.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        if (!(name == "localhost" || name.matches(IPV4))) return false
        if (origin != null && origin != "http://$host") return false
        if (method == "GET") return true
        return method == "POST" && contentType.startsWith("application/json", ignoreCase = true)
    }

    /** Adres dat de telefoon opent (via de QR-code). */
    fun phoneLink(): String? = localIp()?.let { "http://$it:$port/" }

    companion object {
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        /** Groeps-id's komen in de pagina terecht: alleen letters, cijfers, - en _. */
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,40}")
        private val LOOKS = setOf("", "spot", "pendant", "floor", "arc", "table", "globe", "rod")
        private val STATIC = mapOf(
            "/" to "text/html; charset=utf-8", "/index.html" to "text/html; charset=utf-8",
            "/tv.html" to "text/html; charset=utf-8", "/room.js" to "application/javascript; charset=utf-8",
            "/i18n.js" to "application/javascript; charset=utf-8",
            "/calib.html" to "text/html; charset=utf-8",
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
