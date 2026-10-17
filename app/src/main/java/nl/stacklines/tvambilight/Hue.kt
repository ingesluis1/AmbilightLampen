package nl.stacklines.tvambilight

import android.graphics.Color
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Philips Hue via de Hue Bridge (lokale API v2). Ook andere Zigbee-lampen die aan de Bridge gekoppeld zijn
 * (bijvoorbeeld IKEA) doen zo mee. Net als bij WiZ denkt de rest van de app in Tuya-functies
 * (20 aan/uit, 21 modus, 22 helderheid, 23 kleurtemperatuur, 24 kleur); hier wordt dat vertaald.
 *
 * Lamp-id = "hue-<id van de lamp op de Bridge>", ip = adres van de Bridge. De sleutel staat in Store (één per Bridge).
 * Categorie: hue-color (kleur), hue-ct (alleen warm/koel wit), hue-dim (alleen dimmen).
 */
object Hue {
    const val VERSION = "hue"
    /** De Bridge verwerkt ongeveer 10 lampopdrachten per seconde, voor alle lampen samen. */
    private const val SLOT_MS = 100L

    /** Wat er bij koppelen/ophalen gebeurt, voor de telefoon. */
    @Volatile var status = ""
    @Volatile var pairing = false
        private set
    /** Entertainment-ruimtes op de Bridge (id, naam, aantal lampen), voor de keuzelijst. */
    @Volatile var areas: List<Triple<String, String, Int>> = emptyList()
        private set

