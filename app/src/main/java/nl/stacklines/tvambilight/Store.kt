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
    var satBoost: Double = -1.0,      // kleur per lamp: <0 = standaard (ledstrip 1.4, lamp 1.0)
    var group: String = "",           // id van het groepje; leeg = algemene instellingen
    var heightY: Double? = null,      // gekalibreerde hoogte op het tv-vlak (-1 = onderrand, 1 = bovenrand); null = vaste hoogte
    var lowHue: Double = 190.0,       // kleurcorrectie bij weinig licht: richting (tint 0..360) waarheen bijgestuurd wordt
    var lowStrength: Double = 0.0,    // en hoe sterk (0 = geen correctie, 0.6 = veel)
    var maxBright: Double = 1.0       // maximale helderheid van deze lamp bij sync en scènes (0.1..1), bv. een felle lamp dichtbij
) {
    /** Ledstrips tonen kleuren witter dan lampen; die standaard iets voller. */
    val satFactor get() = if (satBoost > 0) satBoost else if (category == "dd") 1.4 else 1.0
    val placed get() = x >= 0 && y >= 0
    val supported get() = version in setOf("3.1", "3.3", "3.4", "3.5", Wiz.VERSION, Hue.VERSION)

    fun toJson(withKey: Boolean) = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("version", version)
        put("x", x); put("y", y); put("height", height); put("enabled", enabled); put("shape", shape); put("follow", follow); put("layers", layers); put("category", category); put("satBoost", satBoost); put("satFactor", satFactor); put("group", group)
        heightY?.let { put("heightY", it) }
        put("lowHue", lowHue); put("lowStrength", lowStrength); put("maxBright", maxBright)
        if (withKey) put("key", key)
    }

    companion object {
        fun fromJson(o: JSONObject) = Lamp(
            o.getString("id"), o.optString("key"), o.optString("name", o.getString("id")),
            o.optString("ip"), o.optString("version", "3.3"),
            o.optDouble("x", -1.0), o.optDouble("y", -1.0),
            o.optString("height", "ceiling"), o.optBoolean("enabled", true), o.optString("shape", "bulb"),
            o.optString("follow", "auto"), o.optInt("layers", 0), o.optString("category"),
            o.optDouble("satBoost", -1.0), o.optString("group"),
            if (o.has("heightY")) o.optDouble("heightY") else null,
            o.optDouble("lowHue", 190.0), o.optDouble("lowStrength", 0.0),
            o.optDouble("maxBright", 1.0).coerceIn(0.1, 1.0)
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
    var cuts = true          // scènewissels direct doorgeven (springen) in plaats van vloeien
    var source = "screen"    // screen = randen van het beeld | ambilight = wat de Ambilight-leds uitstralen
    var followAmbi = true    // Ambilight van de tv uit (ook standby) = lampen terug en sync pauzeren
    var whiteLevel = 0.5     // witte leds geven meer licht dan de kleurleds: hun helderheid hiermee vermenigvuldigen
    var whiteLeds = false    // witte, gele en donkere tinten met de witte leds van de lamp (witmodus) in plaats van RGB
    var darkColor = "half"   // kleur bij donker beeld: half = gemiddelde van de schermhelft aan de kant van de lamp | lamp = dichtstbijzijnde lamp met kleur

    fun toJson() = JSONObject().apply {
        put("smooth", smooth); put("saturation", saturation); put("brightness", brightness)
        put("minLight", minLight); put("spread", spread); put("hz", hz)
        put("autostart", autostart); put("after", after); put("lang", lang); put("fade", fade); put("cuts", cuts); put("followAmbi", followAmbi); put("source", source); put("darkColor", darkColor); put("whiteLeds", whiteLeds); put("whiteLevel", whiteLevel)
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
        cuts = o.optBoolean("cuts", cuts)
        followAmbi = o.optBoolean("followAmbi", followAmbi)
        source = if (o.optString("source", source) == "ambilight") "ambilight" else "screen"
        darkColor = if (o.optString("darkColor", darkColor) == "lamp") "lamp" else "half"
        whiteLeds = o.optBoolean("whiteLeds", whiteLeds)
        whiteLevel = o.optDouble("whiteLevel", whiteLevel).coerceIn(0.1, 1.0)
    }
}

/** Groepje lampen met eigen instellingen (reactiesnelheid, helderheid enz.). */
class Group(val id: String, var name: String, val settings: Settings = Settings()) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("settings", settings.toJson())
    companion object {
        fun fromJson(o: JSONObject) = Group(o.getString("id"), o.optString("name", "Groepje"), Settings().apply { o.optJSONObject("settings")?.let { apply(it) } })
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
    @Volatile var groups: List<Group> = emptyList()
    @Volatile var customScenes: List<Scene> = emptyList()

    /** Instellingen die voor deze lamp gelden: die van zijn groepje, anders de algemene. */
    fun settingsFor(l: Lamp): Settings = groups.firstOrNull { it.id == l.group }?.settings ?: settings
    @Volatile var tvHost = "127.0.0.1"
    @Volatile var tvUser: String? = null
    @Volatile var tvPass: String? = null
    @Volatile var syncWanted = false
    @Volatile var cloudId: String? = null
    @Volatile var cloudSecret: String? = null
    @Volatile var cloudRegion = "eu"
    /** Hue Bridge: adres, id, sleutel (username) en clientkey voor de Entertainment-stream; gekozen Entertainment-ruimte. */
    @Volatile var hueIp: String? = null
    @Volatile var hueBridgeId: String? = null
    @Volatile var hueKey: String? = null
    @Volatile var hueClientKey: String? = null
    @Volatile var hueArea: String? = null
    /** Vingerafdruk (SHA-256) van het certificaat van de Bridge, vastgelegd bij het koppelen. */
    @Volatile var hueCert: String? = null
    /** Verwijderde lampen (id naar naam): komen bij zoeken in de cloud niet vanzelf terug. */
    val hidden = LinkedHashMap<String, String>()
    /** Definitief verwijderde lampen (id naar naam): niet meer in de lijst en ook niet terug bij zoeken; alleen via devices.json. */
    val forgotten = LinkedHashMap<String, String>()
    /** Wordt opgehoogd bij elke wijziging, zodat de motor weet dat hij opnieuw moet rekenen. */
    @Volatile var version = 0

    val tvPaired get() = tvUser != null && tvPass != null
    val cloudConfigured get() = cloudId != null && cloudSecret != null

    @Synchronized
    fun load(ctx: Context) {
        file = File(ctx.filesDir, "config.json")
        if (!file.exists()) return
        val o = try { JSONObject(file.readText()) } catch (_: Exception) {
            // Onleesbaar: bewaren voordat het eerstvolgende opslaan het overschrijft
            try { file.copyTo(File(file.parentFile, "config.json.bad"), overwrite = true) } catch (_: Exception) { }
            return
        }
        parse(o)
    }

    /** Leest een opgeslagen stand in (config.json of een backup). Per onderdeel: één kapot veld mag de rest niet meenemen. */
    private fun parse(o: JSONObject) {
        fun part(block: () -> Unit) = try { block() } catch (_: Exception) { }
        part { o.optJSONObject("layout")?.let { layout = it } }
        lamps.clear()
        o.optJSONArray("lamps")?.let { a -> for (i in 0 until a.length()) part { lamps += Lamp.fromJson(a.getJSONObject(i)) } }
        part { o.optJSONObject("settings")?.let { settings.apply(it) } }
        part { customScenes = o.optJSONArray("scenes")?.let { a -> (0 until a.length()).mapNotNull { try { Scene.fromStore(a.getJSONObject(it)) } catch (_: Exception) { null } } } ?: emptyList() }
        part { groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).mapNotNull { try { Group.fromJson(a.getJSONObject(it)) } catch (_: Exception) { null } } } ?: emptyList() }
        part {
            tvHost = o.optString("tvHost", tvHost)
            tvUser = o.optString("tvUser").ifBlank { null }
            tvPass = o.optString("tvPass").ifBlank { null }
            syncWanted = o.optBoolean("syncWanted", false)
            cloudId = o.optString("cloudId").ifBlank { null }
            cloudSecret = o.optString("cloudSecret").ifBlank { null }
            cloudRegion = o.optString("cloudRegion", "eu")
            hueIp = o.optString("hueIp").ifBlank { null }
            hueBridgeId = o.optString("hueBridgeId").ifBlank { null }
            hueKey = o.optString("hueKey").ifBlank { null }
            hueClientKey = o.optString("hueClientKey").ifBlank { null }
            hueArea = o.optString("hueArea").ifBlank { null }
            hueCert = o.optString("hueCert").ifBlank { null }
        }
        hidden.clear(); forgotten.clear()
        part { o.optJSONObject("hidden")?.let { h -> h.keys().forEach { k -> hidden[k] = h.optString(k) } } }
        part { o.optJSONObject("forgotten")?.let { h -> h.keys().forEach { k -> forgotten[k] = h.optString(k) } } }
        version++
    }

    /** Backup voor de telefoon: alles, en de sleutels (lampen, cloud, Hue, tv-koppeling) alleen als daarom gevraagd is. */
    @Synchronized
    fun backup(withKeys: Boolean): JSONObject {
        val o = buildJson()
        o.remove("syncWanted")
        if (!withKeys) {
            o.optJSONArray("lamps")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("key") }
            for (k in listOf("tvUser", "tvPass", "cloudId", "cloudSecret", "hueKey", "hueClientKey", "hueCert")) o.remove(k)
        }
        return JSONObject().put("app", "AmbilightLampen").put("created", System.currentTimeMillis()).put("withKeys", withKeys).put("config", o)
    }

    /** Zet een backup terug. Sleutels die niet in de backup staan, blijven zoals ze op de tv waren. */
    fun restore(b: JSONObject): Int {
        val o = b.optJSONObject("config") ?: throw IllegalArgumentException(tr("Dit is geen backup van Ambilight Lampen", "This is not an Ambilight Lampen backup"))
        synchronized(this) {
            val oldKeys = lamps.associate { it.id to it.key }
            val old = listOf(tvUser, tvPass, cloudId, cloudSecret, hueKey, hueClientKey, hueCert)
            val wanted = syncWanted
            parse(o)
            syncWanted = wanted
            lamps.forEach { if (it.key.isBlank()) it.key = oldKeys[it.id].orEmpty() }
            tvUser = tvUser ?: old[0]; tvPass = tvPass ?: old[1]
            cloudId = cloudId ?: old[2]; cloudSecret = cloudSecret ?: old[3]
            // Hue-sleutels horen bij elkaar (en bij het certificaat): alleen samen overnemen
            if (hueKey == null) { hueKey = old[4]; hueClientKey = old[5]; hueCert = old[6] }
        }
        save()
        return lamps.size
    }

    private val fileLock = Object()
    private var saveSeq = 0L
    private var writtenSeq = 0L

    fun save() {
        val (n, text) = synchronized(this) { version++; ++saveSeq to buildJson().toString() }
        // Buiten het Store-slot schrijven: lampthreads en de sync-lus lezen ondertussen gewoon door
        synchronized(fileLock) {
            if (n < writtenSeq) return   // een nieuwere stand is al weggeschreven
            writtenSeq = n
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    private fun buildJson(): JSONObject {
        return JSONObject().apply {
            put("layout", layout)
            put("lamps", JSONArray().apply { lamps.forEach { put(it.toJson(true)) } })
            put("settings", settings.toJson())
            put("groups", JSONArray().apply { groups.forEach { put(it.toJson()) } })
            put("scenes", JSONArray().apply { customScenes.forEach { put(it.toStore()) } })
            put("tvHost", tvHost)
            put("tvUser", tvUser ?: ""); put("tvPass", tvPass ?: "")
            put("syncWanted", syncWanted)
            put("hidden", JSONObject(hidden as Map<*, *>))
            put("forgotten", JSONObject(forgotten as Map<*, *>))
            put("cloudId", cloudId ?: ""); put("cloudSecret", cloudSecret ?: ""); put("cloudRegion", cloudRegion)
            put("hueIp", hueIp ?: ""); put("hueBridgeId", hueBridgeId ?: ""); put("hueKey", hueKey ?: ""); put("hueClientKey", hueClientKey ?: ""); put("hueArea", hueArea ?: ""); put("hueCert", hueCert ?: "")
        }
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
