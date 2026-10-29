package nl.stacklines.tvambilight

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.concurrent.ConcurrentHashMap

/**
 * Een scène: vaste of langzaam bewegende kleuren in plaats van de sync.
 * kind: static (elke lamp een kleur uit het palet), drift (palet loopt langzaam door), flicker (haardvuur),
 * white (witlicht met helderheid en kleurtemperatuur), custom (per lamp opgeslagen kleur; lampen zonder eigen kleur
 * krijgen een kleur uit het palet, zoals bij scènes uit de Hue-app; per lamp kan er een effect op, zie EFFECTS).
 * Kleuren: h 0..360, s en v 0..1000. Lampen krijgen hun kleur op volgorde van links naar rechts.
 */
class Scene(
    val id: String, val name: String, val kind: String,
    val palette: List<IntArray> = emptyList(),
    val speed: Double = 0.0,                 // paletkleuren per seconde (drift)
    val white: IntArray? = null,             // helderheid 10..1000, kleurtemperatuur 0 (warm) .. 1000 (koel)
    val perLamp: Map<String, IntArray> = emptyMap(),
    val effects: Map<String, String> = emptyMap(),   // per lamp een van EFFECTS
    val builtin: Boolean = true
) {
    /** Uit de Hue-app overgenomen: Hue-lampen die niet in de scène zitten, blijven zoals ze zijn. */
    val fromHue get() = id.startsWith("h-")

    fun preview(): JSONArray = JSONArray().apply {
        val cols = if (kind == "white") listOf(whiteRgb()) else if (kind == "custom") swatches(perLamp.values + palette) else palette.map { hsv(it) }
        cols.take(6).forEach { put("#%06x".format(it and 0xffffff)) }
    }
    private fun whiteRgb(): Int {
        val t = (white?.get(1) ?: 500) / 1000f
        return Color.rgb((255 - 40 * t).toInt(), (215 + 25 * t).toInt(), (150 + 105 * t).toInt())
    }
    fun toJson() = JSONObject().put("id", id).put("name", name).put("kind", kind).put("builtin", builtin).put("preview", preview())
    fun toStore() = JSONObject().put("id", id).put("name", name).put("kind", kind)
        .put("perLamp", JSONObject().apply { perLamp.forEach { (k, c) -> put(k, JSONArray(c.toList())) } })
        .apply { if (palette.isNotEmpty()) put("palette", JSONArray().apply { palette.forEach { put(JSONArray(it.toList())) } }) }
        .apply { if (effects.isNotEmpty()) put("effects", JSONObject(effects)) }

    companion object {
        fun hsv(c: IntArray) = Color.HSVToColor(floatArrayOf(c[0].toFloat(), c[1] / 1000f, max(0.15f, c[2] / 1000f)))
        /** Kleurvakjes voor het voorbeeld: zonder lampen die uit staan en zonder dubbele. */
        fun swatches(cols: Collection<IntArray>) = cols.filter { it[2] > 0 }.map { hsv(it) }.distinct()
        fun fromStore(o: JSONObject): Scene {
            val m = HashMap<String, IntArray>()
            o.optJSONObject("perLamp")?.let { p -> p.keys().forEach { k -> val a = p.getJSONArray(k); m[k] = IntArray(3) { a.getInt(it) } } }
            val pal = o.optJSONArray("palette")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { c -> IntArray(3) { c.getInt(it) } } } } ?: emptyList()
            val fx = HashMap<String, String>()
            o.optJSONObject("effects")?.let { e -> e.keys().forEach { k -> e.optString(k).takeIf { it in EFFECTS }?.let { fx[k] = it } } }
            return Scene(o.getString("id"), o.optString("name", "Scène"), "custom", palette = pal, perLamp = m, effects = fx, builtin = false)
        }
        private fun c(h: Int, s: Int, v: Int) = intArrayOf(h, s, v)
        /** Effecten per lamp (eigen indeling op de kaart van de telefoon); zie SceneEngine.withEffect. */
        val EFFECTS = setOf("breathe", "pulse", "flicker", "fire", "cycle", "wave", "twinkle", "storm", "party")

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

