package nl.stacklines.tvambilight

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * IKEA DIRIGERA-hub (lokale API op https-poort 8443). Koppelen: de app vraagt een code, je drukt op de actieknop
 * onder op de hub, en dan krijgt de app een sleutel (OAuth met PKCE). Net als bij Hue denkt de rest van de app in
 * Tuya-functies; hier wordt dat vertaald naar de attributen van de hub (isOn, lightLevel, colorHue, ...).
 *
 * Lamp-id = "ikea-<id op de hub>", ip = adres van de hub. Categorie: ikea-color, ikea-ct (alleen wit), ikea-dim.
 * Het certificaat van de hub wordt bij het koppelen vastgelegd (Store.ikeaCert), zoals bij de Hue Bridge.
 */
object Dirigera : Hub {
    const val VERSION = "dirigera"
    override val prefix = "ikea-"
    private const val PORT = 8443
    /** Rem voor alle lampen samen: de hub krijgt hooguit ±10 opdrachten per seconde. */
    private const val SLOT_MS = 100L
    /** Kleurtemperatuur van IKEA-lampen (Kelvin); de meeste witte lampen kunnen 2200..4000. */
    private const val K_WARM = 2200
    private const val K_COOL = 4000

    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }

    @Volatile var status = ""
    @Volatile var pairing = false
        private set

    class IkeaException(msg: String) : Exception(msg)

    // ---------- Verzoeken ----------

    private val anyHost = HostnameVerifier { _, _ -> true }

    /** Verzoek aan de hub. pin = verwachte vingerafdruk (null = nieuwe koppeling, alles accepteren en via seen teruggeven). */
    private fun call(method: String, host: String, path: String, body: String? = null, form: Boolean = false,
                     token: String? = Store.ikeaToken, pin: String? = Store.ikeaCert, seen: ((String) -> Unit)? = null,
                     timeout: Int = 3000): Pair<Int, String> {
        val c = URL("https://$host:$PORT/v1$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = Tls.pinned(pin, seen); c.hostnameVerifier = anyHost
        c.requestMethod = method   // Android (OkHttp) kent ook PATCH
        c.connectTimeout = timeout; c.readTimeout = timeout
        // De sleutel gaat alleen mee naar een hub waarvan het certificaat vastligt
        if (pin != null && token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = try { c.responseCode } catch (e: javax.net.ssl.SSLException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains(Tls.PIN_MISMATCH) == true })
                throw IkeaException(tr("Het certificaat van de DIRIGERA is veranderd. Ontkoppel en koppel opnieuw als dit echt je hub is.",
                    "The DIRIGERA's certificate has changed. Unpair and pair again if this really is your hub."))
            throw e
        }
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return code to text
    }

    private fun host() = Store.ikeaIp ?: throw IkeaException(tr("Nog geen DIRIGERA gekoppeld", "No DIRIGERA paired yet"))

    private val slotLock = Object()
    private var nextSlot = 0L
    override fun waitTurn() {
        val wait = synchronized(slotLock) {
            val now = SystemClock.elapsedRealtime()
            val t = max(now, nextSlot); nextSlot = t + SLOT_MS; t - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    // ---------- Zoeken en koppelen ----------

    /** Zoekt de hub via mDNS (dienst _ihsp._tcp); geeft het adres of null. */
    fun findHub(timeoutMs: Long = 5000): String? {
        val nsd = ctx?.getSystemService(NsdManager::class.java) ?: return null
        val found = CountDownLatch(1)
        var result: String? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        s.host?.hostAddress?.takeIf { Hue.isLocal(it) }?.let { result = it; found.countDown() }
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, err: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, err: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(t: String, err: Int) {}
        }
        return try {
            nsd.discoverServices("_ihsp._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
            result
        } catch (_: Exception) { null } finally { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { } }
    }

    fun startPair(ip: String?) {
        synchronized(this) { if (pairing) return; pairing = true }
        Thread({
            status = try { pair(ip?.trim()?.ifBlank { null }) } catch (e: Exception) { tr("Koppelen mislukt: ", "Pairing failed: ") + e.message }
            pairing = false
        }, "ikea-pair").apply { isDaemon = true; start() }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun pair(ip: String?): String {
        if (ip != null && !Hue.isLocal(ip)) return tr("Vul het ip-adres van de DIRIGERA in, bijvoorbeeld 192.168.1.30.", "Enter the DIRIGERA's IP address, for example 192.168.1.30.")
        val host = ip ?: run { status = tr("DIRIGERA zoeken...", "Looking for the DIRIGERA..."); findHub() }
            ?: return tr("Geen DIRIGERA gevonden. Zit hij in hetzelfde netwerk als de tv? Je kunt ook het ip-adres invullen (staat in de router).",
                "No DIRIGERA found. Is it on the same network as the TV? You can also enter its IP address (see your router).")
        // PKCE: een geheim (verifier) en de hash ervan (challenge)
        val verifier = b64(ByteArray(96).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        var cert: String? = null
        fun authorize(): String {
            val (code, text) = call("GET", host, "/oauth/authorize?audience=homesmart.local&response_type=code&code_challenge=$challenge&code_challenge_method=S256",
                token = null, pin = null, seen = { cert = it })
            if (code != 200) throw IkeaException(tr("De hub antwoordt niet zoals verwacht (HTTP $code)", "The hub doesn't answer as expected (HTTP $code)"))
            return JSONObject(text).getString("code")
        }
        var authCode = authorize()
        status = tr("Druk nu op de actieknop onder op de DIRIGERA", "Now press the action button on the bottom of the DIRIGERA")
        val name = URLEncoder.encode("Ambilight Lampen", "UTF-8")
        val end = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < end) {
            Thread.sleep(1500)
            val form = "code=${URLEncoder.encode(authCode, "UTF-8")}&name=$name&grant_type=authorization_code&code_verifier=$verifier"
            val (code, text) = call("POST", host, "/oauth/token", form, form = true, token = null, pin = null, seen = { cert = it })
            val token = if (code == 200) try { JSONObject(text).optString("access_token").ifBlank { null } } catch (_: Exception) { null } else null
            if (token != null && cert != null) {
                Store.ikeaIp = host; Store.ikeaToken = token; Store.ikeaCert = cert
                Store.save()
                moveLampsTo(host)
                return tr("DIRIGERA gekoppeld. ", "DIRIGERA paired. ") + refresh()
            }
            // Nog niet op de knop gedrukt: opnieuw proberen. Is de code verlopen of afgekeurd, dan een nieuwe vragen.
            if (code == 400 || code == 401) authCode = authorize()
        }
        return tr("De knop is niet ingedrukt. Probeer het opnieuw.", "The button was not pressed. Try again.")
    }

    fun unpair(): String {
        synchronized(Store) {
            Store.lamps.removeAll { it.version == VERSION }
            Store.hidden.keys.removeAll { it.startsWith(prefix) }
            Store.ikeaToken = null; Store.ikeaCert = null
        }
        reach = emptyMap(); status = ""
        Store.save(); Engine.syncWorkers()
        return tr("DIRIGERA ontkoppeld", "DIRIGERA unpaired")
    }

    private fun moveLampsTo(host: String) {
        synchronized(Store) { Store.lamps.filter { it.version == VERSION }.forEach { it.ip = host } }
        Store.save(); Engine.syncWorkers()
    }

    // ---------- Lampen ----------

    private fun devices(): JSONArray {
        val (code, text) = call("GET", host(), "/devices")
        if (code == 401 || code == 403) throw IkeaException(tr("De DIRIGERA kent deze koppeling niet meer. Koppel opnieuw.", "The DIRIGERA no longer knows this pairing. Pair again."))
        if (code != 200) throw IkeaException("DIRIGERA: HTTP $code")
        return JSONArray(text)
    }

    private fun isLight(d: JSONObject) = d.optString("deviceType") == "light" || d.optString("type") == "light"

    fun refresh(): String {
        if (Store.ikeaToken == null) return tr("Nog geen DIRIGERA gekoppeld", "No DIRIGERA paired yet")
        val list = devices()
        val host = host()
        var added = 0; var known = 0; var changed = false
        val r = HashMap<String, Boolean>()
        synchronized(Store) {
            for (i in 0 until list.length()) {
                val d = list.getJSONObject(i)
                if (!isLight(d)) continue
                r[d.getString("id")] = d.optBoolean("isReachable", true)
                val id = prefix + d.getString("id")
                val can = d.optJSONObject("capabilities")?.optJSONArray("canReceive")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                val cat = when { "colorHue" in can -> "ikea-color"; "colorTemperature" in can -> "ikea-ct"; else -> "ikea-dim" }
                if (id in Store.hidden || id in Store.forgotten) continue
                val ex = Store.lamps.firstOrNull { it.id == id }
                if (ex != null) {
                    known++
                    if (ex.ip != host || ex.category != cat) { ex.ip = host; ex.category = cat; changed = true }
                } else {
                    val name = d.optJSONObject("attributes")?.optString("customName").orEmpty().trim().take(60).ifBlank { "IKEA " + d.getString("id").take(4) }
                    Store.lamps += Lamp(id, "", name, ip = host, version = VERSION).apply { category = cat }
                    added++; changed = true
                }
            }
        }
        reach = r; reachAt = SystemClock.elapsedRealtime()
        if (changed) { Store.save(); Engine.syncWorkers() }
        return tr("$added nieuwe lamp(en)", "$added new lamp(s)") + (if (known > 0) tr(", $known al bekend", ", $known already known") else "") + "."
    }

    @Volatile private var reach: Map<String, Boolean> = emptyMap()
    @Volatile private var reachAt = 0L

    override fun reachable(id: String): Boolean? {
        val now = SystemClock.elapsedRealtime()
        val fetch = synchronized(this) { (now - reachAt > 10_000).also { if (it) reachAt = now } }
        if (fetch) try {
            val list = devices(); val r = HashMap<String, Boolean>()
            for (i in 0 until list.length()) list.getJSONObject(i).let { if (isLight(it)) r[it.getString("id")] = it.optBoolean("isReachable", true) }
            reach = r
        } catch (_: Exception) { }
        return reach[id]
    }

    override fun light(id: String): JSONObject? {
        if (Store.ikeaCert == null) return null
        val (code, text) = call("GET", host(), "/devices/$id", timeout = 2000)
        if (code != 200) return null
        return JSONObject(text).optJSONObject("attributes")?.let { dps(it) }
    }

    /** Lampen die geen gecombineerde opdracht (kleur en helderheid samen) aannemen; die krijgen ze apart. */
    private val splitLamps = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun setLight(id: String, dps: Map<String, Any>, mode: String, category: String, ms: Long): Boolean {
        val a = attrs(dps, mode, category)
        if (a.length() == 0) return true
        val parts = if (id in splitLamps) a.keys().asSequence().toList().groupBy { k -> if (k == "colorHue" || k == "colorSaturation") "color" else k }
            .entries.sortedBy { if (it.key == "isOn") 0 else 1 }   // eerst aan, dan helderheid en kleur
            .map { (_, ks) -> JSONObject().apply { ks.forEach { put(it, a.get(it)) } } } else listOf(a)
        for (p in parts) {
            val body = JSONArray().put(JSONObject().put("attributes", p).put("transitionTime", ms))
            val (code, text) = call("PATCH", host(), "/devices/$id", body.toString(), timeout = 2000)
            if (code == 429 || code == 503) return false
            if (code == 400 && parts.size == 1 && a.length() > 1) { splitLamps += id; return setLight(id, dps, mode, category, ms) }
            if (code !in 200..299) throw IkeaException("DIRIGERA: HTTP $code " + text.take(80))
        }
        return true
    }

    // ---------- Vertalen ----------

    private fun kelvin(t: Int) = K_WARM + t.coerceIn(0, 1000) * (K_COOL - K_WARM) / 1000
    private fun tuyaTemp(k: Int) = ((k - K_WARM) * 1000 / (K_COOL - K_WARM)).coerceIn(0, 1000)

    /** Tuya-functies naar attributen van de hub. */
    fun attrs(dps: Map<String, Any>, mode: String, category: String): JSONObject {
        val o = JSONObject()
        val on = dps["20"] as? Boolean
        if (on == false) return o.put("isOn", false)
        val m = dps["21"] as? String ?: mode
        val c = dps["24"] as? String
        if (m == "colour" && c != null && c.length >= 12) {
            val (h, s, v) = TuyaLamp.parseHsvHex(c)
            o.put("lightLevel", (v / 10.0).roundToInt().coerceIn(1, 100))
            when (category) {
                "ikea-color" -> o.put("colorHue", h.toDouble()).put("colorSaturation", (s / 1000.0).coerceIn(0.0, 1.0))
                // Alleen wit: rood/oranje/geel = warm, blauw = koel, weinig kleur = neutraal
                "ikea-ct" -> o.put("colorTemperature", kelvin(if (s < 150) 500 else if (h < 75 || h > 300) 100 else if (h in 170..260) 900 else 500))
            }
        } else if (m == "white") {
            (dps["22"] as? Number)?.let { o.put("lightLevel", (it.toInt() / 10.0).roundToInt().coerceIn(1, 100)) }
            if (category != "ikea-dim") (dps["23"] as? Number)?.let { o.put("colorTemperature", kelvin(it.toInt())) }
        }
        if (on == true || o.length() > 0) o.put("isOn", true)
        return o
    }

    /** Attributen van de hub naar Tuya-functies (weergave en terugzetten). */
    fun dps(a: JSONObject): JSONObject {
        val d = JSONObject().put("20", a.optBoolean("isOn", true))
        val bri = (a.optInt("lightLevel", 100) * 10).coerceIn(10, 1000)
        if (a.optString("colorMode") == "color" && a.has("colorHue")) {
            d.put("21", "colour").put("24", TuyaLamp.hsvHex(a.optDouble("colorHue").roundToInt() % 360, (a.optDouble("colorSaturation", 1.0) * 1000).roundToInt().coerceIn(0, 1000), bri))
        } else {
            d.put("21", "white").put("22", bri).put("23", if (a.has("colorTemperature")) tuyaTemp(a.optInt("colorTemperature")) else 500)
        }
        return d
    }

    /** Bij het starten: lampen ophalen, daarna af en toe (nieuwe lampen, namen). */
    @Volatile private var started = false
    fun startBackground() {
        if (started) return
        started = true
        Thread({
            while (true) {
                var ok = true
                if (Store.ikeaToken != null && !pairing) try { refresh() } catch (_: Exception) { ok = false }
                try { Thread.sleep(if (ok) 600_000L else 60_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "ikea-refresh").apply { isDaemon = true; start() }
    }
}
