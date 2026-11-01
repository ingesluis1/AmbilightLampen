package nl.stacklines.tvambilight

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Tv en House hub gelijk houden (stap 3, zie docs/sync-ontwerp.md).
 *
 * Wat gedeeld wordt, heet een "ding" met een sleutel:
 *  - `room:tv`      naam, kleur en plattegrond van de kamer met de tv;
 *  - `lamp:<id>`    alles van een lamp in de tv-kamer (ook sleutel en plek), of {"away":true} als de lamp in een andere kamer staat;
 *  - `scene:<id>`   een eigen scène (hele huis).
 * Beide kanten maken met [view] hun eigen beeld van die dingen en vergelijken dat met wat ze het laatst met de ander
 * hebben afgesproken. Wat verschilt, is een eigen wijziging. Zo hoeft niet elke knop in de app iets te melden.
 */
object Shared {
    const val AWAY = "{\"away\":true}"

    /** JSON met de sleutels op volgorde: dezelfde inhoud geeft altijd dezelfde tekst (en dus dezelfde vingerafdruk). */
    fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> canon(v.toDouble())
        is Number, is Boolean -> v.toString()
        else -> JSONObject.quote(v.toString())
    }

    fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** Velden van een lamp die meegaan. Kamer, groepje (alleen op de tv) en afgeleide waarden niet. */
    private fun lampData(l: Lamp): JSONObject = l.toJson(true).apply { remove("room"); remove("kind"); remove("satFactor"); remove("group") }

    /**
     * Het gedeelde beeld van deze kant: sleutel naar tekst (canon). [away]: lampen waarvan de hub zei dat ze in een andere
     * kamer staan; die tellen op de tv (die vaak maar één kamer heeft) als "elders" zolang ze niet geplaatst zijn.
     */
    fun view(away: Set<String> = emptySet()): Map<String, String> = synchronized(Store) {
        val m = LinkedHashMap<String, String>()
        val tv = Store.tvRoom
        m["room:tv"] = canon(JSONObject().put("name", tv.name).put("color", tv.color).put("layout", tv.layout))
        for (l in Store.lamps) m["lamp:" + l.id] = if (l.room == tv.id && !(l.id in away && !l.placed)) canon(lampData(l)) else AWAY
        for (s in Store.customScenes) m["scene:" + s.id] = canon(s.toStore())
        m
    }

    /**
     * Eén wijziging van de andere kant overnemen. [data] null = verwijderd. Geeft terug of er iets veranderde.
     * Roep daarna één keer [finish] aan (opslaan, lampverbindingen bijwerken).
     */
    fun apply(key: String, data: String?): Boolean = synchronized(Store) {
        val tv = Store.tvRoom
        when {
            key == "room:tv" -> {
                val o = JSONObject(data ?: return false)
                o.optString("name").trim().take(30).ifBlank { null }?.let { tv.name = it }
                o.optString("color").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.let { tv.color = it }
                o.optJSONObject("layout")?.let { tv.layout = it }
                true
            }
            key.startsWith("lamp:") -> {
                val id = key.removePrefix("lamp:")
                val cur = Store.lamps.firstOrNull { it.id == id }
                when {
                    data == null -> { if (cur == null) return false; Store.hidden[id] = cur.name; Store.lamps.remove(cur); true }
                    data == AWAY -> {
                        if (cur == null || cur.room != tv.id) return false
                        if (Store.rooms.size > 1) cur.room = ""
                        cur.x = -1.0; cur.y = -1.0; true
                    }
                    else -> {
                        val src = Lamp.fromJson(JSONObject(data).put("id", id))
                        Store.hidden.remove(id); Store.forgotten.remove(id)
                        val l = cur ?: src.also { Store.lamps += it }
                        if (cur != null) copyInto(src, cur)
                        l.room = tv.id
                        true
                    }
                }
            }
            key.startsWith("scene:") -> {
                val id = key.removePrefix("scene:")
                if (data == null) {
                    if (Store.customScenes.none { it.id == id }) return false
                    Store.customScenes = Store.customScenes.filter { it.id != id }
                } else {
                    val s = Scene.fromStore(JSONObject(data))
                    val i = Store.customScenes.indexOfFirst { it.id == id }
                    Store.customScenes = if (i < 0) Store.customScenes + s else Store.customScenes.toMutableList().also { it[i] = s }
                }
                true
            }
            else -> false
        }
    }

    /** Na een reeks [apply]: bewaren en de lampverbindingen bijwerken. */
    fun finish() { Store.save(); Engine.syncWorkers() }

    private fun copyInto(s: Lamp, d: Lamp) {
        if (s.key.isNotBlank()) d.key = s.key
        d.name = s.name; d.ip = s.ip; d.version = s.version; d.x = s.x; d.y = s.y; d.height = s.height; d.enabled = s.enabled
        d.shape = s.shape; d.follow = s.follow; d.layers = s.layers; d.category = s.category; d.satBoost = s.satBoost
        d.heightY = s.heightY; d.lowHue = s.lowHue; d.lowStrength = s.lowStrength; d.maxBright = s.maxBright; d.look = s.look
        d.balWarm = s.balWarm; d.balTint = s.balTint
    }

    /** Kleine HTTP-POST over een gewone socket (thuisnetwerk, geen https; werkt op de tv én in tests). */
    fun post(addr: String, path: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): Pair<Int, String> {
        val host = addr.substringBefore(':'); val port = addr.substringAfter(':', "8790").toIntOrNull() ?: 8790
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs); s.soTimeout = timeoutMs
            val b = body.toByteArray()
            val head = StringBuilder("POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            val out = s.getOutputStream(); out.write(head.toString().toByteArray()); out.write(b); out.flush()
            val all = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = all.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return code to all.substringAfter("\r\n\r\n", "")
        }
    }
}

