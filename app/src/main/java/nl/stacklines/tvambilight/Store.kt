package nl.stacklines.tvambilight

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eén lamp. Positie in meters vanaf linksboven van de plattegrond; x < 0 = nog niet geplaatst. */
class Lamp(
    val id: String,
    var key: String,
    var name: String,
    var ip: String = "",
    var version: String = "3.3",
    var x: Double = -1.0,
    var y: Double = -1.0,
    var height: String = "ceiling",   // ceiling | table | floor
    var enabled: Boolean = true,
    var shape: String = "bulb",       // bulb (lamp/spot) | rod (staaflamp)
    var follow: String = "auto",      // auto (op plek) | top (bovenrand, op volgorde) | side (hele zijkant) | all (hele beeld)
    var layers: Int = 0,              // 0 = één kleur; 2..5 = lagen apart (Tuya functie 61, segment 1 = onder)
    var category: String = "",        // Tuya-categorie uit de cloud: dj = lamp, dd = ledstrip
    var satBoost: Double = -1.0       // kleur per lamp: <0 = standaard (ledstrip 1.4, lamp 1.0)
) {
    /** Ledstrips tonen kleuren witter dan lampen; die standaard iets voller. */
    val satFactor get() = if (satBoost > 0) satBoost else if (category == "dd") 1.4 else 1.0
    val placed get() = x >= 0 && y >= 0
    val supported get() = version in setOf("3.1", "3.3", "3.4", "3.5")

    fun toJson(withKey: Boolean) = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("version", version)
        put("x", x); put("y", y); put("height", height); put("enabled", enabled); put("shape", shape); put("follow", follow); put("layers", layers); put("category", category); put("satBoost", satBoost); put("satFactor", satFactor)
        if (withKey) put("key", key)
    }

    companion object {
        fun fromJson(o: JSONObject) = Lamp(
            o.getString("id"), o.optString("key"), o.optString("name", o.getString("id")),
            o.optString("ip"), o.optString("version", "3.3"),
            o.optDouble("x", -1.0), o.optDouble("y", -1.0),
            o.optString("height", "ceiling"), o.optBoolean("enabled", true), o.optString("shape", "bulb"),
            o.optString("follow", "auto"), o.optInt("layers", 0), o.optString("category"),
            o.optDouble("satBoost", -1.0)
        )
    }
}

class Settings {
    var smooth = 0.35        // 0..1, hoger = sneller
    var saturation = 1.6     // kleuren opvoeren
    var brightness = 1.4     // helderheid opvoeren
    var minLight = 0.03      // licht bij zwart beeld (0 = uit)
    var spread = 1.0         // hoe breed een lamp om zich heen kijkt
    var hz = 15
    var autostart = true
    var after = "restore"    // restore | white | off
    var lang = "nl"          // nl | en
    var fade = true          // lampen laten overvloeien (Tuya functie 28) in plaats van springen
    var source = "screen"    // screen = randen van het beeld | ambilight = wat de Ambilight-leds uitstralen
    var followAmbi = true    // Ambilight van de tv uit (ook standby) = lampen terug en sync pauzeren

    fun toJson() = JSONObject().apply {
        put("smooth", smooth); put("saturation", saturation); put("brightness", brightness)
        put("minLight", minLight); put("spread", spread); put("hz", hz)
        put("autostart", autostart); put("after", after); put("lang", lang); put("fade", fade); put("followAmbi", followAmbi); put("source", source)
    }

    fun apply(o: JSONObject) {
        smooth = o.optDouble("smooth", smooth).coerceIn(0.05, 1.0)
        saturation = o.optDouble("saturation", saturation).coerceIn(0.5, 3.0)
        brightness = o.optDouble("brightness", brightness).coerceIn(0.3, 3.0)
        minLight = o.optDouble("minLight", minLight).coerceIn(0.0, 0.5)
        spread = o.optDouble("spread", spread).coerceIn(0.3, 3.0)
        hz = o.optInt("hz", hz).coerceIn(5, 25)
        autostart = o.optBoolean("autostart", autostart)
        after = o.optString("after", after)
        lang = if (o.optString("lang", lang) == "en") "en" else "nl"
        fade = o.optBoolean("fade", fade)
        followAmbi = o.optBoolean("followAmbi", followAmbi)
        source = if (o.optString("source", source) == "ambilight") "ambilight" else "screen"
    }
}