/** Speelt scènes af, per kamer: elke kamer kan tegelijk een eigen scène hebben. */
object SceneEngine {
    /** Een lopende scène in één kamer. */
    private class Run(val scene: Scene, val room: String) {
        /** Lampen na afloop niet terugzetten: er neemt meteen iets anders over (de sync of een andere scène). */
        @Volatile var skipRestore = false
        var thread: Thread? = null
    }
    private val runs = ConcurrentHashMap<String, Run>()

    /** Scène in de tv-kamer (daar wisselt hij af met de sync). */
    val active: Scene? get() = runs[Store.tvRoom.id]?.scene
    /** Scène die in deze kamer loopt, of null. */
    fun activeIn(room: String): Scene? = runs[room]?.scene

    fun all(): List<Scene> = Scene.BUILTIN + Store.customScenes

    /** Scène met dit id, of null (ook bij "" of een verwijderde scène). */
    fun find(id: String): Scene? = if (id.isEmpty()) null else all().firstOrNull { it.id == id }

    /**
     * Scène starten. restoreTo: stand van de lampen van vóór de sync, als de scène direct na de sync begint
     * ("Na stoppen"); bij het stoppen van de scène gaan die lampen daarheen terug in plaats van naar de sync-kleur.
     */
    @Synchronized
    fun start(scene: Scene, restoreTo: Map<String, JSONObject?>? = null, room: String = Store.tvRoom.id) {
        if (Store.room(room) == null) return   // lampen zonder kamer krijgen geen scène (die zou je niet meer kunnen stoppen)
        // Van scène naar scène: niet eerst terug naar de oude stand, de nieuwe scène vloeit vanaf de huidige kleuren
        stop(handOver = true, room = room)
        val tv = room == Store.tvRoom.id
        if (tv) Engine.lampsOff = false
        // Sync loopt (alleen in de tv-kamer): niet eerst terug naar de oude stand; de scène zet de lampen bij stoppen terug naar hoe ze vóór de sync stonden
        var rt = restoreTo
        if (tv && Engine.running) { Platform.userStopped(); Store.syncWanted = false; Store.save(); Engine.stop(handOver = true); rt = Engine.handedOver }
        Engine.syncWorkers()
        val ids = Store.lampsSnapshot().filter { it.room == room }.map { it.id }.toSet()
        val run = Run(scene, room)
        runs[room] = run
        run.thread = Thread({ loop(scene, only = ids, restoreTo = rt, run = run) }, "scene-$room").apply { isDaemon = true; start() }
    }

    /** Scène in alle kamers tegelijk (Huis gekozen op de telefoon); null stopt overal. */
    fun startEverywhere(scene: Scene?) {
        for (r in Store.rooms) if (scene == null) stop(room = r.id) else start(scene, room = r.id)
    }

    /**
     * Scène terwijl de sync pauzeert (startscherm, of zwart beeld omdat de tv uit staat): de sync blijft aan
     * en neemt het daarna weer over.
     */
    private val pauseLock = Any()
    private var pauseThread: Thread? = null

    fun startPause(scene: Scene, lampIds: Set<String>) = synchronized(pauseLock) {
        stopPause()
        pauseThread = Thread({ loop(scene, only = lampIds, restore = false, run = Run(scene, Store.tvRoom.id)) }, "pause-scene").apply { isDaemon = true; start() }
    }

