package nl.stacklines.tvambilight

import android.graphics.Color
import android.os.SystemClock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.roundToInt

/**
 * WiZ-lampen (Philips/Signify) praten JSON over UDP op poort 38899, zonder sleutel.
 * De rest van de app denkt in Tuya-functies (20 aan/uit, 21 modus, 22 helderheid, 23 kleurtemperatuur, 24 kleur);
 * hier wordt dat vertaald naar setPilot en terug.
 */
object Wiz {
    const val PORT = 38899
    const val VERSION = "wiz"

    /** Tuya-functies naar setPilot-parameters. mode = laatst bekende modus van de lamp ("white" of "colour"). */
    fun pilot(dps: Map<String, Any>, mode: String): JSONObject {
        val p = JSONObject()
        val on = dps["20"] as? Boolean
        if (on == false) return p.put("state", false)
        val m = dps["21"] as? String ?: mode
        val c = dps["24"] as? String
        if (m == "colour" && c != null && c.length >= 12) {
            val h = c.substring(0, 4).toInt(16); val s = c.substring(4, 8).toInt(16); val v = c.substring(8, 12).toInt(16)
            val rgb = Color.HSVToColor(floatArrayOf(h.toFloat(), (s / 1000f).coerceIn(0f, 1f), 1f))
            // WiZ dimt niet onder 10%; daaronder ook de kleur zelf zachter maken
            val dim = (v / 10.0).roundToInt().coerceIn(10, 100)
            val k = if (v < 100) (v / 100.0).coerceAtLeast(0.15) else 1.0
            p.put("r", (Color.red(rgb) * k).roundToInt()).put("g", (Color.green(rgb) * k).roundToInt()).put("b", (Color.blue(rgb) * k).roundToInt())
            p.put("dimming", dim)
        } else if (m == "white" && (dps.containsKey("22") || dps.containsKey("23"))) {
            (dps["22"] as? Number)?.let { p.put("dimming", (it.toInt() / 10.0).roundToInt().coerceIn(10, 100)) }
            (dps["23"] as? Number)?.let { p.put("temp", 2200 + it.toInt().coerceIn(0, 1000) * 43 / 10) }
        }
        if (on == true || p.length() > 0) p.put("state", true)
        return p
    }

    /** getPilot-antwoord naar Tuya-functies (voor terugzetten na sync, scène of test). */
    fun dps(r: JSONObject): JSONObject {
        val d = JSONObject().put("20", r.optBoolean("state", true))
        val dim = r.optInt("dimming", 100).coerceIn(10, 100) * 10
        if (r.has("r") && (r.optInt("r") + r.optInt("g") + r.optInt("b")) > 0 && !r.has("temp")) {
            val hsv = FloatArray(3); Color.RGBToHSV(r.optInt("r"), r.optInt("g"), r.optInt("b"), hsv)
            d.put("21", "colour").put("24", TuyaLamp.hsvHex(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), dim))
        } else {
            val t = r.optInt("temp", 3000)
            d.put("21", "white").put("22", dim).put("23", ((t - 2200) * 10 / 43).coerceIn(0, 1000))
        }
        return d
    }

    /**
     * Zoekt WiZ-lampen op het netwerk: nieuwe komen in de lampenlijst, bekende krijgen hun nieuwe adres.
     * Geeft het aantal nieuwe lampen terug.
     */
    fun scan(waitMs: Long = 2500): Int {
        val ip = WebServer.localIp() ?: return 0
        val found = LinkedHashMap<String, Pair<String, String>>()   // mac -> ip, module
        DatagramSocket().use { s ->
            s.broadcast = true; s.soTimeout = 300
            val ask = """{"method":"getSystemConfig","params":{}}""".toByteArray()
            for (target in listOf(ip.substringBeforeLast('.') + ".255", "255.255.255.255")) {
                try { s.send(DatagramPacket(ask, ask.size, InetAddress.getByName(target), PORT)) } catch (_: Exception) { }
            }
            val end = SystemClock.elapsedRealtime() + waitMs
            val buf = ByteArray(2048)
            while (SystemClock.elapsedRealtime() < end) {
                try {
                    val pkt = DatagramPacket(buf, buf.size); s.receive(pkt)
                    val r = JSONObject(String(buf, 0, pkt.length)).optJSONObject("result") ?: continue
                    val mac = r.optString("mac"); if (mac.isBlank()) continue
                    found[mac] = pkt.address.hostAddress.orEmpty() to r.optString("moduleName")
                } catch (_: SocketTimeoutException) { } catch (_: Exception) { }
            }
        }
        var added = 0; var changed = false
        synchronized(Store) {
            for ((mac, v) in found) {
                val id = "wiz-$mac"
                if (id in Store.hidden) continue
                val ex = Store.lamps.firstOrNull { it.id == id }
                if (ex != null) { if (ex.ip != v.first) { ex.ip = v.first; changed = true } }
                else {
                    Store.lamps += Lamp(id, "", "WiZ " + mac.takeLast(4).uppercase(), ip = v.first, version = VERSION).apply { category = "wiz" }
                    added++; changed = true
                }
            }
        }
        if (changed) { Store.save(); Engine.syncWorkers() }
        return added
    }

    /** Op de achtergrond af en toe zoeken (nieuwe lampen, of een ander adres van de router). */
    @Volatile private var started = false
    fun startBackground() {
        if (started) return
        started = true
        Thread({
            var n = 0
            while (true) {
                try { scan() } catch (_: Exception) { }
                n++
                try { Thread.sleep(if (n < 6) 20_000L else 120_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "wiz-scan").apply { isDaemon = true; start() }
    }
}