/**
 * De tv-kant: belt de hub (elke 5 seconden, en binnen een seconde na een eigen wijziging) en ruilt wijzigingen.
 * Zonder hub-adres doet dit niets; valt de hub weg, dan gaat de tv gewoon door en ruilt later.
 * Stand in hubsync.json naast config.json (niet in de backup).
 */
object HubLink {
    private var file: File? = null
    @Volatile var addr = ""; private set
    @Volatile private var secret = ""
    @Volatile private var since = 0L
    private val known = HashMap<String, String>()   // sleutel -> vingerafdruk van wat tv en hub het laatst afspraken
    private val away = HashSet<String>()            // lamp-id's die volgens de hub in een andere kamer staan
    @Volatile var status = ""; private set           // pending | ok | error | unpaired | ""
    @Volatile var error = ""; private set
    @Volatile var lastOk = 0L; private set
    @Volatile var lastChanges = 0; private set
    private var thread: Thread? = null
    private val lock = Object()
    /** Naam van de tv bij het koppelen (de hub toont hem bij het goedkeuren). */
    var deviceName = "Tv"

    val paired get() = addr.isNotEmpty() && secret.isNotEmpty()

    fun init(dir: File) {
        file = File(dir, "hubsync.json")
        try {
            val o = JSONObject(file!!.readText())
            addr = o.optString("addr"); secret = o.optString("secret"); since = o.optLong("since")
            o.optJSONObject("known")?.let { k -> k.keys().forEach { known[it] = k.getString(it) } }
            o.optJSONArray("away")?.let { a -> for (i in 0 until a.length()) away += a.getString(i) }
            if (addr.isNotEmpty()) status = if (secret.isEmpty()) "pending" else "ok"
        } catch (_: Exception) { }
        start()
    }

    private fun persist() {
        val f = file ?: return
        val o = synchronized(lock) {
            JSONObject().put("addr", addr).put("secret", secret).put("since", since)
                .put("known", JSONObject(known as Map<*, *>)).put("away", JSONArray(away.toList()))
        }
        try { val tmp = File(f.parentFile, "hubsync.json.tmp"); tmp.writeText(o.toString()); tmp.renameTo(f) } catch (_: Exception) { }
    }

    fun statusJson(): JSONObject = JSONObject().put("addr", addr).put("paired", paired).put("status", status).put("error", error)
        .put("lastOk", lastOk).put("lastChanges", lastChanges)

    /** Koppelen met een hub (adres zoals 192.168.1.177:8790). Begint opnieuw: de hub wint bij de eerste ruil. */
    fun connect(a: String) {
        synchronized(lock) { addr = a.trim().removePrefix("http://").trimEnd('/'); secret = ""; since = 0; known.clear(); away.clear(); status = "pending"; error = "" }
        persist(); wake()
    }

