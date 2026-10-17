package nl.stacklines.tvambilight

import android.graphics.Color
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Een scène: vaste of langzaam bewegende kleuren in plaats van de sync.
 * kind: static (elke lamp een kleur uit het palet), drift (palet loopt langzaam door), flicker (haardvuur),
 * white (witlicht met helderheid en kleurtemperatuur), custom (per lamp opgeslagen kleur).
 * Kleuren: h 0..360, s en v 0..1000. Lampen krijgen hun kleur op volgorde van links naar rechts.
 */
class Scene(
    val id: String, val name: String, val kind: String,
    val palette: List<IntArray> = emptyList(),
    val speed: Double = 0.0,                 // paletkleuren per seconde (drift)
    val white: IntArray? = null,             // helderheid 10..1000, kleurtemperatuur 0 (warm) .. 1000 (koel)
    val perLamp: Map<String, IntArray> = emptyMap(),
    val builtin: Boolean = true
) {
    fun preview(): JSONArray = JSONArray().apply {
        val cols = if (kind == "white") listOf(whiteRgb()) else if (kind == "custom") perLamp.values.map { hsv(it) } else palette.map { hsv(it) }
        cols.take(6).forEach { put("#%06x".format(it and 0xffffff)) }
    }
    private fun whiteRgb(): Int {
        val t = (white?.get(1) ?: 500) / 1000f
        return Color.rgb((255 - 40 * t).toInt(), (215 + 25 * t).toInt(), (150 + 105 * t).toInt())
    }
    fun toJson() = JSONObject().put("id", id).put("name", name).put("kind", kind).put("builtin", builtin).put("preview", preview())
    fun toStore() = JSONObject().put("id", id).put("name", name).put("kind", kind)
        .put("perLamp", JSONObject().apply { perLamp.forEach { (k, c) -> put(k, JSONArray(c.toList())) } })

    companion object {
        fun hsv(c: IntArray) = Color.HSVToColor(floatArrayOf(c[0].toFloat(), c[1] / 1000f, max(0.15f, c[2] / 1000f)))
        fun fromStore(o: JSONObject): Scene {
            val m = HashMap<String, IntArray>()
            o.optJSONObject("perLamp")?.let { p -> p.keys().forEach { k -> val a = p.getJSONArray(k); m[k] = IntArray(3) { a.getInt(it) } } }
            return Scene(o.getString("id"), o.optString("name", "Scène"), "custom", perLamp = m, builtin = false)
        }
        private fun c(h: Int, s: Int, v: Int) = intArrayOf(h, s, v)

        /** Ingebouwde scènes: bewegend, vast en wit. */
        val BUILTIN = listOf(
            Scene("sunset", "Zonsondergang", "drift", listOf(c(25, 950, 1000), c(10, 850, 900), c(345, 700, 800), c(5, 1000, 700)), speed = 0.05),
            Scene("pinkblue", "Roze & blauw", "drift", listOf(c(320, 800, 900), c(280, 750, 800), c(225, 850, 900), c(300, 700, 850)), speed = 0.05),
            Scene("aurora", "Noorderlicht", "drift", listOf(c(140, 900, 800), c(175, 850, 850), c(270, 700, 700), c(155, 800, 700)), speed = 0.04),
            Scene("ocean", "Oceaan", "drift", listOf(c(205, 900, 850), c(185, 850, 800), c(230, 950, 700), c(195, 700, 900)), speed = 0.04),
            Scene("fire", "Haardvuur", "flicker", listOf(c(22, 1000, 900), c(12, 1000, 750), c(30, 950, 850))),
            Scene("rainbow", "Regenboog", "drift", (0 until 6).map { c(it * 60, 1000, 900) }, speed = 0.08),
            Scene("warm", "Warme gloed", "static", listOf(c(28, 800, 450), c(22, 850, 400), c(32, 700, 450))),
            Scene("blossom", "Lentebloesem", "static", listOf(c(340, 450, 800), c(15, 400, 850), c(330, 300, 900))),
            Scene("movie", "Filmavond", "static", listOf(c(25, 900, 120), c(20, 950, 90))),
            Scene("night", "Nachtlampje", "static", listOf(c(28, 1000, 25))),
            Scene("relax", "Ontspannen", "white", white = intArrayOf(500, 150)),
            Scene("read", "Lezen", "white", white = intArrayOf(900, 500)),
            Scene("focus", "Concentratie", "white", white = intArrayOf(1000, 900)),
        )
    }
}

/** Speelt een scène af op alle lampen die meedoen. */
object SceneEngine {
    @Volatile var active: Scene? = null
        private set
    private var thread: Thread? = null

    fun all(): List<Scene> = Scene.BUILTIN + Store.customScenes

    @Synchronized
    fun start(scene: Scene) {
        stop()
        Engine.lampsOff = false
        if (Engine.running) { AutoDark.userStopped(); Store.syncWanted = false; Store.save(); Engine.stop() }
        Engine.syncWorkers()
        active = scene
        thread = Thread({ loop(scene) }, "scene").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        active = null
        t.interrupt(); t.join(3000)
        thread = null
    }

