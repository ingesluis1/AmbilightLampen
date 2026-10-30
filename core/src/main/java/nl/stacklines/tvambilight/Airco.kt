package nl.stacklines.tvambilight

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Airco's bedienen (alleen op de hub; de tv laat ze met rust). Uitgezocht op de mobiele airco (categorie ydkt) door in
 * Smart Life één ding tegelijk te veranderen:
 * 1 aan/uit, 2 ingestelde temperatuur (graden), 3 temperatuur binnen (meet de airco), 101 stand ("1" koelen,
 * "3" ontvochtigen, "5" ventileren), 104 ventilator ("3" laag, "2" medium, "1" hoog), 103 slaapstand, 106 swing,
 * 105 timer (uren, 0 = uit).
 */
object Airco {
    /** Aanzetten op de hub. Op de tv staat het uit: twee verbindingen tegelijk kan de airco niet altijd aan. */
    @Volatile var enabled = false
    val conns = ConcurrentHashMap<String, TuyaLamp>()
    val MODES = mapOf("cool" to "1", "dry" to "3", "fan" to "5")
    val FANS = mapOf("low" to "3", "mid" to "2", "high" to "1")
    const val MIN_TEMP = 16
    const val MAX_TEMP = 31

    /** Verbindingen bijhouden: één per airco met ip-adres, open zolang de hub draait. */
    fun sync() {
        val airs = if (enabled) Store.lampsSnapshot().filter { it.kind == "airco" && it.ip.isNotBlank() && it.supported }.associateBy { it.id } else emptyMap()
        for ((id, c) in conns) { val l = airs[id]; if (l == null || l.ip != c.lamp.ip || l.key != c.lamp.key) { c.stop(); conns.remove(id) } }
        for (l in airs.values) if (!conns.containsKey(l.id)) conns[l.id] = TuyaLamp(l, keepOpen = true)
    }

    /** Stand voor de pagina; is die ouder dan een halve minuut, dan vragen we hem op de achtergrond opnieuw op. */
    fun state(id: String): JSONObject? {
        val c = conns[id] ?: return null
        if (SystemClock.elapsedRealtime() - c.dpsAt > 30_000) c.refresh()
        val d = c.allDps
        return JSONObject().apply {
            put("online", c.online)
            put("known", d.isNotEmpty())
            d["1"]?.let { put("on", it == true) }
            (d["2"] as? Number)?.let { put("target", it.toInt()) }
            (d["3"] as? Number)?.let { put("inside", it.toInt()) }
            MODES.entries.firstOrNull { it.value == d["101"]?.toString() }?.let { put("mode", it.key) }
            FANS.entries.firstOrNull { it.value == d["104"]?.toString() }?.let { put("fan", it.key) }
            d["103"]?.let { put("sleep", it == true) }
            d["106"]?.let { put("swing", it == true) }
        }
    }

    /** Opdracht van de pagina: on, mode, target, fan, sleep, swing (alleen wat er in staat). Geeft een foutmelding of null. */
    fun set(id: String, j: JSONObject): String? {
        val c = conns[id] ?: return tr("Airco niet verbonden", "Air conditioner not connected")
        val dps = LinkedHashMap<String, Any>()
        if (j.has("on")) dps["1"] = j.optBoolean("on")
        j.optString("mode").takeIf { it.isNotEmpty() }?.let { m -> dps["101"] = MODES[m] ?: return tr("Onbekende stand", "Unknown mode") }
        if (j.has("target")) dps["2"] = j.optInt("target").coerceIn(MIN_TEMP, MAX_TEMP)
        j.optString("fan").takeIf { it.isNotEmpty() }?.let { f -> dps["104"] = FANS[f] ?: return tr("Onbekende ventilatorstand", "Unknown fan speed") }
        if (j.has("sleep")) dps["103"] = j.optBoolean("sleep")
        if (j.has("swing")) dps["106"] = j.optBoolean("swing")
        if (dps.isEmpty()) return tr("Niets te doen", "Nothing to do")
        c.send(dps, raw = true)
        return null
    }
}