    fun disconnect() {
        synchronized(lock) { addr = ""; secret = ""; since = 0; known.clear(); away.clear(); status = ""; error = "" }
        persist()
    }

    private fun wake() { synchronized(lock) { (lock as Object).notifyAll() } }

    private fun start() {
        if (thread != null) return
        thread = Thread({
            var lastVer = -1; var lastRun = 0L
            while (true) {
                try {
                    synchronized(lock) { (lock as Object).wait(1000) }
                    if (addr.isEmpty()) continue
                    val now = System.currentTimeMillis()
                    val due = now - lastRun >= (if (secret.isEmpty()) 5_000 else 5_000)
                    if (!due && Store.version == lastVer) continue
                    lastRun = now
                    if (secret.isEmpty()) pair() else { exchange(); lastVer = Store.version }
                } catch (_: InterruptedException) { break
                } catch (e: Exception) { status = if (secret.isEmpty()) "pending" else "error"; error = e.message ?: e.javaClass.simpleName }
            }
        }, "hub-link").apply { isDaemon = true; start() }
    }

    private fun pair() {
        val (code, body) = Shared.post(addr, "/api/tvsync/pair", JSONObject().put("tv", Store.installId).put("name", deviceName).toString())
        if (code != 200) { status = "pending"; error = "HTTP $code"; return }
        val o = JSONObject(body)
        if (o.optString("status") == "ok" && o.optString("secret").isNotEmpty()) {
            synchronized(lock) { secret = o.getString("secret"); since = 0; known.clear() }
            status = "ok"; error = ""; persist(); exchange()
        } else { status = if (o.optString("status") == "denied") "unpaired" else "pending"; error = "" }
    }

    /** Eén ruil met de hub. */
    fun exchange() {
        val first = since == 0L && known.isEmpty()
        val v = Shared.view(synchronized(lock) { away.toSet() })
        val changes = JSONArray()
        val sent = HashMap<String, String?>()
        if (!first) synchronized(lock) {
            for ((k, s) in v) if (known[k] != Shared.hash(s)) { changes.put(JSONObject().put("key", k).put("data", s)); sent[k] = s }
            for (k in known.keys.toList()) if (k !in v && known[k] != Shared.hash(Shared.AWAY)) { changes.put(JSONObject().put("key", k).put("deleted", true)); sent[k] = null }
        }
        val req = JSONObject().put("tv", Store.installId).put("since", since).put("changes", changes)
        val (code, body) = Shared.post(addr, "/api/tvsync", req.toString(), mapOf("X-Sync-Key" to secret))
        if (code == 401 || code == 403) {
            synchronized(lock) { secret = ""; since = 0; known.clear(); away.clear() }
            status = "unpaired"; error = ""; persist(); return
        }
        if (code != 200) { status = "error"; error = "HTTP $code"; return }
        val o = JSONObject(body)
        val got = o.optJSONArray("changes") ?: JSONArray()
        var applied = false
        synchronized(lock) {
            for ((k, s) in sent) if (s == null) known.remove(k) else { known[k] = Shared.hash(s); if (s != Shared.AWAY) away.remove(k.removePrefix("lamp:")) }
        }
        val incoming = ArrayList<Pair<String, String?>>()
        for (i in 0 until got.length()) {
            val c = got.getJSONObject(i); val k = c.getString("key")
            val d = if (c.optBoolean("deleted")) null else c.optString("data")
            incoming += k to d
            if (k.startsWith("lamp:")) synchronized(lock) { val id = k.removePrefix("lamp:"); if (d == Shared.AWAY) away += id else away -= id }
            try { if (Shared.apply(k, d)) applied = true } catch (e: Exception) { AppLog.add("House hub: $k niet over te nemen: ${e.message}") }
        }
        if (applied) Shared.finish()
        if (incoming.isNotEmpty()) {
            val v2 = Shared.view(synchronized(lock) { away.toSet() })
            synchronized(lock) {
                for ((k, d) in incoming) {
                    if (d == null) known.remove(k)
                    else known[k] = Shared.hash(v2[k] ?: if (k.startsWith("lamp:")) Shared.AWAY else d)
                }
            }
        }
        synchronized(lock) { since = o.optLong("seq", since) }
        lastChanges = changes.length() + got.length()
        status = "ok"; error = ""; lastOk = System.currentTimeMillis()
        if (changes.length() > 0 || got.length() > 0 || first) persist()
    }
}
