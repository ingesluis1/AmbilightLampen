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

    private fun call(method: String, path: String, token: String?): JSONObject {
        val id = Store.cloudId ?: throw IllegalStateException("geen Access ID")
        val secret = Store.cloudSecret ?: throw IllegalStateException("geen Access Secret")
        val host = HOSTS[Store.cloudRegion] ?: HOSTS.getValue("eu")
        val t = System.currentTimeMillis().toString()
        val stringToSign = "$method\n${sha256("")}\n\n$path"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val sign = mac.doFinal((id + (token ?: "") + t + stringToSign).toByteArray()).joinToString("") { "%02X".format(it) }
        val c = URL(host + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 10000
        c.setRequestProperty("client_id", id); c.setRequestProperty("sign", sign); c.setRequestProperty("t", t)
        c.setRequestProperty("sign_method", "HMAC-SHA256"); c.setRequestProperty("nonce", "")
        if (token != null) c.setRequestProperty("access_token", token)
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
        val j = JSONObject(body)
        if (!j.optBoolean("success")) throw IllegalStateException(j.optString("msg", "fout $code"))
        return j
    }

    /** Haalt alle lampen op en voegt nieuwe toe of werkt sleutels bij. Geeft een melding voor de gebruiker. */
    @Synchronized
    fun refresh(): String {
        val token = call("GET", "/v1.0/token?grant_type=1", null).getJSONObject("result").getString("access_token")
        val devices = mutableListOf<JSONObject>()
        try {
            var lastRow = ""
            do {
                val r = call("GET", "/v1.0/iot-01/associated-users/devices?size=50" + (if (lastRow.isNotEmpty()) "&last_row_key=$lastRow" else ""), token)
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
                if (d.optString("category") !in LIGHTS) { skipped++; continue }
                if (id in Store.hidden) continue
                val ex = Store.lamps.firstOrNull { it.id == id }
                if (ex != null) {
                    if (ex.key != key) { ex.key = key; updated++ }
                } else if (d.optBoolean("online", true)) {   // offline registraties (oude lampen) overslaan
                    Store.lamps += Lamp(id, key, d.optString("name", id))
                    added += d.optString("name", id)
                }
            }
        }
        Store.save(); Engine.syncWorkers()
        return buildString {
            append(if (added.isEmpty()) tr("Geen nieuwe lampen gevonden.", "No new lamps found.") else tr("Nieuw: ", "New: ") + added.joinToString(", ") + tr(". Hang ze op via Lampen.", ". Place them under Lamps."))
            if (updated > 0) append(tr(" $updated sleutel(s) bijgewerkt.", " $updated key(s) updated."))
            if (added.isNotEmpty()) append(tr(" Het ip-adres wordt binnen een halve minuut vanzelf gevonden.", " The IP address is found automatically within half a minute."))
        }
    }
}