    fun stopPause() = synchronized(pauseLock) {
        val t = pauseThread ?: return
        pauseThread = null
        t.interrupt()
        // Ook vanuit de sync-lus die zelf net onderbroken is: dan niet blijven hangen, wel de vlag laten staan
        try { t.join(3000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    @Synchronized
    fun stop(handOver: Boolean = false, room: String = Store.tvRoom.id) {
        val r = runs.remove(room) ?: return
        r.skipRestore = handOver
        r.thread?.let { it.interrupt(); it.join(3000) }
    }

    /** Loopt er ergens een scène? */
    fun anyActive() = runs.isNotEmpty()

    /**
     * Lampen zijn verhuisd: scènes in deze kamers opnieuw starten, zodat ze de nieuwe lampenset gebruiken
     * (anders blijft de scène van de oude kamer een verhuisde lamp aansturen en vechten twee scènes).
     */
    fun lampsMoved(rooms: Collection<String>) {
        for (id in rooms.toSet()) { val r = runs[id] ?: continue; start(r.scene, room = id) }
    }

    /** Alle scènes stoppen (alle lampen uit, afsluiten van de hub). */
    fun stopAll(handOver: Boolean = false) { for (id in runs.keys.toList()) stop(handOver, id) }

    /** Id van de indeling die je op de kaart van de telefoon maakt (tikken: aan/uit, vasthouden: kleur en effect). */
    const val LIVE = "live"
    private val livePer = ConcurrentHashMap<String, IntArray>()
    private val liveFx = ConcurrentHashMap<String, String>()
    private val liveOnV = ConcurrentHashMap<String, Int>()   // helderheid van voor het uitzetten, voor weer aanzetten

    /** Kleur (h, s, v) en effect per lamp van de lopende indeling, of null als die niet loopt. */
    fun liveLamps(): Map<String, Pair<IntArray, String?>>? {
        val rooms = runs.filterValues { it.scene.id == LIVE }.keys
        if (rooms.isEmpty()) return null
        val ids = Store.lampsSnapshot().filter { it.room in rooms }.map { it.id }.toSet()
        return livePer.filterKeys { it in ids }.mapValues { (k, c) -> c to liveFx[k] }
    }

    /**
     * Eén lamp in de indeling aanpassen (aan/uit, kleur, helderheid, effect). De eerste keer begint de indeling bij wat
     * de lampen nu doen (sync, scène of hun eigen stand); een lopende sync of scène stopt dan, net als bij een scène.
     */
    @Synchronized
    fun liveSet(id: String?, on: Boolean?, colour: IntArray?, bright: Double?, effect: String?) {
        // De indeling loopt in de kamer van de lamp (zonder lamp: de tv-kamer)
        val room = id?.let { i -> Store.lampsSnapshot().firstOrNull { it.id == i }?.room } ?: Store.tvRoom.id
        if (Store.room(room) == null) return   // lamp zonder kamer: eerst in een kamer zetten
        if (activeIn(room)?.id != LIVE) {
            val cur = activeIn(room)
            val running = (room == Store.tvRoom.id && Engine.running) || cur != null
            val inRoom = Store.lampsSnapshot().filter { it.room == room }
            inRoom.forEach { livePer.remove(it.id); liveFx.remove(it.id); liveOnV.remove(it.id) }
            for (l in inRoom) {
                val w = Engine.workers[l.id] ?: continue
                if (!running && w.powered == false) { livePer[l.id] = intArrayOf(30, 600, 0); continue }
                val col = (if (running) w.shownColor else w.idleColor()) ?: continue
                val hsv = FloatArray(3); Color.colorToHSV(col, hsv)
                val v = if (hsv[2] < 0.02f) 0 else (hsv[2] * 1000 / l.maxBright).roundToInt().coerceIn(30, 1000)
                livePer[l.id] = intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), v)
            }
            if (cur?.kind == "custom") cur.effects.forEach { (k, v) -> if (inRoom.any { it.id == k }) liveFx[k] = v }
            start(Scene(LIVE, tr("Eigen indeling", "Own setup"), "custom", perLamp = livePer, effects = liveFx, builtin = false), room = room)
        }
        if (id == null) return
        val old = livePer[id] ?: intArrayOf(30, 600, 0)
        var c = colour?.copyOf() ?: old.copyOf()
        if (colour != null) c[2] = if (old[2] > 0) old[2] else liveOnV[id] ?: 1000
        if (bright != null) c[2] = (bright * 1000).roundToInt().coerceIn(10, 1000)
        when {
            on == false -> { if (old[2] > 0) liveOnV[id] = old[2]; c = intArrayOf(old[0], old[1], 0) }
            on == true && c[2] <= 0 -> c[2] = liveOnV[id] ?: 1000
        }
        livePer[id] = c
        effect?.let { if (it in Scene.EFFECTS) liveFx[id] = it else liveFx.remove(id) }
    }

    /** Huidige kleuren van alle lampen opslaan als eigen scène (bij de eigen indeling: met de effecten). */
    fun saveCurrent(name: String, room: String = Store.tvRoom.id): Scene {
        val inRoom = Store.lampsSnapshot().filter { it.room == room }
        val ids = inRoom.map { it.id }.toSet()
        if (activeIn(room)?.id == LIVE) {
            val s = Scene("c" + System.currentTimeMillis().toString(36), name.ifBlank { tr("Eigen scène", "Own scene") }, "custom",
                perLamp = livePer.filterKeys { it in ids }.mapValues { it.value.copyOf() }, effects = HashMap(liveFx.filterKeys { it in ids }), builtin = false)
            Store.customScenes = Store.customScenes + s; Store.save()
            return s
        }
        val m = HashMap<String, IntArray>()
        // Tijdens sync of scène: wat de app nu stuurt; anders de stand die de lamp zelf meldt (uit of onbekend = niet opnemen)
        val live = (room == Store.tvRoom.id && Engine.running) || activeIn(room) != null
        for (l in inRoom) {
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

    /**
     * Scènes uit de Hue-app overnemen als eigen scène. Hue-lampen krijgen hun kleur uit de scène, de andere lampen
     * kleuren uit het palet. Opnieuw importeren werkt de scène bij (zelfde id). Geeft het aantal terug.
     */
    fun importHue(ids: Set<String>): Int {
        val found = Hue.scenes().filter { it.id in ids && (it.perLamp.isNotEmpty() || it.palette.isNotEmpty()) }
        val add = found.map { h ->
            Scene(hueId(h.id), if (h.room.isBlank()) h.name else "${h.name} · ${h.room}", "custom", palette = h.palette, perLamp = h.perLamp, builtin = false)
        }
        val ids2 = add.map { it.id }.toSet()
        Store.customScenes = Store.customScenes.filter { it.id !in ids2 } + add; Store.save()
        return add.size
    }

    /** Id van een geïmporteerde Hue-scène in de app (past in de veilige ids: letters, cijfers, streepjes). */
    fun hueId(hueSceneId: String) = "h-" + hueSceneId.filter { it.isLetterOrDigit() || it == '-' }.take(38)

    /** only: alleen deze lampen; restore = false: lampen na afloop niet terugzetten (de sync neemt het over). */
    private fun loop(scene: Scene, only: Set<String>? = null, restore: Boolean = true, restoreTo: Map<String, JSONObject?>? = null, run: Run) {
        val lamps = Store.lampsSnapshot().filter { it.enabled && Engine.workers.containsKey(it.id) && (only == null || it.id in only) }
            .sortedBy { Mixer.project(Store.layout, it)?.get(0) ?: it.x }
        // Lampen die de sync niet aanraakte (niet geplaatst) zelf opvragen
        val saved: Map<String, JSONObject?> = if (!restore) emptyMap() else try {
            Engine.queryAll(lamps.map { it.id }.filter { restoreTo?.containsKey(it) != true }) + (restoreTo ?: emptyMap())
        } catch (_: InterruptedException) { return }
        val last = HashMap<String, IntArray>()
        // Zacht naar de eerste kleuren van de scène
        val first = if (scene.kind == "white") (scene.white ?: intArrayOf(700, 500)).let { w -> lamps.associate { it.id to Fade.white(w[1], (w[0] * it.maxBright).roundToInt()) } }
            else lamps.withIndex().mapNotNull { (i, l) -> colourFor(scene, l, i, lamps.size, 0.0, emptyList())?.let { c ->
                l.id to intArrayOf(c[0], c[1].coerceIn(0, 1000), if (c[2] <= 0) 0 else (c[2] * l.maxBright).roundToInt().coerceIn(10, 1000)) } }.toMap()
        Fade.run(lamps.mapNotNull { l -> Engine.workers[l.id]?.let { w -> first[l.id]?.let { Fade.Target(l, w, it) } } })
        if (Thread.currentThread().isInterrupted) { if (restore && !run.skipRestore) restoreScene(lamps, saved); return }
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
                // Lampen met onweer die aan staan: daaruit komt per inslag de bron
                val storm = lamps.filter { scene.effects[it.id] == "storm" && (scene.perLamp[it.id]?.get(2) ?: 1) > 0 }
                for ((i, l) in lamps.withIndex()) {
                    val w = Engine.workers[l.id] ?: continue
                    val c = colourFor(scene, l, i, lamps.size, t, storm) ?: continue
                    val p = last[l.id]
                    // Helderheid 0: lamp uit (Hue-scène waarin deze lamp uit staat)
                    if (c[2] <= 0) {
                        if (p == null || p[2] > 0) { w.send(mapOf("20" to false)); w.shownColor = Color.BLACK; last[l.id] = c }
                        continue
                    }
                    if (p != null && p[2] > 0 && hueDist(p[0], c[0]) < 2 && abs(p[1] - c[1]) < 10 && abs(p[2] - c[2]) < 10) continue
                    val dps = LinkedHashMap<String, Any>()
                    if (p == null || p[2] <= 0) { dps["20"] = true; dps["21"] = "colour" }
                    dps["24"] = TuyaLamp.hsvHex(c[0], c[1].coerceIn(0, 1000), (c[2] * l.maxBright).roundToInt().coerceIn(10, 1000))
                    w.send(dps)
                    w.shownColor = Scene.hsv(c)
                    last[l.id] = c
                }
                // Vaste scènes hoeven niet steeds opnieuw; bewegende ongeveer 4 keer per seconde
                // (de eigen indeling ook vaak: wijzigingen vanaf de telefoon moeten meteen zichtbaar zijn)
                val still = (scene.kind == "static" || scene.kind == "custom") && scene.effects.isEmpty() && scene.id != LIVE
                // Onweer: flitsen duren maar een tiende seconde, dan vaker kijken
                Thread.sleep(if (storm.isNotEmpty()) 70 else if (still) 5_000 else 250)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            AppLog.add("Scène: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            Thread.interrupted()
            if (restore && !run.skipRestore) restoreScene(lamps, saved)
        }
    }

    /** Na de scène: zacht terug naar de bewaarde stand, dan precies die stand. */
    private fun restoreScene(lamps: List<Lamp>, saved: Map<String, JSONObject?>) {
        Fade.run(lamps.mapNotNull { l -> Engine.workers[l.id]?.let { Fade.Target(l, it, Fade.target(saved[l.id])) } })
        Thread.interrupted()
        for (l in lamps) Engine.workers[l.id]?.restore(saved[l.id])
    }

    private fun hueDist(a: Int, b: Int) = abs(a - b).let { min(it, 360 - it) }

    /** Kleur voor lamp i (van links naar rechts) op tijdstip t seconden. */
    private fun colourFor(scene: Scene, l: Lamp, i: Int, n: Int, t: Double, storm: List<Lamp>): IntArray? {
        val pal = scene.palette
        return when (scene.kind) {
            "custom" -> (scene.perLamp[l.id] ?: if (scene.fromHue && l.version == Hue.VERSION) null else pal.takeIf { it.isNotEmpty() }?.let { it[i % it.size] })
                ?.let { if (scene.effects[l.id] == "storm" && it[2] > 0) storm(it, l, t, storm) else withEffect(it, scene.effects[l.id], i, t) }
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

    /** Effect over een vaste kleur, per lamp iets verschoven zodat ze niet allemaal tegelijk gaan. */
    private fun withEffect(c: IntArray, effect: String?, i: Int, t: Double): IntArray {
        if (effect == null || c[2] <= 0) return c
        fun hue(h: Double) = (h.roundToInt() % 360 + 360) % 360
        fun v(f: Double) = (c[2] * f).roundToInt().coerceIn(10, 1000)
        return when (effect) {
            // Ademen: de tint golft zacht heen en weer (ongeveer elke 6 seconden), de helderheid bijna niet.
            // Wit heeft geen tint om te verschuiven; dat krijgt er een vleugje kleur bij.
            "breathe" -> { val w = sin(t * 2 * Math.PI / 6 + i * 0.9)
                intArrayOf(hue(c[0] + 18 * w), if (c[1] < 150) (150 * (0.5 + 0.5 * w)).roundToInt() else c[1], v(0.92 + 0.08 * w)) }
            // Pulseren: rustig zachter en weer feller, ongeveer elke 5 seconden
            "pulse" -> intArrayOf(c[0], c[1], v(0.6 + 0.4 * sin(t * 2 * Math.PI / 5 + i * 0.9)))
            // Flikkeren: zoals een kaars
            "flicker" -> intArrayOf(c[0], c[1], v(0.78 + 0.12 * sin(t * 2.1 + i * 1.7) + 0.07 * sin(t * 5.3 + i * 0.9) + 0.03 * sin(t * 11.0 + i)))
            // Haardvuur: dieper flakkeren, de tint beweegt mee (rood en oranje)
            "fire" -> intArrayOf(hue(c[0] + 8 * sin(t * 1.7 + i) + 6 * sin(t * 4.3 + i * 2)), c[1],
                v(0.65 + 0.2 * sin(t * 2.3 + i * 1.3) + 0.1 * sin(t * 7.1 + i) + 0.05 * sin(t * 13.0 + i)))
            // Kleurenloop: langzaam de kleurencirkel rond (ongeveer 24 seconden)
            "cycle" -> intArrayOf(hue(c[0] + t * 15 + i * 40), max(c[1], 700), c[2])
            // Golf: een golf van licht die van links naar rechts door de kamer gaat
            "wave" -> intArrayOf(c[0], c[1], v(0.35 + 0.65 * (0.5 + 0.5 * sin(t * 2 * Math.PI / 4 - i * 1.2))))
            // Twinkelen: gedimd, af en toe een korte fonkeling
            "twinkle" -> if (rnd(floor(t * 3), i) > 0.85) intArrayOf(c[0], (c[1] * 0.6).roundToInt(), v(1.0)) else intArrayOf(c[0], c[1], v(0.45))
            // Feest: ongeveer elke seconde een andere kleur, elke lamp anders
            "party" -> intArrayOf(hue(c[0] + floor(t * 1.2 + i * 0.37) * 97), max(c[1], 850), c[2])
            else -> c
        }
    }

    /**
     * Onweer: een inslag bij één lamp met onweer (de bron, per inslag gekozen); de andere onweerlampen lichten later en
     * zwakker mee naarmate ze verder in de kamer van de bron staan. Tussen de inslagen brandt de lamp gedimd.
     */
    private fun storm(c: IntArray, l: Lamp, t: Double, group: List<Lamp>): IntArray {
        var flash = 0.0
        val slot = floor(t / 0.5)
        if (group.isNotEmpty()) for (k in 0..3) {           // ook de nagloed van inslagen van net daarvoor
            val s = slot - k
            if (rnd(s, 7) < 0.86) continue                   // gemiddeld ongeveer elke 3,5 seconde een inslag
            val src = group[(rnd(s, 11) * group.size).toInt().coerceIn(0, group.size - 1)]
            val d = when {
                l.id == src.id -> 0.0
                l.placed && src.placed -> hypot(l.x - src.x, l.y - src.y)
                else -> 3.0
            }
            // 0,12 seconde later per meter, en zwakker: op 2 meter nog ruim een derde
            flash = max(flash, exp(-d / 2.0) * flashCurve(t - s * 0.5 - d * 0.12))
        }
        val dim = c[2] * 0.25
        return intArrayOf(c[0], (c[1] * (1 - flash)).roundToInt(), (dim + (1000 - dim) * flash).roundToInt().coerceIn(10, 1000))
    }

    /** Verloop van één bliksemflits: fel, even terug, nog een keer fel, en dan uitdoven. */
    private fun flashCurve(tau: Double) = when {
        tau < 0 -> 0.0
        tau < 0.10 -> 1.0
        tau < 0.18 -> 0.35
        tau < 0.30 -> 0.85
        else -> exp(-(tau - 0.30) / 0.3)
    }

    /** Vast toeval per tijdvak en lamp (0..1), zodat fonkelingen en flitsen niet elke ronde opnieuw loten. */
    private fun rnd(a: Double, b: Int): Double { val x = sin(a * 12.9898 + b * 78.233) * 43758.5453; return x - floor(x) }

    /** Mengt twee kleuren via RGB, zodat de overgang geen omweg door de kleurencirkel maakt. */
    private fun mixHsv(a: IntArray, b: IntArray, f: Double): IntArray {
        val ca = Color.HSVToColor(floatArrayOf(a[0].toFloat(), a[1] / 1000f, 1f)); val cb = Color.HSVToColor(floatArrayOf(b[0].toFloat(), b[1] / 1000f, 1f))
        val r = (Color.red(ca) * (1 - f) + Color.red(cb) * f).roundToInt(); val g = (Color.green(ca) * (1 - f) + Color.green(cb) * f).roundToInt()
        val bl = (Color.blue(ca) * (1 - f) + Color.blue(cb) * f).roundToInt()
        val hsv = FloatArray(3); Color.RGBToHSV(r, g, bl, hsv)
        return intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), (a[2] * (1 - f) + b[2] * f).roundToInt())
    }
}
