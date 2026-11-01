package nl.stacklines.tvambilight

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.roundToInt

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
                        if (Engine.running || SceneEngine.anyActive()) msg(false, tr("Stop eerst de sync of scène", "Stop the sync or scene first"))
                        else {
                            val n = Store.restore(JSONObject(req))
                            Engine.syncWorkers()
                            msg(true, tr("Teruggezet: $n lamp(en) met kamer, groepen en instellingen.", "Restored: $n lamp(s) with room, groups and settings."))
                        }
                    }
                    path == "/api/save" && method == "POST" -> { save(JSONObject(req)); msg(true, tr("Opgeslagen", "Saved")) }
                    path == "/api/import" && method == "POST" -> msg(true, import(req))
                    path == "/api/app/profile" && method == "POST" -> {
                        val j = JSONObject(req)
                        Store.setAppProfile(j.optString("pkg"), j.optString("profile"))
                        msg(true, tr("Opgeslagen", "Saved"))
                    }
                    path == "/api/identify" && method == "POST" -> {
                        if (Engine.running && lampInTv(JSONObject(req).optString("id"))) msg(false, tr("Stop eerst de sync", "Stop the sync first")) else { Engine.blink(JSONObject(req).getString("id")); msg(true, tr("Lamp knippert", "Lamp is blinking")) }
                    }
                    path == "/api/lamp/power" && method == "POST" -> {
                        // Eén lamp aan of uit (lampenlijst). Loopt de eigen indeling van de kaart, dan via die indeling.
                        val j = JSONObject(req); val id = j.optString("id"); val on = j.optBoolean("on")
                        when {
                            Store.lampsSnapshot().none { it.id == id } -> msg(false, tr("Lamp niet gevonden", "Lamp not found"))
                            Engine.running && lampInTv(id) -> msg(false, tr("Stop eerst de sync", "Stop the sync first"))
                            SceneEngine.activeIn(roomOf(id))?.id == SceneEngine.LIVE -> { SceneEngine.liveSet(id, on, null, null, null); msg(true, if (on) tr("Lamp aan", "Lamp on") else tr("Lamp uit", "Lamp off")) }
                            else -> { Engine.power(id, on); msg(true, if (on) tr("Lamp aan", "Lamp on") else tr("Lamp uit", "Lamp off")) }
                        }
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
                        if (Engine.running && lampInTv(j.optString("id"))) msg(false, tr("Stop eerst de sync", "Stop the sync first"))
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
                        if (on) Engine.start() else Engine.stopByUser()
                        msg(true, if (on) tr("Sync gestart", "Sync started") else tr("Sync gestopt", "Sync stopped"))
                    }
                    path == "/api/lowcal" && method == "POST" -> {
                        val j = JSONObject(req)
                        val id = j.optString("id", "*")
                        if (!j.optBoolean("on", true)) { if (id == "*") Engine.endAllPreviews() else Engine.endPreview(id); msg(true, tr("Klaar", "Done")) }
                        else { Engine.previewLow(id, j.optDouble("level", 0.05), j.optDouble("hue", 190.0), j.optDouble("strength", 0.0), j.optString("base", "grey")); msg(true, "") }
                    }
                    path == "/api/match" && method == "POST" -> {
                        // Kleurbalans: twee lampen dezelfde testkleur laten tonen, of (on: false) terug naar normaal
                        val j = JSONObject(req); val id = j.optString("id"); val ref = j.optString("ref")
                        if (!j.optBoolean("on", true)) { Engine.endPreview(id); if (ref.isNotBlank()) Engine.endPreview(ref); msg(true, tr("Klaar", "Done")) }
                        else {
                            // Schuifwaarden meteen gebruiken; de pagina slaat ze daarna ook gewoon op
                            Store.lampsSnapshot().firstOrNull { it.id == id }?.let { l ->
                                if (j.has("balWarm")) l.balWarm = j.optDouble("balWarm", l.balWarm).coerceIn(-1.0, 1.0)
                                if (j.has("balTint")) l.balTint = j.optDouble("balTint", l.balTint).coerceIn(-1.0, 1.0)
                            }
                            val c = j.optJSONArray("colour")
                            val col = intArrayOf(c?.optInt(0, 0) ?: 0, c?.optInt(1, 0) ?: 0, c?.optInt(2, 700) ?: 700)
                            Engine.previewMatch(id, ref.ifBlank { id }, intArrayOf(((col[0] % 360) + 360) % 360, col[1].coerceIn(0, 1000), col[2].coerceIn(10, 1000)))
                            msg(true, "")
                        }
                    }
                    path == "/api/lamp/revive" && method == "POST" -> msg(true, Engine.revive(JSONObject(req).getString("id")))
                    path == "/api/demo" && method == "POST" -> { Engine.demo(JSONObject(req).optString("k")); msg(true, "") }
                    path == "/api/calib" && method == "POST" -> {
                        val on = JSONObject(req).optBoolean("on")
                        Engine.calib(on)
                        msg(true, if (on) tr("Kijk naar de tv", "Look at the TV") else tr("Kalibratie klaar", "Calibration done"))
                    }
                    path == "/api/lamps/power" && method == "POST" -> {
                        val j = JSONObject(req); val on = j.optBoolean("on"); val room = j.optString("room")
                        when {
                            room.isEmpty() -> { Engine.allLamps(on); msg(true, if (on) tr("Lampen aan", "Lamps on") else tr("Lampen uit", "Lamps off")) }
                            room != "*" && Store.room(room) == null -> msg(false, tr("Kamer niet gevonden", "Room not found"))
                            else -> {
                                val kept = Engine.roomLamps(room, on)
                                val what = if (room == "*") tr("Hele huis", "Whole house") else Store.room(room)!!.name
                                msg(true, what + (if (on) tr(" aan", " on") else tr(" uit", " off")) +
                                    (if (kept.isNotEmpty()) tr(", ${kept.joinToString()} blijft aan (sync of scène)", ", ${kept.joinToString()} stays on (sync or scene)") else ""))
                            }
                        }
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
                        // De tekst gaat altijd mee terug: lukt versturen niet, dan kan de telefoon hem tonen om te kopiëren
                        val text = Reporter.reportText(JSONObject(req).optString("note"))
                        val (ok, m) = when {
                            !Store.settings.reports -> false to tr("Fouten sturen staat uit (Instellingen). Hieronder staat de melding om zelf te sturen.", "Sending problems is switched off (Settings). The report is below to send yourself.")
                            Reporter.report(JSONObject(req).optString("note")) -> true to tr("Verstuurd, dank je", "Sent, thank you")
                            else -> false to tr("Versturen lukte niet (", "Sending failed (") + (Reporter.lastSendError ?: "?") + tr("). Hieronder staat de melding om zelf te sturen.", "). The report is below to send yourself.")
                        }
                        json(JSONObject().put("ok", ok).put("message", m).put("text", text))
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
                        val j = JSONObject(req); val id = j.optString("id")
                        val room = j.optString("room").ifBlank { Store.tvRoom.id }
                        val sc = SceneEngine.all().firstOrNull { it.id == id }
                        when {
                            room != "*" && Store.room(room) == null -> msg(false, tr("Kamer niet gevonden", "Room not found"))
                            room == "*" -> { SceneEngine.startEverywhere(sc); msg(true, if (sc == null) tr("Scènes gestopt", "Scenes stopped") else tr("Hele huis: ", "Whole house: ") + sc.name) }
                            sc == null -> { SceneEngine.stop(room = room); msg(true, tr("Scène gestopt", "Scene stopped")) }
                            else -> { SceneEngine.start(sc, room = room); msg(true, tr("Scène: ", "Scene: ") + sc.name) }
                        }
                    }
                    path == "/api/live" && method == "POST" -> {
                        // Kaart op de telefoon: lamp aan/uit, kleur (#rrggbb), helderheid (0..1) en effect
                        val j = JSONObject(req); val id = j.optString("id").ifBlank { null }
                        if (id != null && Store.lampsSnapshot().none { it.id == id }) msg(false, tr("Lamp niet gevonden", "Lamp not found"))
                        else if (id != null && Store.room(roomOf(id)) == null) msg(false, tr("Zet de lamp eerst in een kamer", "Put the lamp in a room first"))
                        else {
                            val colour = j.optString("colour").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let {
                                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(it), hsv)
                                intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), 1000)
                            }
                            val on = if (j.has("on")) j.optBoolean("on") else null
                            val bright = if (j.has("bright")) j.optDouble("bright", 1.0).coerceIn(0.01, 1.0) else null
                            val room = j.optString("room").ifBlank { null }
                            // Zonder lamp maar met kamer: alle lampen van die kamer tegelijk
                            if (id == null && room != null) SceneEngine.liveRoom(room, on, colour, bright, j.optString("effect").ifBlank { null })
                            else SceneEngine.liveSet(id, on, colour, bright, j.optString("effect").ifBlank { null })
                            msg(true, "")
                        }
                    }
                    path == "/api/scene/save" && method == "POST" -> {
                        val j = JSONObject(req)
                        val sc = SceneEngine.saveCurrent(j.optString("name").trim(), j.optString("room").takeIf { Store.room(it) != null } ?: Store.tvRoom.id)
                        msg(true, tr("Opgeslagen als scène: ", "Saved as scene: ") + sc.name)
                    }
                    path == "/api/hue/scenes" && method == "GET" -> {
                        // Scènes uit de Hue-app, om te kiezen welke de app overneemt
                        try {
                            val have = Store.customScenes.map { it.id }.toSet()
                            val list = JSONArray()
                            for (h in Hue.scenes().sortedWith(compareBy({ it.room.lowercase() }, { it.name.lowercase() }))) {
                                if (h.perLamp.isEmpty() && h.palette.isEmpty()) continue
                                list.put(JSONObject().put("id", h.id).put("name", h.name).put("room", h.room).put("lamps", h.perLamp.size)
                                    .put("imported", SceneEngine.hueId(h.id) in have)
                                    .put("preview", JSONArray().apply { Scene.swatches(h.perLamp.values + h.palette).take(6).forEach { put("#%06x".format(it and 0xffffff)) } }))
                            }
                            json(JSONObject().put("ok", true).put("scenes", list))
                        } catch (e: Exception) { msg(false, tr("Scènes ophalen mislukt: ", "Fetching scenes failed: ") + e.message) }
                    }
                    path == "/api/hue/scenes/import" && method == "POST" -> {
                        val a = JSONObject(req).optJSONArray("ids") ?: JSONArray()
                        val ids = (0 until a.length()).map { a.getString(it) }.toSet()
                        try {
                            val n = SceneEngine.importHue(ids)
                            msg(true, tr("$n scène(s) uit Hue overgenomen", "$n scene(s) imported from Hue"))
                        } catch (e: Exception) { msg(false, tr("Importeren mislukt: ", "Import failed: ") + e.message) }
                    }
                    path == "/api/room/save" && method == "POST" -> {
                        val j = JSONObject(req); val id = j.optString("id")
                        val name = j.optString("name").trim().take(30)
                        val color = j.optString("color").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                        val existing = if (id.isBlank()) null else Store.room(id)
                        when {
                            id.isNotBlank() && existing == null -> msg(false, tr("Kamer niet gevonden", "Room not found"))
                            j.optBoolean("tv", false) && existing?.tv != true && Engine.running -> msg(false, tr("Stop eerst de sync", "Stop the sync first"))
                            existing == null && name.isEmpty() -> msg(false, tr("Geef de kamer een naam", "Give the room a name"))
                            else -> {
                                val r = synchronized(Store) {
                                    val r = existing ?: Room(newRoomId(name), name, color ?: "#7a8fb5", false, JSONObject()).also { Store.rooms = Store.rooms + it }
                                    if (name.isNotEmpty()) r.name = name
                                    color?.let { r.color = it }
                                    // Maat in meters: een nieuwe of lege vloer wordt een rechthoek van die maat
                                    val w = j.optDouble("w", Double.NaN); val h = j.optDouble("h", Double.NaN)
                                    if (!w.isNaN() && !h.isNaN()) r.layout = resized(r.layout, w.coerceIn(1.0, 20.0), h.coerceIn(1.0, 20.0))
                                    if (j.optBoolean("tv", false) && !r.tv) Store.rooms.forEach { it.tv = it === r }
                                    Store.normalizeRooms()
                                    r
                                }
                                Store.save(); Engine.syncWorkers()
                                json(JSONObject().put("ok", true).put("id", r.id).put("message", if (existing == null) tr("Kamer toegevoegd: ", "Room added: ") + r.name else tr("Opgeslagen", "Saved")))
                            }
                        }
                    }
                    path == "/api/room/delete" && method == "POST" -> {
                        val r = Store.room(JSONObject(req).optString("id"))
                        when {
                            r == null -> msg(false, tr("Kamer niet gevonden", "Room not found"))
                            Store.rooms.size == 1 -> msg(false, tr("De laatste kamer kan niet weg", "The last room can't be removed"))
                            r.tv -> msg(false, tr("Hier staat de tv. Zet de tv eerst in een andere kamer.", "The TV is in this room. Move the TV to another room first."))
                            else -> {
                                SceneEngine.stop(room = r.id)
                                synchronized(Store) {
                                    Store.lamps.forEach { if (it.room == r.id) { it.room = ""; it.x = -1.0; it.y = -1.0 } }
                                    Store.rooms = Store.rooms.filter { it !== r }
                                }
                                Store.save(); Engine.syncWorkers()
                                msg(true, tr("Kamer verwijderd: ", "Room removed: ") + r.name)
                            }
                        }
                    }
                    path == "/api/room/order" && method == "POST" -> {
                        val a = JSONObject(req).optJSONArray("ids") ?: JSONArray()
                        val order = (0 until a.length()).map { a.optString(it) }
                        synchronized(Store) { Store.rooms = Store.rooms.sortedBy { r -> order.indexOf(r.id).let { if (it < 0) Int.MAX_VALUE else it } } }
                        Store.save(); msg(true, tr("Volgorde opgeslagen", "Order saved"))
                    }
                    path == "/api/scene/delete" && method == "POST" -> {
                        val id = JSONObject(req).optString("id")
                        Store.rooms.forEach { r -> if (SceneEngine.activeIn(r.id)?.id == id) SceneEngine.stop(room = r.id) }
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
                    // Koppeling met de House hub: tv-kamer, lampen en eigen scènes gelijk houden
                    method == "GET" && path == "/api/hublink" -> json(HubLink.statusJson().put("ok", true))
                    path == "/api/hublink" && method == "POST" -> {
                        val a = JSONObject(req).optString("addr").trim().removePrefix("http://").trimEnd('/')
                        if (!a.matches(Regex("[A-Za-z0-9.-]{1,63}(:[0-9]{1,5})?"))) msg(false, tr("Vul het adres van de hub in, bv. 192.168.1.177:8790", "Enter the hub address, e.g. 192.168.1.177:8790"))
                        else { HubLink.connect(if (':' in a) a else "$a:8790"); msg(true, tr("Koppelen gevraagd. Sta de tv toe op de hub (Instellingen).", "Pairing requested. Allow the TV on the hub (Settings).")) }
                    }
                    path == "/api/hublink/off" && method == "POST" -> { HubLink.disconnect(); msg(true, tr("Ontkoppeld van de hub", "Unpaired from the hub")) }
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
                val live = (Engine.running && l.inTvRoom) || SceneEngine.activeIn(l.room) != null
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
            put("rooms", JSONArray().apply { Store.rooms.forEach { r ->
                val sc = SceneEngine.activeIn(r.id)
                put(r.toJson().put("scene", sc?.id ?: "").put("sceneName", sc?.name ?: ""))
            } })
            put("lamps", lamps)
            put("settings", Store.settings.toJson())
            put("groups", JSONArray().apply { Store.groups.forEach { put(it.toJson()) } })
            put("tv", JSONObject().put("paired", Store.tvPaired).put("host", Store.tvHost).put("status", Engine.tvStatus)
                .put("appAccess", Foreground.allowed()).put("app", Foreground.idle().let { Foreground.current } ?: ""))
            put("sync", Engine.running)
            put("lampsOff", Engine.lampsOff)
            put("sun", AutoDark.info())
            put("scenes", JSONArray().apply { SceneEngine.all().forEach { put(it.toJson()) } })
            // Instellingen per app: alle apps op de tv met hun profiel
            put("apps", JSONArray().apply { Foreground.installed().forEach { (pkg, label) ->
                put(JSONObject().put("pkg", pkg).put("label", label).put("profile", Store.appProfiles[pkg] ?: "")) } })
            put("profile", if (Engine.running) Profiles.current() else "")
            put("activeScene", SceneEngine.active?.id ?: "")
            put("activeName", SceneEngine.active?.name ?: "")
            SceneEngine.liveLamps()?.let { m ->
                put("liveLamps", JSONObject().apply { m.forEach { (k, p) ->
                    put(k, JSONObject().put("colour", "#%06x".format(android.graphics.Color.HSVToColor(floatArrayOf(p.first[0].toFloat(), p.first[1] / 1000f, 1f)) and 0xffffff))
                        .put("bright", p.first[2] / 1000.0).put("effect", p.second ?: "none"))
                } })
            }
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
        val room = Store.room(o.optString("room")) ?: Store.tvRoom
        o.optJSONObject("layout")?.let { room.layout = it }
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
        val moved = HashSet<String>()
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
                    if (j.has("balWarm")) l.balWarm = j.optDouble("balWarm", l.balWarm).coerceIn(-1.0, 1.0)
                    if (j.has("balTint")) l.balTint = j.optDouble("balTint", l.balTint).coerceIn(-1.0, 1.0)
                    if (j.has("group")) l.group = j.optString("group")
                    if (j.has("room")) j.optString("room").let { r ->
                        if ((r.isEmpty() || Store.room(r) != null) && r != l.room) {
                            moved += l.room; moved += r; l.room = r
                            // De plek hoorde bij de oude kamer; zonder nieuwe plek is de lamp nog niet geplaatst
                            if (!j.has("x")) { l.x = -1.0; l.y = -1.0 }
                        }
                    }
                    l.enabled = j.optBoolean("enabled", l.enabled)
                    j.optString("ip").trim().takeIf { it.matches(Net.IPV4) }?.let { l.ip = it }
                }
            }
        }
        Store.save()
        Engine.syncWorkers()
        if (moved.isNotEmpty()) SceneEngine.lampsMoved(moved)
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
                    d.optString("ip").takeIf { it.matches(Net.IPV4) }?.let { ex.ip = it }
                    d.optString("version").ifBlank { null }?.let { ex.version = it }
                    updated++
                } else {
                    Store.lamps += Lamp(id, key, d.optString("name", id).take(60), d.optString("ip").takeIf { it.matches(Net.IPV4) }.orEmpty(), d.optString("version", "3.3").ifBlank { "3.3" })
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
        if (!(name == "localhost" || name.matches(Net.IPV4))) return false
        if (origin != null && origin != "http://$host") return false
        if (method == "GET") return true
        return method == "POST" && contentType.startsWith("application/json", ignoreCase = true)
    }

    /** Adres dat de telefoon opent (via de QR-code). */
    fun phoneLink(): String? = Net.localIp()?.let { "http://$it:$port/" }

    private fun roomOf(lampId: String) = Store.lampsSnapshot().firstOrNull { it.id == lampId }?.room ?: ""
    private fun lampInTv(lampId: String) = Store.lampsSnapshot().firstOrNull { it.id == lampId }?.inTvRoom == true

    /** Id voor een nieuwe kamer uit de naam (letters, cijfers, streepjes), uniek. */
    private fun newRoomId(name: String): String {
        val base = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifBlank { "kamer" }
        var id = base; var n = 2
        while (Store.room(id) != null) id = "$base-${n++}"
        return id
    }

    /** Vloer als rechthoek van w x h meter (in hele vakjes van een meter, zoals de editor), meubels blijven staan. */
    private fun resized(layout: JSONObject, w: Double, h: Double): JSONObject {
        val cw = kotlin.math.ceil(w).toInt(); val ch = kotlin.math.ceil(h).toInt()
        return JSONObject(layout.toString()).put("w", cw).put("h", ch).put("floor", JSONArray().apply { repeat(ch) { put("1".repeat(cw)) } })
    }

    companion object {
        /** Groeps-id's komen in de pagina terecht: alleen letters, cijfers, - en _. */
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,40}")
        private val LOOKS = setOf("", "spot", "pendant", "floor", "arc", "table", "globe", "rod", "wall", "flush", "lightbar", "strip", "desk", "head", "wallspot",
            "airco-wand-wit", "airco-wand-zwart", "airco-mobiel-wit", "airco-mobiel-zwart")
        private val STATIC = mapOf(
            "/" to "text/html; charset=utf-8", "/index.html" to "text/html; charset=utf-8",
            "/tv.html" to "text/html; charset=utf-8", "/room.js" to "application/javascript; charset=utf-8",
            "/furniture34.js" to "application/javascript; charset=utf-8",
            "/i18n.js" to "application/javascript; charset=utf-8",
            "/calib.html" to "text/html; charset=utf-8",
            "/ui.css" to "text/css; charset=utf-8", "/pixelify-sans.woff2" to "font/woff2",
            // Zet op beginscherm (telefoon): manifest en iconen
            "/manifest.json" to "application/manifest+json", "/icon-180.png" to "image/png",
            "/icon-192.png" to "image/png", "/icon-512.png" to "image/png"
        )
    }
}