/** Tekst in de gekozen taal (Nederlands of Engels). */
fun tr(nl: String, en: String) = if (Store.settings.lang == "en") en else nl

/** Alles wat bewaard wordt, in één JSON-bestand in de app-opslag. */
object Store {
    private lateinit var file: File

    /** Plattegrond zoals de telefoon-editor hem maakt: w, h, floor (rijen van '0'/'1'), tv, couch. */
    @Volatile var layout: JSONObject = defaultLayout()
    val lamps = mutableListOf<Lamp>()
    val settings = Settings()
    @Volatile var tvHost = "127.0.0.1"
    @Volatile var tvUser: String? = null
    @Volatile var tvPass: String? = null
    @Volatile var syncWanted = false
    @Volatile var cloudId: String? = null
    @Volatile var cloudSecret: String? = null
    @Volatile var cloudRegion = "eu"
    /** Verwijderde lampen (id naar naam): komen bij zoeken in de cloud niet vanzelf terug. */
    val hidden = LinkedHashMap<String, String>()
    /** Wordt opgehoogd bij elke wijziging, zodat de motor weet dat hij opnieuw moet rekenen. */
    @Volatile var version = 0

    val tvPaired get() = tvUser != null && tvPass != null

    @Synchronized
    fun load(ctx: Context) {
        file = File(ctx.filesDir, "config.json")
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            o.optJSONObject("layout")?.let { layout = it }
            lamps.clear()
            o.optJSONArray("lamps")?.let { a -> for (i in 0 until a.length()) lamps += Lamp.fromJson(a.getJSONObject(i)) }
            o.optJSONObject("settings")?.let { settings.apply(it) }
            tvHost = o.optString("tvHost", tvHost)
            tvUser = o.optString("tvUser").ifBlank { null }
            tvPass = o.optString("tvPass").ifBlank { null }
            syncWanted = o.optBoolean("syncWanted", false)
            cloudId = o.optString("cloudId").ifBlank { null }
            cloudSecret = o.optString("cloudSecret").ifBlank { null }
            cloudRegion = o.optString("cloudRegion", "eu")
            hidden.clear()
            o.optJSONObject("hidden")?.let { h -> h.keys().forEach { k -> hidden[k] = h.getString(k) } }
        } catch (_: Exception) { }
        version++
    }

    @Synchronized
    fun save() {
        version++
        val o = JSONObject().apply {
            put("layout", layout)
            put("lamps", JSONArray().apply { lamps.forEach { put(it.toJson(true)) } })
            put("settings", settings.toJson())
            put("tvHost", tvHost)
            put("tvUser", tvUser ?: ""); put("tvPass", tvPass ?: "")
            put("syncWanted", syncWanted)
            put("hidden", JSONObject(hidden as Map<*, *>))
            put("cloudId", cloudId ?: ""); put("cloudSecret", cloudSecret ?: ""); put("cloudRegion", cloudRegion)
        }
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun lampsSnapshot(): List<Lamp> = lamps.toList()

    fun defaultLayout(): JSONObject {
        val w = 7; val h = 5
        val rows = JSONArray().apply { repeat(h) { put("1".repeat(w)) } }
        return JSONObject().apply {
            put("w", w); put("h", h); put("floor", rows)
            put("tv", JSONObject().apply { put("x", 3.5); put("y", 0.25); put("w", 1.44); put("dir", 90) })
            put("items", JSONArray().apply {
                put(JSONObject().put("type", "rug").put("x", 3.5).put("y", 2.9).put("dir", 90))
                put(JSONObject().put("type", "sofa").put("x", 3.5).put("y", 4.0).put("dir", 270))
                put(JSONObject().put("type", "coffee").put("x", 3.5).put("y", 2.75).put("dir", 90))
                put(JSONObject().put("type", "plant").put("x", 0.5).put("y", 0.6).put("dir", 90))
            })
        }
    }
}
