package nl.stacklines.tvambilight

import org.json.JSONArray
import org.json.JSONObject

/**
 * Elektrische kattenbak (Tuya-categorie msp, uitgezocht op de ZEDAR K1200). Zelfde verbinding als de airco
 * (Airco.conns, alleen op de hub). Functies: 1 aan/uit, 4 automatisch schoonmaken, 5 wachttijd na een bezoek (s),
 * 6 gewicht van de kat (g), 7 bezoeken vandaag, 8 tijd erin vandaag (s), 9 nu schoonmaken, 16 lampje,
 * 22 storingen (bits: motor, gewichtsensor, afvalbak vol, bak eruit, filter), 24 status (bv. standby, sleep, uv, cat_in).
 */
object Kattenbak {
    private val FAULTS = listOf("motor", "sensor", "vol", "bak", "filter")

    fun state(id: String): JSONObject? {
        val c = Airco.conns[id] ?: return null
        if (SystemClock.elapsedRealtime() - c.dpsAt > 30_000) c.refresh()
        val d = c.allDps
        return JSONObject().apply {
            put("online", c.online); put("known", d.isNotEmpty())
            d["1"]?.let { put("on", it == true) }
            d["4"]?.let { put("autoClean", it == true) }
            (d["5"] as? Number)?.let { put("delay", it.toInt()) }
            (d["6"] as? Number)?.let { put("weight", it.toInt()) }
            (d["7"] as? Number)?.let { put("visits", it.toInt()) }
            (d["8"] as? Number)?.let { put("seconds", it.toInt()) }
            d["9"]?.let { put("cleaning", it == true) }
            d["16"]?.let { put("light", it == true) }
            d["24"]?.let { put("status", it.toString()) }
            (d["22"] as? Number)?.toInt()?.let { f -> put("faults", JSONArray().apply { FAULTS.forEachIndexed { i, n -> if (f and (1 shl i) != 0) put(n) } }) }
            // Alle nummers zoals de bak ze meldt (om onbekende functies, zoals het gewicht, op te sporen)
            put("raw", JSONObject(d.toMap()))
        }
    }

    /** Opdracht van de pagina: on, autoClean, delay (s), clean (nu schoonmaken), light. Geeft een foutmelding of null. */
    fun set(id: String, j: JSONObject): String? {
        val c = Airco.conns[id] ?: return tr("Kattenbak niet verbonden", "Litter box not connected")
        val dps = LinkedHashMap<String, Any>()
        if (j.has("on")) dps["1"] = j.optBoolean("on")
        if (j.has("autoClean")) dps["4"] = j.optBoolean("autoClean")
        if (j.has("delay")) dps["5"] = j.optInt("delay").coerceIn(0, 600)
        if (j.optBoolean("clean")) dps["9"] = true
        if (j.has("light")) dps["16"] = j.optBoolean("light")
        if (dps.isEmpty()) return tr("Niets te doen", "Nothing to do")
        c.send(dps, raw = true)
        return null
    }
}