    // De Bridge heeft een eigen certificaat op een lokaal adres; dat kan Android niet controleren.
    private val trustAll: SSLSocketFactory by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })
        SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }.socketFactory
    }

    class HueException(msg: String) : Exception(msg)

    /** Eén HTTP-verzoek; geeft statuscode en tekst. host mag een poort bevatten (handig om te testen). */
    fun request(method: String, url: String, body: String? = null, key: String? = Store.hueKey, timeout: Int = 3000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) { c.sslSocketFactory = trustAll; c.hostnameVerifier = HostnameVerifier { _, _ -> true } }
        c.requestMethod = method; c.connectTimeout = timeout; c.readTimeout = timeout
        key?.let { c.setRequestProperty("hue-application-key", it) }
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return code to text
    }

    /** GET op een v2-resource; geeft de lijst "data". */
    private fun resource(type: String): JSONArray {
        val host = Store.hueIp ?: throw HueException(tr("Nog geen Bridge gekoppeld", "No Bridge paired yet"))
        val (code, text) = request("GET", "https://$host/clip/v2/resource/$type")
        if (code == 401 || code == 403) throw HueException(tr("De Bridge kent deze koppeling niet meer. Koppel opnieuw.", "The Bridge no longer knows this pairing. Pair again."))
        if (code != 200) throw HueException("Bridge: HTTP $code")
        return JSONObject(text).optJSONArray("data") ?: JSONArray()
    }

    /** Huidige stand van één lamp als Tuya-functies, of null als de Bridge niet antwoordt. */
    fun light(id: String): JSONObject? {
        val host = Store.hueIp ?: return null
        val (code, text) = request("GET", "https://$host/clip/v2/resource/light/$id", timeout = 2000)
        if (code != 200) return null
        return JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.let { dps(it) }
    }

    /** Stuurt een opdracht naar één lamp. Gooit een fout als de Bridge weigert. */
    fun setLight(id: String, body: JSONObject) {
        val host = Store.hueIp ?: throw HueException(tr("Nog geen Bridge gekoppeld", "No Bridge paired yet"))
        val (code, text) = request("PUT", "https://$host/clip/v2/resource/light/$id", body.toString(), timeout = 2000)
        if (code == 429 || code == 503) { Thread.sleep(500); return }   // Bridge heeft het druk: deze kleur overslaan
        if (code != 200) throw HueException("Bridge: HTTP $code " + text.take(80))
    }

    private val slotLock = Object()
    private var nextSlot = 0L
    /** Wacht op een vrij moment bij de Bridge (gedeeld door alle Hue-lampen). */
    fun waitTurn() {
        val wait = synchronized(slotLock) {
            val now = SystemClock.elapsedRealtime()
            val t = max(now, nextSlot); nextSlot = t + SLOT_MS; t - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    // ---------- Zoeken en koppelen ----------

    /** Zoekt Bridges: eerst via de zoekdienst van Philips, anders zelf het netwerk afgaan. */
    fun findBridges(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val (code, text) = request("GET", "https://discovery.meethue.com/", key = null, timeout = 4000)
            if (code == 200) JSONArray(text).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).optString("internalipaddress").ifBlank { null }?.let { out += it } }
        } catch (_: Exception) { }
        // De zoekdienst kan een oud adres geven; alleen houden wat echt antwoordt
        val alive = out.filter { bridgeId(it) != null }.toMutableList()
        if (alive.isEmpty()) alive += scan()
        return alive
    }

    /** Id van de Bridge op dit adres (vraagt de openbare config op), of null als daar geen Bridge zit. */
    fun bridgeId(host: String): String? = try {
        val (code, text) = request("GET", "http://$host/api/0/config", key = null, timeout = 1500)
        if (code == 200) JSONObject(text).optString("bridgeid").ifBlank { null } else null
    } catch (_: Exception) { null }

    /** Alle adressen in het eigen netwerk (x.x.x.1 tot .254) proberen. */
    private fun scan(): List<String> {
        val prefix = WebServer.localIp()?.substringBeforeLast('.') ?: return emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(32)
        for (i in 1..254) pool.execute {
            val host = "$prefix.$i"
            try {
                val c = URL("http://$host/api/0/config").openConnection() as HttpURLConnection
                c.connectTimeout = 700; c.readTimeout = 1200
                if (c.responseCode == 200 && c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.contains("bridgeid")) found += host
            } catch (_: Exception) { }
        }
        pool.shutdown(); pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.sorted()
    }

    /** Koppelen op de achtergrond: Bridge zoeken (als er geen adres is), wachten op de knop, lampen ophalen. */
    fun startPair(ip: String?) {
        synchronized(this) { if (pairing) return; pairing = true }
        Thread({
            status = try { pair(ip?.trim()?.ifBlank { null }) } catch (e: Exception) { tr("Koppelen mislukt: ", "Pairing failed: ") + e.message }
            pairing = false
        }, "hue-pair").apply { isDaemon = true; start() }
    }

    private fun pair(ip: String?): String {
        val host = ip ?: run {
            status = tr("Bridge zoeken...", "Looking for the Bridge...")
            findBridges().firstOrNull()
        } ?: return tr("Geen Bridge gevonden. Zit hij in hetzelfde netwerk als de tv? Je kunt ook het ip-adres invullen (staat in de Hue-app bij Instellingen, Bridges).",
            "No Bridge found. Is it on the same network as the TV? You can also enter its IP address (in the Hue app under Settings, Bridges).")
        val id = bridgeId(host) ?: return tr("Op $host antwoordt geen Hue Bridge.", "No Hue Bridge answers at $host.")
        status = tr("Druk nu op de grote knop op de Bridge", "Now press the big button on the Bridge")
        val ask = JSONObject().put("devicetype", "ambilight_lampen#tv").put("generateclientkey", true).toString()
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) {
            val (_, text) = request("POST", "https://$host/api", ask, key = null)
            val o = try { JSONArray(text).getJSONObject(0) } catch (_: Exception) { null }
            val ok = o?.optJSONObject("success")
            if (ok != null) {
                Store.hueIp = host; Store.hueBridgeId = id
                Store.hueKey = ok.getString("username"); Store.hueClientKey = ok.optString("clientkey").ifBlank { null }
                Store.save()
                return tr("Bridge gekoppeld. ", "Bridge paired. ") + refresh()
            }
            val err = o?.optJSONObject("error")
            if (err != null && err.optInt("type") != 101) return tr("Bridge weigert: ", "Bridge refused: ") + err.optString("description")
            Thread.sleep(1000)
        }
        return tr("De knop is niet ingedrukt. Probeer het opnieuw.", "The button was not pressed. Try again.")
    }

    /** Ontkoppelen: sleutel weg, Hue-lampen naar de verwijderde lampen. */
    fun unpair(): String {
        HueStream.awaitStop()
        synchronized(Store) {
            Store.lamps.filter { it.version == VERSION }.forEach { Store.hidden[it.id] = it.name }
            Store.lamps.removeAll { it.version == VERSION }
            Store.hueKey = null; Store.hueClientKey = null; Store.hueArea = null
        }
        areas = emptyList(); HueStream.channels = emptyMap(); HueStream.areaId = null; status = ""
        Store.save(); Engine.syncWorkers()
        return tr("Bridge ontkoppeld", "Bridge unpaired")
    }

    // ---------- Lampen ophalen ----------

    /** Haalt de lampen en Entertainment-ruimtes op van de Bridge. Geeft een korte uitleg terug. */
    fun refresh(): String {
        if (Store.hueKey == null) return tr("Nog geen Bridge gekoppeld", "No Bridge paired yet")
        val lights = try { resource("light") } catch (e: java.io.IOException) {
            // Bridge kreeg misschien een ander adres van de router: opnieuw zoeken op zijn id
            if (!relocate()) throw HueException(tr("Bridge niet bereikbaar op ${Store.hueIp}", "Bridge not reachable at ${Store.hueIp}"))
            resource("light")
        }
        val devices = HashMap<String, String>()   // apparaat-id -> fabrikant
        try {
            val d = resource("device")
            for (i in 0 until d.length()) d.getJSONObject(i).let { devices[it.optString("id")] = it.optJSONObject("product_data")?.optString("manufacturer_name").orEmpty() }
        } catch (_: Exception) { }
        val host = Store.hueIp!!
        var added = 0; var known = 0; var changed = false
        synchronized(Store) {
            for (i in 0 until lights.length()) {
                val l = lights.getJSONObject(i)
                val id = "hue-" + l.getString("id")
                val cat = when {
                    l.has("color") -> "hue-color"
                    l.has("color_temperature") -> "hue-ct"
                    else -> "hue-dim"
                }
                if (id in Store.hidden || id in Store.forgotten) continue
                val ex = Store.lamps.firstOrNull { it.id == id }
                if (ex != null) {
                    known++
                    if (ex.ip != host || ex.category != cat) { ex.ip = host; ex.category = cat; changed = true }
                } else {
                    val name = l.optJSONObject("metadata")?.optString("name").orEmpty().ifBlank { "Hue " + l.getString("id").take(4) }
                    Store.lamps += Lamp(id, "", name, ip = host, version = VERSION).apply { category = cat }
                    added++; changed = true
                }
            }
        }
        if (changed) { Store.save(); Engine.syncWorkers() }
        val ikea = (0 until lights.length()).count { i ->
            devices[lights.getJSONObject(i).optJSONObject("owner")?.optString("rid")]?.contains("IKEA", true) == true
        }
        val ent = loadAreas()
        val parts = mutableListOf(tr("$added nieuwe lamp(en)", "$added new lamp(s)"))
        if (known > 0) parts += tr("$known al bekend", "$known already known")
        if (ikea > 0) parts += tr("waarvan $ikea van IKEA", "of which $ikea from IKEA")
        return parts.joinToString(", ") + ". " + ent
    }

    /** Leest de Entertainment-ruimtes en zet de kanalen van de gekozen ruimte klaar voor de stream. */
    fun loadAreas(): String {
        try {
            val services = HashMap<String, String>()   // entertainment-dienst -> lamp
            val e = resource("entertainment")
            for (i in 0 until e.length()) e.getJSONObject(i).let { s ->
                s.optJSONObject("renderer_reference")?.optString("rid")?.ifBlank { null }?.let { services[s.getString("id")] = it }
            }
            val confs = resource("entertainment_configuration")
            val found = mutableListOf<Triple<String, String, Int>>()
            val maps = HashMap<String, Map<String, List<Int>>>()
            for (i in 0 until confs.length()) {
                val c = confs.getJSONObject(i)
                val m = LinkedHashMap<String, MutableList<Int>>()
                c.optJSONArray("channels")?.let { ch ->
                    for (j in 0 until ch.length()) {
                        val chan = ch.getJSONObject(j)
                        val members = chan.optJSONArray("members") ?: continue
                        for (k in 0 until members.length()) {
                            val light = services[members.getJSONObject(k).optJSONObject("service")?.optString("rid")] ?: continue
                            m.getOrPut("hue-$light") { mutableListOf() } += chan.getInt("channel_id")
                        }
                    }
                }
                found += Triple(c.getString("id"), c.optJSONObject("metadata")?.optString("name").orEmpty().ifBlank { "Entertainment" }, m.size)
                maps[c.getString("id")] = m
            }
            areas = found
            if (found.isEmpty()) {
                HueStream.channels = emptyMap()
                return tr("Geen Entertainment-ruimte: maak er een in de Hue-app voor de snelste reactie.", "No entertainment area: create one in the Hue app for the fastest response.")
            }
            val pick = found.firstOrNull { it.first == Store.hueArea } ?: found.maxByOrNull { it.third }!!
            if (pick.first != Store.hueArea) { Store.hueArea = pick.first; Store.save() }
            HueStream.channels = maps[pick.first] ?: emptyMap()
            HueStream.areaId = pick.first
            return tr("Entertainment-ruimte \"${pick.second}\" met ${pick.third} lamp(en).", "Entertainment area \"${pick.second}\" with ${pick.third} lamp(s).")
        } catch (e: Exception) {
            HueStream.channels = emptyMap()
            return tr("Entertainment niet gelezen: ", "Could not read entertainment: ") + e.message
        }
    }

    /** Bridge onder een ander adres terugvinden aan zijn id; lampen krijgen het nieuwe adres. */
    private fun relocate(): Boolean {
        val id = Store.hueBridgeId ?: return false
        val host = findBridges().firstOrNull { bridgeId(it) == id } ?: return false
        Store.hueIp = host
        synchronized(Store) { Store.lamps.filter { it.version == VERSION }.forEach { it.ip = host } }
        Store.save(); Engine.syncWorkers()
        return true
    }

    /** Bij het starten van de app de lampen en ruimtes ophalen, daarna af en toe (nieuwe lampen, ander adres). */
    @Volatile private var started = false
    fun startBackground() {
        if (started) return
        started = true
        Thread({
            while (true) {
                if (Store.hueKey != null && !pairing) try { refresh() } catch (_: Exception) { }
                try { Thread.sleep(if (HueStream.channels.isEmpty() && Store.hueKey != null) 60_000L else 600_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "hue-refresh").apply { isDaemon = true; start() }
    }

    // ---------- Vertalen ----------

    /** sRGB (0..1) naar CIE xy; de Bridge past het zelf aan op wat de lamp kan. */
    fun xy(rgb: Int): DoubleArray {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92 }
        val r = lin(Color.red(rgb)); val g = lin(Color.green(rgb)); val b = lin(Color.blue(rgb))
        val x = r * 0.4124 + g * 0.3576 + b * 0.1805
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = r * 0.0193 + g * 0.1192 + b * 0.9505
        val sum = x + y + z
        return if (sum <= 0) doubleArrayOf(0.3127, 0.3290) else doubleArrayOf(x / sum, y / sum)
    }

    /** CIE xy naar een volle sRGB-kleur (helderheid apart). */
    fun rgb(x: Double, y: Double): Int {
        if (y <= 0.0001) return Color.WHITE
        val bigX = x / y; val bigZ = (1 - x - y) / y
        val r = bigX * 3.2406 - 1.5372 - bigZ * 0.4986
        val g = -bigX * 0.9689 + 1.8758 + bigZ * 0.0415
        val b = bigX * 0.0557 - 0.2040 + bigZ * 1.0570
        val m = maxOf(r, g, b, 1e-6)
        fun gam(v: Double): Int { val c = (v / m).coerceIn(0.0, 1.0); return ((if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055) * 255).roundToInt() }
        return Color.rgb(gam(r), gam(g), gam(b))
    }

    /** Tuya-temperatuur (0 warm .. 1000 koel) naar mirek (500 = 2000 K, 153 = 6500 K). */
    fun mirek(t: Int) = (500 - t.coerceIn(0, 1000) * 347 / 1000).coerceIn(153, 500)

    /** Tuya-functies naar een Hue-opdracht. mode = laatst bekende modus; ms = overgangstijd. */
    fun body(dps: Map<String, Any>, mode: String, category: String, ms: Long): JSONObject {
        val o = JSONObject()
        val on = dps["20"] as? Boolean
        if (on == false) return o.put("on", JSONObject().put("on", false)).put("dynamics", JSONObject().put("duration", ms))
        val m = dps["21"] as? String ?: mode
        val c = dps["24"] as? String
        if (m == "colour" && c != null && c.length >= 12) {
            val h = c.substring(0, 4).toInt(16); val s = c.substring(4, 8).toInt(16); val v = c.substring(8, 12).toInt(16)
            o.put("dimming", JSONObject().put("brightness", (v / 10.0).coerceIn(1.0, 100.0)))
            when (category) {
                "hue-color" -> {
                    val p = xy(Color.HSVToColor(floatArrayOf(h.toFloat(), (s / 1000f).coerceIn(0f, 1f), 1f)))
                    o.put("color", JSONObject().put("xy", JSONObject().put("x", p[0]).put("y", p[1])))
                }
                // Alleen wit: rood/oranje/geel = warm, blauw = koel, weinig kleur = neutraal
                "hue-ct" -> {
                    val warmth = if (s < 150) 500 else if (h < 75 || h > 300) 150 else if (h in 170..260) 850 else 500
                    o.put("color_temperature", JSONObject().put("mirek", mirek(warmth)))
                }
            }
        } else if (m == "white") {
            (dps["22"] as? Number)?.let { o.put("dimming", JSONObject().put("brightness", (it.toInt() / 10.0).coerceIn(1.0, 100.0))) }
            if (category != "hue-dim") (dps["23"] as? Number)?.let { o.put("color_temperature", JSONObject().put("mirek", mirek(it.toInt()))) }
        }
        if (on == true || o.length() > 0) o.put("on", JSONObject().put("on", true))
        o.put("dynamics", JSONObject().put("duration", ms))
        return o
    }

    /** Hue-lamp (v2-resource) naar Tuya-functies, voor de weergave en het terugzetten na sync of scène. */
    fun dps(l: JSONObject): JSONObject {
        val d = JSONObject().put("20", l.optJSONObject("on")?.optBoolean("on", true) ?: true)
        val bri = ((l.optJSONObject("dimming")?.optDouble("brightness", 100.0) ?: 100.0) * 10).roundToInt().coerceIn(10, 1000)
        val ct = l.optJSONObject("color_temperature")
        val mk = if (ct != null && ct.optBoolean("mirek_valid") && !ct.isNull("mirek")) ct.optInt("mirek") else null
        val xy = l.optJSONObject("color")?.optJSONObject("xy")
        if (mk == null && xy != null) {
            val hsv = FloatArray(3); Color.colorToHSV(rgb(xy.optDouble("x"), xy.optDouble("y")), hsv)
            d.put("21", "colour").put("24", TuyaLamp.hsvHex(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), bri))
        } else {
            d.put("21", "white").put("22", bri).put("23", if (mk != null) ((500 - mk) * 1000 / 347).coerceIn(0, 1000) else 500)
        }
        return d
    }

    /** Tuya-functies naar 16-bit RGB voor de stream; null = deze opdracht verandert de kleur niet. */
    fun rgb16(dps: Map<String, Any>, mode: String): IntArray? {
        if (dps["20"] == false) return intArrayOf(0, 0, 0)
        val m = dps["21"] as? String ?: mode
        val c = dps["24"] as? String
        val rgb = if (m == "colour" && c != null && c.length >= 12) {
            val h = c.substring(0, 4).toInt(16); val s = c.substring(4, 8).toInt(16); val v = c.substring(8, 12).toInt(16)
            Color.HSVToColor(floatArrayOf(h.toFloat(), (s / 1000f).coerceIn(0f, 1f), (v / 1000f).coerceIn(0f, 1f)))
        } else if (m == "white" && dps.containsKey("22")) {
            val v = ((dps["22"] as Number).toInt() / 1000f).coerceIn(0f, 1f)
            val t = ((dps["23"] as? Number)?.toInt() ?: 500) / 1000f
            Color.rgb(((255 - 35 * t) * v).roundToInt(), ((190 + 50 * t) * v).roundToInt(), ((110 + 145 * t) * v).roundToInt())
        } else return null
        return intArrayOf(Color.red(rgb) * 257, Color.green(rgb) * 257, Color.blue(rgb) * 257)
    }
}
