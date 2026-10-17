package nl.stacklines.tvambilight

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/** Randkleuren van het beeld: per zone r, g, b (0..255). Links en rechts van onder naar boven, boven van links naar rechts. */
class ZoneSample(val left: List<IntArray>, val top: List<IntArray>, val right: List<IntArray>)

/**
 * Philips JointSpace-API (versie 6) op poort 1926: https met een eigen certificaat en digest-login.
 * Op de tv zelf is dat 127.0.0.1; op de emulator het ip-adres van de tv.
 */
object PhilipsTv {
    private val sharedKey = android.util.Base64.decode(
        "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==", 0)

    private var challenge: Map<String, String>? = null
    private var nc = 0
    private var pairState: JSONObject? = null

    private class Resp(val code: Int, val body: String, val wwwAuth: String?)

    private fun exec(host: String, method: String, path: String, body: String?, auth: String?): Resp {
        val c = URL("https://$host:1926/6$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = Tls.trustAll
        c.setHostnameVerifier { _, _ -> true }
        c.connectTimeout = 2000; c.readTimeout = 3000
        c.requestMethod = method
        if (auth != null) c.setRequestProperty("Authorization", auth)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Resp(code, text, c.getHeaderField("WWW-Authenticate"))
    }

    @Synchronized
    private fun call(method: String, path: String, body: String? = null, user: String? = null, pass: String? = null): Resp {
        val host = Store.tvHost
        var auth = if (user != null && pass != null && challenge != null) digest(method, "/6$path", user, pass) else null
        var r = exec(host, method, path, body, auth)
        if (r.code == 401 && user != null && pass != null && r.wwwAuth != null) {
            challenge = parseChallenge(r.wwwAuth!!); nc = 0
            auth = digest(method, "/6$path", user, pass)
            r = exec(host, method, path, body, auth)
        }
        return r
    }

    /** Huidige randkleuren, of null als de tv niet bereikbaar of niet gekoppeld is. */
    fun measured(): ZoneSample? {
        val u = Store.tvUser ?: return null
        val p = Store.tvPass ?: return null
        return try {
            // Bron: de randen van het beeld (measured) of wat Ambilight zelf uitstraalt (processed)
            val r = call("GET", if (Store.settings.source == "ambilight") "/ambilight/processed" else "/ambilight/measured", user = u, pass = p)
            if (r.code != 200) return null
            val l = JSONObject(r.body).getJSONObject("layer1")
            fun side(name: String): List<IntArray> {
                val o = l.optJSONObject(name) ?: return emptyList()
                return (0 until o.length()).mapNotNull { i ->
                    o.optJSONObject(i.toString())?.let { intArrayOf(it.optInt("r"), it.optInt("g"), it.optInt("b")) }
                }
            }
            ZoneSample(side("left"), side("top"), side("right"))
        } catch (_: Exception) { null }
    }

    /** Stap 1: de tv toont een pincode van 4 cijfers. */
    fun pairRequest(): String {
        val id = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("")
        val req = JSONObject().put("scope", org.json.JSONArray(listOf("read", "write", "control"))).put("device", device(id))
        val r = call("POST", "/pair/request", req.toString())
        val j = JSONObject(r.body)
        if (j.optString("error_id") != "SUCCESS") return tr("De tv weigerde: ", "The TV refused: ") + j.optString("error_text", r.body)
        pairState = JSONObject().put("id", id).put("key", j.getString("auth_key")).put("ts", j.get("timestamp"))
        return "OK"
    }

    /** Stap 2: pincode bevestigen. Bij succes worden de inloggegevens opgeslagen. */
    fun pairGrant(pin: String): String {
        val st = pairState ?: return tr("Start eerst het koppelen.", "Start pairing first.")
        val id = st.getString("id"); val key = st.getString("key"); val ts = st.get("ts").toString()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(sharedKey, "HmacSHA1")) }
        val sig = android.util.Base64.encodeToString(mac.doFinal((ts + pin).toByteArray()), android.util.Base64.NO_WRAP)
        val body = JSONObject()
            .put("auth", JSONObject().put("auth_AppId", "1").put("pin", pin).put("auth_timestamp", st.get("ts")).put("auth_signature", sig))
            .put("device", device(id))
        challenge = null
        val r = call("POST", "/pair/grant", body.toString(), id, key)
        val j = try { JSONObject(r.body) } catch (_: Exception) { JSONObject() }
        if (r.code == 200 && j.optString("error_id") == "SUCCESS") {
            Store.tvUser = id; Store.tvPass = key; Store.save()
            pairState = null; challenge = null
            return "OK"
        }
        return tr("Koppelen mislukt", "Pairing failed") + " (${r.code}): " + j.optString("error_text", r.body.take(120))
    }

    private fun device(id: String) = JSONObject().put("device_name", "ambilight-lampen").put("device_os", "Android")
        .put("app_id", "app.id").put("app_name", "Ambilight Lampen").put("type", "native").put("id", id)

    private fun parseChallenge(h: String): Map<String, String> {
        val m = HashMap<String, String>()
        Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(h.removePrefix("Digest").trim()).forEach {
            m[it.groupValues[1]] = it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        return m
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val o = CharArray(d.size * 2)
        for (i in d.indices) { val v = d[i].toInt() and 0xff; o[i * 2] = HEX[v ushr 4]; o[i * 2 + 1] = HEX[v and 15] }
        return String(o)
    }
    /** HA1 en HA2 veranderen alleen met de challenge of het adres; de meting draait tot 25 keer per seconde. */
    private val fixedHashes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun md5Fixed(s: String) = fixedHashes.getOrPut(s) { md5(s) }

    private fun digest(method: String, uri: String, user: String, pass: String): String {
        val ch = challenge!!
        val realm = ch["realm"] ?: ""; val nonce = ch["nonce"] ?: ""
        val ha1 = md5Fixed("$user:$realm:$pass"); val ha2 = md5Fixed("$method:$uri")
        val qop = ch["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
        if (qop != null) {
            nc++
            val ncs = "%08x".format(nc)
            val cnonce = md5(System.nanoTime().toString()).take(16)
            sb.append(", qop=auth, nc=$ncs, cnonce=\"$cnonce\", response=\"${md5("$ha1:$nonce:$ncs:$cnonce:auth:$ha2")}\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        ch["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        sb.append(", algorithm=MD5")
        return sb.toString()
    }

    init { HttpURLConnection.setFollowRedirects(false) }
}
