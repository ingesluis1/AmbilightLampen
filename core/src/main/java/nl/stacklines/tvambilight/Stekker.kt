package nl.stacklines.tvambilight

import org.json.JSONArray
import org.json.JSONObject

/**
 * Slimme stekker met verbruiksmeter (Tuya-categorie cz, uitgezocht op de LSC Power Plug w/ Power Meter EU).
 * Zelfde verbinding als de airco (Airco.conns, alleen op de hub). Functies: 1 aan/uit, 9 aftellen (s),
 * 17 kWh erbij sinds de vorige melding (x0,001), 18 stroom (mA), 19 vermogen (W x0,1), 20 spanning (V x0,1),
 * 26 storing (bits: te veel stroom, te hoge spanning, te veel vermogen, te weinig stroom/spanning/vermogen),
 * 38 na een stroomstoring (off, on, memory), 40 lampje (relay, pos, none, on).
 */
object Stekker {
    private val FAULTS = listOf("stroom", "spanning", "vermogen", "weinig-stroom", "weinig-spanning", "weinig-vermogen")

    fun state(id: String): JSONObject? {
        val c = Airco.conns[id] ?: return null
        if (SystemClock.elapsedRealtime() - c.dpsAt > 30_000) c.refresh()
        val d = c.allDps
        return JSONObject().apply {
            put("online", c.online); put("known", d.isNotEmpty())
            d["1"]?.let { put("on", it == true) }
            (d["19"] as? Number)?.let { put("watt", it.toDouble() / 10) }
            (d["20"] as? Number)?.let { put("volt", it.toDouble() / 10) }
            (d["18"] as? Number)?.let { put("mA", it.toInt()) }
            (d["9"] as? Number)?.let { put("countdown", it.toInt()) }
            d["38"]?.let { put("afterOutage", it.toString()) }
            d["40"]?.let { put("led", it.toString()) }
            (d["26"] as? Number)?.toInt()?.let { f -> put("faults", JSONArray().apply { FAULTS.forEachIndexed { i, n -> if (f and (1 shl i) != 0) put(n) } }) }
        }
    }

    /** Vermogen nu in watt, of null als onbekend. */
    fun watt(id: String): Double? = (Airco.conns[id]?.allDps?.get("19") as? Number)?.toDouble()?.div(10)
    fun isOn(id: String): Boolean? = Airco.conns[id]?.allDps?.get("1")?.let { it == true }

    /** Opdracht: on (aan/uit), afterOutage (off/on/memory), led (relay/pos/none/on). Geeft een foutmelding of null. */
    fun set(id: String, j: JSONObject): String? {
        val c = Airco.conns[id] ?: return tr("Stekker niet verbonden", "Plug not connected")
        val dps = LinkedHashMap<String, Any>()
        if (j.has("on")) dps["1"] = j.optBoolean("on")
        j.optString("afterOutage").takeIf { it in setOf("off", "on", "memory") }?.let { dps["38"] = it }
        j.optString("led").takeIf { it in setOf("relay", "pos", "none", "on") }?.let { dps["40"] = it }
        if (dps.isEmpty()) return tr("Niets te doen", "Nothing to do")
        c.send(dps, raw = true)
        return null
    }
}