    /** Huidige kleuren van alle lampen opslaan als eigen scène. */
    fun saveCurrent(name: String): Scene {
        val m = HashMap<String, IntArray>()
        // Tijdens sync of scène: wat de app nu stuurt; anders de stand die de lamp zelf meldt (uit of onbekend = niet opnemen)
        val live = Engine.running || active != null
        for (l in Store.lampsSnapshot()) {
            val w = Engine.workers[l.id] ?: continue
            val col = (if (live) w.shownColor else w.idleColor()) ?: continue
            val hsv = FloatArray(3); Color.colorToHSV(col, hsv)
            // De maximale helderheid van de lamp zit al in de getoonde kleur; bij afspelen komt hij er weer overheen
            m[l.id] = intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), max(30, (hsv[2] * 1000 / l.maxBright).roundToInt().coerceAtMost(1000)))
        }
        val s = Scene("c" + System.currentTimeMillis().toString(36), name.ifBlank { "Eigen scène" }, "custom", perLamp = m, builtin = false)
        Store.customScenes = Store.customScenes + s; Store.save()
        return s
    }

    private fun loop(scene: Scene) {
        val lamps = Store.lampsSnapshot().filter { it.enabled && Engine.workers.containsKey(it.id) }
            .sortedBy { Mixer.project(Store.layout, it)?.get(0) ?: it.x }
        val saved = try { Engine.queryAll(lamps.map { it.id }) } catch (_: InterruptedException) { return }
        val last = HashMap<String, IntArray>()
        val t0 = SystemClock.elapsedRealtime()
        try {
            // Wit: één keer instellen
            if (scene.kind == "white") {
                val w = scene.white ?: intArrayOf(700, 500)
                for (l in lamps) {
                    Engine.workers[l.id]?.let { it.send(mapOf("20" to true, "21" to "white", "22" to (w[0] * l.maxBright).roundToInt().coerceAtLeast(10), "23" to w[1])); it.shownColor = Color.rgb(255, 225, 180) }
                }
                while (true) Thread.sleep(60_000)
            }
            while (true) {
                val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
                for ((i, l) in lamps.withIndex()) {
                    val w = Engine.workers[l.id] ?: continue
                    val c = colourFor(scene, l, i, lamps.size, t) ?: continue
                    val p = last[l.id]
                    if (p != null && hueDist(p[0], c[0]) < 2 && abs(p[1] - c[1]) < 10 && abs(p[2] - c[2]) < 10) continue
                    val dps = LinkedHashMap<String, Any>()
                    if (p == null) { dps["20"] = true; dps["21"] = "colour" }
                    dps["24"] = TuyaLamp.hsvHex(c[0], c[1].coerceIn(0, 1000), (c[2] * l.maxBright).roundToInt().coerceIn(10, 1000))
                    w.send(dps)
                    w.shownColor = Scene.hsv(c)
                    last[l.id] = c
                }
                // Vaste scènes hoeven niet steeds opnieuw; bewegende ongeveer 4 keer per seconde
                Thread.sleep(if (scene.kind == "static" || scene.kind == "custom") 5_000 else 250)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            AppLog.add("Scène: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            for (l in lamps) Engine.workers[l.id]?.restore(saved[l.id])
        }
    }

    private fun hueDist(a: Int, b: Int) = abs(a - b).let { min(it, 360 - it) }

    /** Kleur voor lamp i (van links naar rechts) op tijdstip t seconden. */
    private fun colourFor(scene: Scene, l: Lamp, i: Int, n: Int, t: Double): IntArray? {
        val pal = scene.palette
        return when (scene.kind) {
            "custom" -> scene.perLamp[l.id]
            "static" -> pal[i % pal.size]
            "drift" -> {
                // Elke lamp een eigen plek in het palet; het geheel schuift langzaam door
                val pos = (i.toDouble() / max(1, n) * pal.size + t * scene.speed * pal.size) % pal.size
                val a = pal[floor(pos).toInt() % pal.size]; val b = pal[(floor(pos).toInt() + 1) % pal.size]
                mixHsv(a, b, pos - floor(pos))
            }
            "flicker" -> {
                // Haardvuur: rustig golvende helderheid met af en toe een opflakkering, per lamp verschoven
                val base = pal[i % pal.size]
                val f = 0.78 + 0.12 * sin(t * 2.1 + i * 1.7) + 0.07 * sin(t * 5.3 + i * 0.9) + 0.03 * sin(t * 11.0 + i)
                intArrayOf(((base[0] + 4 * sin(t * 1.3 + i)).roundToInt() + 360) % 360, base[1], (base[2] * f).roundToInt())
            }
            else -> null
        }
    }

    /** Mengt twee kleuren via RGB, zodat de overgang geen omweg door de kleurencirkel maakt. */
    private fun mixHsv(a: IntArray, b: IntArray, f: Double): IntArray {
        val ca = Color.HSVToColor(floatArrayOf(a[0].toFloat(), a[1] / 1000f, 1f)); val cb = Color.HSVToColor(floatArrayOf(b[0].toFloat(), b[1] / 1000f, 1f))
        val r = (Color.red(ca) * (1 - f) + Color.red(cb) * f).roundToInt(); val g = (Color.green(ca) * (1 - f) + Color.green(cb) * f).roundToInt()
        val bl = (Color.blue(ca) * (1 - f) + Color.blue(cb) * f).roundToInt()
        val hsv = FloatArray(3); Color.RGBToHSV(r, g, bl, hsv)
        return intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), (a[2] * (1 - f) + b[2] * f).roundToInt())
    }
}
