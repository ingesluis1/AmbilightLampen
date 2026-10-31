package nl.stacklines.tvambilight

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tuya-cloud (het project op iot.tuya.com): haalt namen en local keys van de lampen op,
 * zodat een nieuwe lamp alleen in Smart Life gekoppeld hoeft te worden.
 * Het ip-adres komt daarna vanzelf via Discovery.
 */
object TuyaCloud {
    private val HOSTS = mapOf(
        "eu" to "https://openapi.tuyaeu.com", "eu-w" to "https://openapi-weaz.tuyaeu.com",
        "us" to "https://openapi.tuyaus.com", "us-e" to "https://openapi-ueaz.tuyaus.com",
        "cn" to "https://openapi.tuyacn.com", "in" to "https://openapi.tuyain.com", "sg" to "https://openapi-sg.iotbing.com"
    )
    /** Tuya-categorieën die lampen zijn (lamp, ledstrip, sfeerlicht, plafondlamp, lichtsnoer). */
    private val LIGHTS = setOf("dj", "dd", "fwd", "xdd", "dc", "gyd", "tyndj", "fsd", "fwl")

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** [signPath]: het pad zoals het ondertekend wordt (Tuya: vóór url-codering); standaard gelijk aan [path]. */
    private fun call(method: String, path: String, token: String?, body: String = "", signPath: String = path): JSONObject {
        val id = Store.cloudId ?: throw IllegalStateException("geen Access ID")
        val secret = Store.cloudSecret ?: throw IllegalStateException("geen Access Secret")
        val host = HOSTS[Store.cloudRegion] ?: HOSTS.getValue("eu")
        val t = System.currentTimeMillis().toString()
        val stringToSign = "$method\n${sha256(body)}\n\n$signPath"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val sign = mac.doFinal((id + (token ?: "") + t + stringToSign).toByteArray()).joinToString("") { "%02X".format(it) }
        val c = URL(host + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 10000
        c.setRequestProperty("client_id", id); c.setRequestProperty("sign", sign); c.setRequestProperty("t", t)
        c.setRequestProperty("sign_method", "HMAC-SHA256"); c.setRequestProperty("nonce", "")
        if (token != null) c.setRequestProperty("access_token", token)
        if (body.isNotEmpty()) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val resp = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
        val j = JSONObject(resp)
        if (!j.optBoolean("success")) throw IllegalStateException(j.optString("msg", "fout $code"))
        return j
    }

    /**
     * Laatste redmiddel als de lamp lokaal niet reageert: via de cloud aanzetten.
     * Geeft terug of de cloud de lamp online ziet (de opdracht gaat dan via Tuya's eigen verbinding met de lamp).
     */
    fun powerOn(deviceId: String): Boolean {
        val token = call("GET", "/v1.0/token?grant_type=1", null).getJSONObject("result").getString("access_token")
        val online = call("GET", "/v1.0/devices/$deviceId", token).optJSONObject("result")?.optBoolean("online") == true
        if (online) {
            val cmd = JSONObject().put("commands", JSONArray().put(JSONObject().put("code", "switch_led").put("value", true))).toString()
            call("POST", "/v1.0/devices/$deviceId/commands", token, cmd)
        }
        return online
    }

    /**
     * Geschiedenis van een apparaat uit de cloud: alle gemelde standen (type 7) tussen [startMs] en [endMs],
     * oudste eerst. Elke melding: event_time (ms), code, value. Hooguit 50 pagina's van 100.
     */
    fun deviceLogs(deviceId: String, startMs: Long, endMs: Long): List<JSONObject> {
        val token = call("GET", "/v1.0/token?grant_type=1", null).getJSONObject("result").getString("access_token")
        val out = ArrayList<JSONObject>(); var row: String? = null
        for (page in 0 until 50) {
            // Parameters op alfabet (dat hoort bij de handtekening)
            // Ondertekenen met de ruwe waarde, versturen url-gecodeerd (de row key kan + / = bevatten)
            fun q(rk: String?) = "end_time=$endMs&query_type=1&size=100" + (rk?.let { "&start_row_key=$it" } ?: "") + "&start_time=$startMs&type=7"
            val base = "/v1.0/devices/$deviceId/logs?"
            val r = call("GET", base + q(row?.let { java.net.URLEncoder.encode(it, "UTF-8") }), token, signPath = base + q(row)).optJSONObject("result") ?: break
            r.optJSONArray("logs")?.let { a -> for (i in 0 until a.length()) out += a.getJSONObject(i) }
            if (!r.optBoolean("has_next")) break
            row = r.optString("next_row_key").ifEmpty { break }
        }
        return out.sortedBy { it.optLong("event_time") }
    }

    /** Haalt alle lampen op en voegt nieuwe toe of werkt sleutels bij. Geeft een melding voor de gebruiker. */
    @Synchronized
    fun refresh(): String {
        val token = call("GET", "/v1.0/token?grant_type=1", null).getJSONObject("result").getString("access_token")
        val devices = mutableListOf<JSONObject>()
        try {
            var lastRow = ""
            do {
                // Tuya ondertekent de parameters op alfabetische volgorde: last_row_key voor size
                val q = if (lastRow.isNotEmpty()) "last_row_key=$lastRow&size=50" else "size=50"
                val r = call("GET", "/v1.0/iot-01/associated-users/devices?$q", token)
                    .getJSONObject("result")
                r.optJSONArray("devices")?.let { a -> for (i in 0 until a.length()) devices += a.getJSONObject(i) }
                lastRow = r.optString("last_row_key")
            } while (r.optBoolean("has_more") && lastRow.isNotEmpty())
        } catch (e: Exception) {
            // Oudere projecten: via een bekende lamp de gebruiker opzoeken
            val known = Store.lampsSnapshot().firstOrNull() ?: throw e
            val uid = call("GET", "/v1.0/devices/${known.id}", token).getJSONObject("result").getString("uid")
            val a: JSONArray = call("GET", "/v1.0/users/$uid/devices", token).getJSONArray("result")
            for (i in 0 until a.length()) devices += a.getJSONObject(i)
        }
        val added = mutableListOf<String>(); var updated = 0; var skipped = 0
        synchronized(Store) {
            for (d in devices) {
                val id = d.optString("id"); val key = d.optString("local_key")
                if (id.isBlank() || key.length != 16) continue
                if (d.optString("category") !in LIGHTS && d.optString("category") !in Lamp.AIRCO && d.optString("category") !in Lamp.LITTER && d.optString("category") !in Lamp.PLUG) { skipped++; continue }
                if (id in Store.hidden || id in Store.forgotten) continue
                val ex = Store.lamps.firstOrNull { it.id == id }
                if (ex != null) {
                    if (ex.key != key) { ex.key = key; updated++ }
                    ex.category = d.optString("category", ex.category)
                } else if (d.optBoolean("online", true)) {   // offline registraties (oude lampen) overslaan
                    val name = d.optString("name", id).replace(Regex("\\s+"), " ").trim().take(60).ifBlank { id }
                    Store.lamps += Lamp(id, key, name).apply {
                        category = d.optString("category")
                        // Airco: standaard-uiterlijk op de kaart (mobiel of aan de muur, wit); kiezen kan in het menu van het apparaat
                        if (kind == "airco") look = if (category == "ydkt") "airco-mobiel-wit" else "airco-wand-wit"
                        if (kind == "kattenbak") look = "kattenbak"
                        if (kind == "stekker") look = "stekker"
                    }
                    added += name
                }
            }
        }
        Store.save(); Engine.syncWorkers()
        if (added.isNotEmpty()) Discovery.lookupNew()
        return buildString {
            append(if (added.isEmpty()) tr("Geen nieuwe apparaten gevonden.", "No new devices found.") else tr("Nieuw: ", "New: ") + added.joinToString(", ") + tr(". Zet ze in hun kamer op de kaart.", ". Put them in their room on the map."))
            if (updated > 0) append(tr(" $updated sleutel(s) bijgewerkt.", " $updated key(s) updated."))
            if (added.isNotEmpty()) append(tr(" Het ip-adres wordt binnen een halve minuut vanzelf gevonden.", " The IP address is found automatically within half a minute."))
        }
    }
}
