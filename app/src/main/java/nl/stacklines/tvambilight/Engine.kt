package nl.stacklines.tvambilight

import android.graphics.Color
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rekent uit hoe zwaar elke randzone van het beeld meetelt voor een lamp, op basis van zijn plek in de kamer.
 * De lamp wordt geprojecteerd op het vlak van de tv: links/rechts t.o.v. het midden van de tv (in tv-breedtes)
 * en hoogte (plafond = bovenrand, tafel = midden, vloer = onderkant). Hoe verder van de tv, hoe breder hij kijkt.
 */
object Mixer {
    class Weights(val left: DoubleArray, val top: DoubleArray, val right: DoubleArray)

    /** Positie van de lamp op het tv-vlak: x -1..1 is de breedte van de tv, y 1 = bovenrand. */
    fun project(layout: JSONObject, lamp: Lamp): DoubleArray? {
        val tv = layout.optJSONObject("tv") ?: return null
        if (!lamp.placed) return null
        val tx = tv.optDouble("x"); val ty = tv.optDouble("y")
        val w = tv.optDouble("w", 1.44).coerceAtLeast(0.3)
        val a = Math.toRadians(tv.optDouble("dir", 90.0))
        val fx = cos(a); val fy = sin(a)       // kijkrichting van het scherm, de kamer in
        val rx = fy; val ry = -fx               // "rechts" gezien vanaf de bank
        val dx = lamp.x - tx; val dy = lamp.y - ty
        val lateral = dx * rx + dy * ry
        val depth = max(0.0, dx * fx + dy * fy)
        val xs = (lateral / (w / 2)).coerceIn(-1.8, 1.8)
        val ys = lamp.heightY ?: when (lamp.height) { "ceiling" -> 1.0; "sill" -> 0.4; "table" -> 0.1; else -> -0.7 }
        return doubleArrayOf(xs, ys, depth)
    }

    fun weights(layout: JSONObject, lamp: Lamp, spread: Double, nLeft: Int, nTop: Int, nRight: Int, topX: Double? = null): Weights {
        // Bovenrand: alleen de bovenste zones, op de plek die bij de volgorde van links naar rechts hoort
        if (lamp.follow == "top" && topX != null) {
            val sig = 0.3 * spread
            val top = DoubleArray(nTop) { i -> val dx = topX - (-1 + (2.0 * i + 1) / nTop); exp(-dx * dx / (2 * sig * sig)) }
            return Weights(DoubleArray(nLeft), top, DoubleArray(nRight)).normalized()
        }
        val p = project(layout, lamp)
        if (p == null) {
            // Niet geplaatst: gemiddelde van het hele beeld
            return Weights(DoubleArray(nLeft) { 1.0 }, DoubleArray(nTop) { 1.0 }, DoubleArray(nRight) { 1.0 }).normalized()
        }
        val (xs, ys, depth) = Triple(p[0], p[1], p[2])
        // Vaste keuze: hele zijkant (aan de kant waar de lamp staat) of het hele beeld
        if (lamp.follow == "side") {
            val left = xs < 0
            return Weights(DoubleArray(nLeft) { if (left) 1.0 else 0.0 }, DoubleArray(nTop) { 0.0 },
                DoubleArray(nRight) { if (left) 0.0 else 1.0 }).normalized()
        }
        if (lamp.follow == "all") {
            return Weights(DoubleArray(nLeft) { 1.0 }, DoubleArray(nTop) { 1.0 }, DoubleArray(nRight) { 1.0 }).normalized()
        }
        val sigma = (0.28 + 0.08 * depth) * spread
        val hh = 9.0 / 16.0
        // Een staaflamp geeft licht van de vloer tot boven: gemiddelde over drie hoogtes
        val heights = if (lamp.shape == "rod") listOf(-0.7, 0.1, 0.9) else listOf(ys)
        fun gs(zx: Double, zy: Double) = heights.sumOf { h ->
            val dx = xs - zx; val dy = (h - zy) * hh
            exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
        }
        // Philips nummert met de klok mee: links van onder naar boven, boven van links naar rechts, rechts van boven naar onder
        val top = DoubleArray(nTop) { i -> gs(-1 + (2.0 * i + 1) / nTop, 1.0) }
        val left = DoubleArray(nLeft) { j -> gs(-1.0, -1 + (2.0 * j + 1) / nLeft) }
        val right = DoubleArray(nRight) { j -> gs(1.0, 1 - (2.0 * j + 1) / nRight) }
        return Weights(left, top, right).normalized()
    }

    private fun Weights.normalized(): Weights {
        val s = left.sum() + top.sum() + right.sum()
        if (s <= 0) return this
        return Weights(left.map { it / s }.toDoubleArray(), top.map { it / s }.toDoubleArray(), right.map { it / s }.toDoubleArray())
    }

    /** Kleur per laag (index 0 = onderste laag) van de kant van het beeld waar de lamp staat. */
    fun layers(layout: JSONObject, lamp: Lamp, z: ZoneSample, n: Int): List<DoubleArray> {
        val p = project(layout, lamp)
        val left = p == null || p[0] < 0
        // Zones van onder naar boven zetten
        val side = if (left) z.left else z.right.reversed()
        if (side.isEmpty()) return List(n) { DoubleArray(3) }
        val m = side.size
        return List(n) { i ->
            val pos = ((i + 0.5) / n) * m - 0.5            // plek tussen de zones, 0 = onderste
            val a = pos.toInt().coerceIn(0, m - 1); val b = (a + 1).coerceAtMost(m - 1)
            val f = (pos - a).coerceIn(0.0, 1.0)
            DoubleArray(3) { k -> side[a][k] * (1 - f) + side[b][k] * f }
        }
    }

    fun mix(w: Weights, z: ZoneSample): DoubleArray {
        val out = DoubleArray(3)
        fun add(ws: DoubleArray, zs: List<IntArray>) {
            for (i in ws.indices) { val c = zs.getOrNull(i) ?: continue; for (k in 0..2) out[k] += ws[i] * c[k] }
        }
        add(w.left, z.left); add(w.top, z.top); add(w.right, z.right)
        return out
    }
}

/** De sync-lus: meet de tv, rekent per lamp een kleur uit en stuurt die door. */
object Engine {
    val workers = ConcurrentHashMap<String, TuyaLamp>()
    @Volatile var running = false
        private set
    @Volatile var tvStatus = "Nog niet gemeten"
        private set
    @Volatile var lastSample: ZoneSample? = null
        private set

    /** Kalibratie: de tv toont gekleurde banden; tot dit tijdstip (elapsedRealtime) staat hij aan. */
    @Volatile private var calibUntil = 0L
    @Volatile private var calibStartedSync = false
    val calibrating get() = calibUntil > SystemClock.elapsedRealtime()

    /** Aantal banden = aantal zones aan de zijkant, zodat elke band precies één zone vult. */
    fun calibBands() = (lastSample?.left?.size ?: 0).let { if (it in 3..9) it else 5 }

    fun calib(on: Boolean) {
        if (on) {
            calibUntil = SystemClock.elapsedRealtime() + 10 * 60_000
            if (!running) { calibStartedSync = true; start() }
        } else endCalib()
    }

    /** Stopt de kalibratie, ook als de telefoon is weggelegd (na tien minuten). */
    fun endCalib() {
        calibUntil = 0L
        if (calibStartedSync) { calibStartedSync = false; if (!Store.syncWanted) stop() }
    }

    fun checkCalib() { if (calibUntil != 0L && !calibrating) endCalib() }
    private var thread: Thread? = null

    /** Houdt de verbindingen in lijn met de lampenlijst (nieuw, verwijderd, ander ip-adres). */
    @Synchronized
    fun syncWorkers() {
        val lamps = Store.lampsSnapshot().associateBy { it.id }
        for ((id, w) in workers) {
            val l = lamps[id]
            if (l == null || l.ip != w.lamp.ip || l.key != w.lamp.key) { w.stop(); workers.remove(id) }
        }
        for (l in lamps.values) if (!workers.containsKey(l.id) && l.ip.isNotBlank()) workers[l.id] = TuyaLamp(l)
    }

    /**
     * Kleurcorrectie bij weinig licht: veel RGB-lampen worden roze als ze zacht branden (de rode led overheerst).
     * Onder LOW_REF helderheid schuiven we de kleur geleidelijk naar de ingestelde tegenkleur (optellen in het tint/verzadiging-vlak).
     */
    fun lowCorrect(hue: Double, strength: Double, h: Int, sv: Int, vv: Int): IntArray {
        if (strength <= 0.001) return intArrayOf(h, sv, vv)
        val w = ((LOW_REF - vv) / LOW_REF.toDouble()).coerceIn(0.0, 1.0)
        if (w <= 0.0) return intArrayOf(h, sv, vv)
        val s = sv / 1000.0; val a = Math.toRadians(h.toDouble()); val c = Math.toRadians(hue)
        val x = cos(a) * s + cos(c) * strength * w
        val y = sin(a) * s + sin(c) * strength * w
        val nh = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).roundToInt() % 360
        return intArrayOf(nh, (Math.sqrt(x * x + y * y).coerceAtMost(1.0) * 1000).roundToInt(), vv)
    }
    private fun lowCorrect(l: Lamp, h: Int, sv: Int, vv: Int) = lowCorrect(l.lowHue, l.lowStrength, h, sv, vv)
    private const val LOW_REF = 350

    /** Lampen die nu een kalibratievoorbeeld tonen; de sync laat ze zolang met rust. */
    private val preview = ConcurrentHashMap<String, Long>()
    private val previewSaved = ConcurrentHashMap<String, JSONObject>()
    private fun inPreview(id: String) = (preview[id] ?: 0L) > SystemClock.elapsedRealtime()
    @Volatile private var previewBase = "grey"
    /** Voorbeeldkleur die de tv laat zien zolang er een lamp gekalibreerd wordt (grey/blue), anders null. */
    val previewRef get() = if (preview.keys.any { inPreview(it) }) previewBase else null

    /** Instellingen van het lopende voorbeeld per lamp: helderheid (0..1000), tint, sterkte, soort (white/blue). */
    private class Preview(val vv: Int, val hue: Double, val strength: Double, val base: String)
    private val previewArgs = ConcurrentHashMap<String, Preview>()
    @Volatile private var previewThread: Thread? = null

    /**
     * Kalibratievoorbeeld. "white": de lamp wisselt elke 1,5 seconde tussen zijn eigen witte leds (neutraal) en
     * kleurlicht zonder kleur met de correctie; goed ingesteld zie je geen verschil meer. "blue": donkerblauw, de tv toont hetzelfde.
     */
    fun previewLow(id: String, level: Double, hue: Double, strength: Double, base: String) {
        syncWorkers()
        val w = workers[id] ?: return
        if (!inPreview(id) && !running) w.queryStatus()?.let { previewSaved[id] = it }
        preview[id] = SystemClock.elapsedRealtime() + 5 * 60_000
        previewBase = base
        previewArgs[id] = Preview((level * 1000).roundToInt().coerceIn(10, 1000), hue, strength, base)
        showPreview(id, colour = true)
        synchronized(this) {
            if (previewThread?.isAlive != true) previewThread = Thread({
                var colour = true
                while (previewArgs.isNotEmpty()) {
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                    colour = !colour
                    for (pid in previewArgs.keys) {
                        if (!inPreview(pid)) { endPreview(pid); continue }
                        if (previewArgs[pid]?.base == "white") showPreview(pid, colour)
                    }
                }
            }, "preview").apply { isDaemon = true; start() }
        }
    }

    private fun showPreview(id: String, colour: Boolean) {
        val p = previewArgs[id] ?: return
        val w = workers[id] ?: return
        if (!colour) {
            w.send(mapOf("20" to true, "21" to "white", "22" to max(10, p.vv), "23" to WHITE_REF))
            w.shownColor = Color.rgb(255, 244, 229)
            return
        }
        val (bh, bs) = if (p.base == "blue") 220 to 450 else 0 to 0
        val c = lowCorrect(p.hue, p.strength, bh, bs, p.vv)
        w.send(mapOf("20" to true, "21" to "colour", "24" to TuyaLamp.hsvHex(c[0], c[1], c[2])))
        w.shownColor = Color.HSVToColor(floatArrayOf(c[0].toFloat(), c[1] / 1000f, max(0.15f, p.vv / 1000f)))
    }
    /** Kleurtemperatuur van het witte vergelijkingslicht (0 = warm, 1000 = koel); 600 is ongeveer 5000 K, neutraal wit. */
    private const val WHITE_REF = 600

    fun endPreview(id: String) {
        preview.remove(id); previewArgs.remove(id)
        val saved = previewSaved.remove(id)
        if (!running) workers[id]?.restore(saved)
    }

    fun endAllPreviews() { for (id in preview.keys.toList()) endPreview(id) }

    /** Gebruikt de nieuwste plek/hoogte van een lamp (de verbinding blijft dezelfde). */
    private fun current(id: String) = Store.lampsSnapshot().firstOrNull { it.id == id }

    fun blink(id: String) { syncWorkers(); workers[id]?.blink() }

    /**
     * Lamp die niet reageert opnieuw oppakken: sleutel opnieuw uit de Tuya-cloud (verandert na opnieuw koppelen),
     * het adres opnieuw zoeken, een verse verbinding maken en hem aanzetten. Geeft terug wat er gebeurd is.
     */
    fun revive(id: String): String {
        val before = Store.lampsSnapshot().firstOrNull { it.id == id } ?: return tr("Lamp niet gevonden", "Lamp not found")
        val notes = mutableListOf<String>()
        if (before.version == Wiz.VERSION) {
            try { Wiz.scan(2000) } catch (_: Exception) { }
        } else {
            if (Store.cloudId != null && Store.cloudSecret != null) {
                try { TuyaCloud.refresh() } catch (_: Exception) { notes += tr("cloud niet bereikbaar", "cloud not reachable") }
            }
            Discovery.ask()
            Thread.sleep(1500)
        }
        val l = Store.lampsSnapshot().firstOrNull { it.id == id } ?: return tr("Lamp niet gevonden", "Lamp not found")
        if (l.key != before.key) notes += tr("nieuwe sleutel", "new key")
        if (l.ip != before.ip) notes += tr("nieuw adres ", "new address ") + l.ip
        synchronized(this) { workers.remove(id)?.stop() }
        syncWorkers()
        val w = workers[id] ?: return tr("Geen ip-adres bekend", "No IP address known")
        val until = SystemClock.elapsedRealtime() + 6000
        while (!w.online && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        val extra = if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")"
        if (!w.online) {
            // Lokaal hangt hij (komt voor bij Tuya-lampen): via de cloud aanzetten, dat lukt vaak nog wel
            if (l.version != Wiz.VERSION && Store.cloudId != null && Store.cloudSecret != null) {
                val viaCloud = try { TuyaCloud.powerOn(id) } catch (_: Exception) { null }
                if (viaCloud == true) return tr("Lokaal reageert hij niet, maar via de cloud is hij aangezet. Meekleuren lukt pas weer na stroom eraf en erop.",
                    "Not responding locally, but switched on via the cloud. Following the picture only works again after a power cycle.") + extra
                if (viaCloud == false) return tr("Lamp is ook in de cloud offline. Haal de stroom er even af.", "Lamp is offline in the cloud too. Switch the power off and on.") + extra
            }
            return tr("Lamp reageert nog steeds niet. Haal de stroom er even af.", "Lamp still not responding. Switch the power off and on.") + extra
        }
        // Tijdens de sync weer op kleur zetten; de lus stuurt daarna vanzelf de juiste kleur
        w.send(if (running) mapOf("20" to true, "21" to "colour") else mapOf("20" to true))
        return tr("Lamp weer verbonden", "Lamp connected again") + extra
    }
    fun test(id: String) { syncWorkers(); workers[id]?.test() }
    fun setColor(id: String, rgb: Int) { syncWorkers(); workers[id]?.setColor(rgb) }

    /** Alle lampen na elkaar testen, zodat je ziet welke welke is. */
    fun testAll() {
        syncWorkers()
        Thread {
            for (l in Store.lampsSnapshot()) { workers[l.id]?.test(); Thread.sleep(4200) }
        }.start()
    }

    /** Staan alle lampen uit via de knop "Lampen uit"? */
    @Volatile var lampsOff = false

    /** Sync en scène stoppen en alle lampen uit (of weer aan in warm wit). */
    fun allLamps(on: Boolean) {
        if (!on) {
            val busy = running || SceneEngine.active != null
            Store.syncWanted = false; Store.save(); stop(); SceneEngine.stop()
            // Eerst het terugzetten van de sync of scène laten versturen, anders mengt het met "uit"
            if (busy) Thread.sleep(600)
        }
        syncWorkers()
        for (l in Store.lampsSnapshot().filter { it.enabled }) {
            val w = workers[l.id] ?: continue
            w.send(if (on) mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 300) else mapOf("20" to false))
            w.shownColor = if (on) 0xFFFFE1B4.toInt() else 0xFF555555.toInt()
        }
        lampsOff = !on
    }

    @Synchronized
    fun start() {
        if (running) return
        lampsOff = false
        SceneEngine.stop()
        syncWorkers()
        running = true
        thread = Thread(::loop, "sync").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread?.join(3000)
        thread = null
    }

    /** Lamp met aparte lagen: per laag gladstrijken, opvoeren en alleen versturen als hij genoeg veranderd is. */
    private fun syncLayers(l: Lamp, worker: TuyaLamp, sample: ZoneSample, s: Settings, alpha: Double,
                           smooth: HashMap<String, DoubleArray>, last: HashMap<String, IntArray>, lampOn: HashMap<String, Boolean>,
                           donor: DoubleArray) {
        if (lampOn[l.id] != true) { worker.send(mapOf("20" to true, "21" to "colour")); lampOn[l.id] = true }
        val cols = Mixer.layers(Store.layout, l, sample, l.layers)
        var shown = 0
        for ((i, target) in cols.withIndex()) {
            val key = l.id + "#" + i
            val cut = smooth[key]?.let { o -> (s.cuts && rgbDist(o, target) > CUT_DIST) } ?: true
            val sm = if (cut) target else smooth[key]?.let { o -> DoubleArray(3) { o[it] + alpha * (target[it] - o[it]) } } ?: target
            smooth[key] = sm
            val hsv = FloatArray(3)
            Color.RGBToHSV(sm[0].roundToInt().coerceIn(0, 255), sm[1].roundToInt().coerceIn(0, 255), sm[2].roundToInt().coerceIn(0, 255), hsv)
            var sat = boostSat(hsv, s)
            val raw = ((hsv[2] - black(s)) / (1 - black(s))).coerceAtLeast(0.0)
            val v = max(s.minLight, min(1.0, raw * s.brightness))
            val dk = darkMix(s, raw)
            val h0 = if (dk > 0.5) donor[0].roundToInt() % 360 else hsv[0].roundToInt() % 360
            // Laag zwart: helderheid 0 (de lamp blijft aan, alleen die laag gaat uit)
            val vv = if (s.minLight <= 0.001 && raw * s.brightness < 0.02) 0 else max(10, (v * 1000).roundToInt().coerceIn(0, 1000))
            val sv0 = (min(1.0, (sat * (1 - dk) + donor[1] * dk) * l.satFactor) * 1000).roundToInt().coerceIn(0, 1000)
            val corr = lowCorrect(l, h0, sv0, vv); val h = corr[0]; val sv = corr[1]
            if (i == cols.size / 2) shown = Color.HSVToColor(floatArrayOf(h.toFloat(), sv / 1000f, max(0.15f, vv / 1000f)))
            val prev = last[key]
            val dh = prev?.let { abs(h - it[0]).let { d -> min(d, 360 - d) } } ?: 999
            // Kleine drempel: liever veel kleine stapjes, dan lijkt het op overvloeien
            val fine = s.fade
            // Lagen zijn duur (één bericht per laag, ±150 ms): iets grovere drempel dan bij gewone lampen
            if (prev != null && dh < (if (fine) 3 else 5) && abs(sv - prev[1]) < (if (fine) 25 else 40) && abs(vv - prev[2]) < (if (fine) 20 else 35)) continue
            val prio = if (prev == null) 999 else dh + abs(sv - prev[1]) / 10 + abs(vv - prev[2]) / 10
            last[key] = intArrayOf(h, sv, vv)
            worker.sendLayer(i + 1, h, sv, vv, prio)
        }
        if (shown != 0) worker.shownColor = shown
    }

    /**
     * Kleuren voller maken, maar minder naarmate het beeld donkerder is: bij bijna zwart is de tint vooral ruis
     * (een vleug paars wordt anders fel roze).
     */
    private fun boostSat(hsv: FloatArray, s: Settings): Double {
        val sat = hsv[1].toDouble()
        if (sat <= 0.08) return sat
        val boosted = min(1.0, sat * satF(s) + satAdd(s))
        val g = (hsv[2] / 0.25).coerceIn(0.0, 1.0)
        return sat + (boosted - sat) * g
    }

    /**
     * Witmodus kiezen? Geeft de kleurtemperatuur (0 = warm, 1000 = koel) of null voor kleurmodus.
     * Grijs en bijna-wit: neutraal (blauwig grijs iets koeler). Geel en oranje met weinig verzadiging: warm wit.
     * Met een marge (wasWhite) zodat de lamp niet heen en weer springt tussen de twee modi.
     */
    private fun whiteTemp(hue: Double, sat: Double, wasWhite: Boolean): Int? {
        val warm = hue in 15.0..75.0
        val limit = if (warm) (if (wasWhite) 0.55 else 0.45) else (if (wasWhite) 0.26 else 0.18)
        if (sat > limit) return null
        return when {
            warm -> (WHITE_NEUTRAL * (1 - sat / 0.55)).roundToInt().coerceIn(0, WHITE_NEUTRAL)
            hue in 170.0..270.0 -> (WHITE_NEUTRAL + (1000 - WHITE_NEUTRAL) * (sat / 0.26)).roundToInt().coerceIn(WHITE_NEUTRAL, 1000)
            else -> WHITE_NEUTRAL
        }
    }
    private const val WHITE_NEUTRAL = 600

    /** Hoe witlicht met deze temperatuur en helderheid eruitziet, voor de kaart en de telefoon. */
    private fun whiteRgb(temp: Int, vv: Int): Int {
        val t = temp / 1000f; val k = max(0.15f, vv / 1000f)
        return Color.rgb(((255 - 35 * t) * k).roundToInt(), ((190 + 50 * t) * k).roundToInt(), ((110 + 145 * t) * k).roundToInt())
    }

    /** Afstand tussen twee kleuren (0..441); boven CUT_DIST telt het als scènewissel. */
    private fun rgbDist(a: DoubleArray, b: DoubleArray) = Math.sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
    private const val CUT_DIST = 160.0
    /** Onder deze helderheid (0..1) telt een kleur als zwart. */
    private const val BLACK = 0.07
    // Met Ambilight als bron bewerken we de kleuren niet extra: Ambilight doet dat al
    private fun amb(s: Settings) = s.source == "ambilight"
    private fun satF(s: Settings) = if (amb(s)) 1.0 else s.saturation
    private fun satAdd(s: Settings) = if (amb(s)) 0.0 else 0.1
    private fun black(s: Settings) = if (amb(s)) 0.0 else BLACK

    /** Bij (bijna) zwart beeld krijgen alle lampen dezelfde zacht warme tint in plaats van restjes kleur uit het beeld. */
    private const val DARK_H = 30
    private const val DARK_S = 0.3
    /** 1 = volledig donker (lamp op "Licht bij zwart beeld"), 0 = gewoon beeld; daartussen geleidelijk. */
    private fun darkMix(s: Settings, raw: Double): Double {
        if (s.minLight <= 0.001) return 0.0
        val level = raw * s.brightness
        return (1 - (level - s.minLight) / s.minLight).coerceIn(0.0, 1.0)
    }


    /** Lampen terugzetten volgens "Na stoppen" (bij stoppen en bij pauze omdat Ambilight uit staat). */
    private fun restoreAll(touched: Set<String>, saved: Map<String, JSONObject?>) {
        for (id in touched) {
                val w = workers[id] ?: continue
                val restore: Map<String, Any> = when (Store.settings.after) {
                    "off" -> mapOf("20" to false)
                    "white" -> mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 500)
                    else -> saved[id]?.let { st ->
                        val m = LinkedHashMap<String, Any>()
                        for (k in listOf("20", "21", "22", "23", "24")) if (st.has(k)) m[k] = st.get(k)
                        m
                    }?.takeIf { it.isNotEmpty() } ?: mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 500)
                }
                w.send(restore)
                w.shownColor = 0xFF555555.toInt()
            }
    }

    /** Lampen met kleur in deze meting: x, y, tint, verzadiging. */
    private val lit = ArrayList<DoubleArray>()
    /** Tint en verzadiging van het hele beeld, of null als het beeld (bijna) zwart is. */
    private var screenHS: DoubleArray? = null
    /** Zelfde voor de linker- en rechterhelft (zijkant plus die helft van de bovenrand). */
    private var leftHS: DoubleArray? = null
    private var rightHS: DoubleArray? = null

    /** Gemiddelde tint en verzadiging van een stel zones, of null als het daar zwart of grijs is. */
    private fun avgHS(zones: List<IntArray>, s: Settings, minV: Float = 0.03f): DoubleArray? {
        if (zones.isEmpty()) return null
        val avg = DoubleArray(3) { k -> zones.sumOf { it[k].toDouble() } / zones.size }
        val hv = FloatArray(3)
        Color.RGBToHSV(avg[0].roundToInt(), avg[1].roundToInt(), avg[2].roundToInt(), hv)
        return if (hv[2] > minV && hv[1] > 0.08) doubleArrayOf(hv[0].toDouble(), min(1.0, hv[1] * s.saturation + 0.1)) else null
    }
    /** Per lamp de gladgestreken donor-kleur als vector (cos·s, sin·s), zodat de tint niet verspringt. */
    private val donorSmooth = HashMap<String, DoubleArray>()

    /**
     * Kleur voor een lamp die op "Licht bij zwart beeld" staat. Instelling "half": het gemiddelde van de schermhelft
     * aan zijn kant. Instelling "lamp": de tint van de dichtstbijzijnde lamp die nog kleur heeft.
     * Lukt dat niet: het gemiddelde van het hele beeld, anders de zacht warme tint. Geeft (tint 0..360, verzadiging 0..1).
     */
    private fun donorFor(l: Lamp, alpha: Double): DoubleArray {
        val half = if (Store.settingsFor(l).darkColor == "half")
            Mixer.project(Store.layout, l)?.let { p -> if (p[0] < 0) leftHS else rightHS } else null
        val near = if (Store.settingsFor(l).darkColor == "half") null
            else lit.filter { !(it[0] == l.x && it[1] == l.y) }.minByOrNull { (it[0] - l.x) * (it[0] - l.x) + (it[1] - l.y) * (it[1] - l.y) }
        val hs = when {
            half != null -> half
            near != null -> doubleArrayOf(near[2], near[3])
            screenHS != null -> screenHS!!
            else -> doubleArrayOf(DARK_H.toDouble(), DARK_S)
        }
        val a = Math.toRadians(hs[0])
        val target = doubleArrayOf(cos(a) * hs[1], sin(a) * hs[1])
        val sm = donorSmooth[l.id]?.let { o -> doubleArrayOf(o[0] + alpha * (target[0] - o[0]), o[1] + alpha * (target[1] - o[1])) } ?: target
        donorSmooth[l.id] = sm
        val h = (Math.toDegrees(Math.atan2(sm[1], sm[0])) + 360) % 360
        return doubleArrayOf(h, Math.sqrt(sm[0] * sm[0] + sm[1] * sm[1]).coerceIn(0.0, 1.0))
    }

    private fun loop() {
        val saved = HashMap<String, JSONObject?>()
        val smooth = HashMap<String, DoubleArray>()
        val last = HashMap<String, IntArray>()
        val lampOn = HashMap<String, Boolean>()
        val whiteMode = HashMap<String, Boolean>()          // lamp staat nu in witmodus (witte leds)
        val lastWhite = HashMap<String, IntArray>()         // laatst gestuurde kleurtemperatuur en helderheid
        var weightsVersion = -1
        var weights = HashMap<String, Mixer.Weights>()
        var zoneCount = Triple(0, 0, 0)

        val startLamps = Store.lampsSnapshot().filter { it.enabled && workers.containsKey(it.id) }
        val touched = LinkedHashSet<String>()
        var blackSince = 0L; var paused = false
        for (l in startLamps) {
            val w = workers[l.id] ?: continue
            saved[l.id] = if (Store.settings.after == "restore") w.queryStatus() else null
            w.send(mapOf("20" to true, "21" to "colour"))
            lampOn[l.id] = true
            touched += l.id
        }

        try {
            while (running) {
                val t0 = SystemClock.elapsedRealtime()
                val s = Store.settings
                // Met vloeiende overgangen vaker meten; de gladheid per meting omrekenen zodat het even snel aanvoelt
                val hzEff = if (s.fade || Store.groups.any { it.settings.fade }) maxOf(s.hz, 25) else s.hz
                val sample = PhilipsTv.measured()
                // Tv uit of standby: de API van sommige tv's meldt Ambilight altijd uit, dus we kijken naar het beeld zelf.
                // Langer dan 20 seconden volledig zwart = tv uit: lampen terug naar hun gewone stand, tot er weer beeld is.
                if (s.followAmbi && sample != null) {
                    val maxC = (sample.left + sample.top + sample.right).maxOfOrNull { it.max() } ?: 0
                    if (maxC > 10) blackSince = 0L else if (blackSince == 0L) blackSince = t0
                    if (blackSince != 0L && t0 - blackSince > 20_000) {
                        if (!paused) { paused = true; restoreAll(touched, saved); lampOn.clear(); last.clear(); smooth.clear() }
                        tvStatus = "Tv lijkt uit"; Thread.sleep(500); continue
                    }
                }
                if (paused) { paused = false }
                if (sample == null) {
                    tvStatus = if (Store.tvPaired) "Tv niet bereikbaar" else "Tv nog niet gekoppeld"
                    Thread.sleep(2000); continue
                }
                tvStatus = "Verbonden"
                lastSample = sample
                val zc = Triple(sample.left.size, sample.top.size, sample.right.size)
                if (Store.version != weightsVersion || zc != zoneCount) {
                    weightsVersion = Store.version; zoneCount = zc
                    weights = HashMap()
                    // Lampen op "Bovenrand" krijgen op volgorde van links naar rechts een gelijk verdeelde plek op de bovenrand
                    val topLamps = Store.lampsSnapshot().filter { it.follow == "top" && it.placed }
                        .sortedBy { Mixer.project(Store.layout, it)?.get(0) ?: 0.0 }
                    val topX = topLamps.withIndex().associate { (k, l) -> l.id to (-1 + (2.0 * k + 1) / topLamps.size) }
                    for (l in Store.lampsSnapshot()) weights[l.id] = Mixer.weights(Store.layout, l, Store.settingsFor(l).spread, zc.first, zc.second, zc.third, topX[l.id])
                }
                // Voorronde voor donker beeld: welke lampen hebben nog kleur (tint + verzadiging), en wat is het gemiddelde van het hele beeld
                lit.clear()
                for (l in Store.lampsSnapshot()) {
                    if (!l.enabled || !l.placed || l.layers > 0) continue
                    val ls = Store.settingsFor(l)
                    val tgt = Mixer.mix(weights[l.id] ?: continue, sample)
                    val hv = FloatArray(3)
                    Color.RGBToHSV(tgt[0].roundToInt().coerceIn(0, 255), tgt[1].roundToInt().coerceIn(0, 255), tgt[2].roundToInt().coerceIn(0, 255), hv)
                    val raw = ((hv[2] - black(ls)) / (1 - black(ls))).coerceAtLeast(0.0)
                    if (raw * ls.brightness > ls.minLight * 2 && hv[1] > 0.08)
                        lit += doubleArrayOf(l.x, l.y, hv[0].toDouble(), min(1.0, hv[1] * satF(ls) + satAdd(ls)))
                }
                screenHS = avgHS(sample.left + sample.top + sample.right, s)
                // Helften: bij donker beeld zijn de randen vaak bijna zwart, dus hier een lagere drempel
                val nt = sample.top.size
                leftHS = avgHS(sample.left + sample.top.take(nt / 2), s, 0.015f)
                rightHS = avgHS(sample.right + sample.top.drop(nt - nt / 2), s, 0.015f)
                // Elke ronde opnieuw: lampen die tijdens de sync zijn toegevoegd doen meteen mee
                for (l in Store.lampsSnapshot()) {
                    val id = l.id
                    val worker = workers[id] ?: continue
                    if (!l.enabled) continue
                    // Lamp toont een kalibratievoorbeeld: niet overschrijven, en daarna opnieuw sturen
                    if (inPreview(id)) { last.remove(id); (0..5).forEach { last.remove("$id#$it") }; continue }
                    val ls = Store.settingsFor(l)
                    val la = 1 - Math.pow(1 - ls.smooth.coerceIn(0.01, 1.0), 15.0 / hzEff)
                    if (touched.add(id)) syncWorkers()
                    if (l.layers > 0) { syncLayers(l, worker, sample, ls, la, smooth, last, lampOn, donorFor(l, la)); continue }
                    val target = Mixer.mix(weights[id] ?: continue, sample)
                    // Grote, plotselinge verandering (scènewissel): direct springen; kleine: gladstrijken en overvloeien
                    val cut = smooth[id]?.let { o -> (ls.cuts && rgbDist(o, target) > CUT_DIST) } ?: true
                    val sm = if (cut) target else smooth[id]?.let { o -> DoubleArray(3) { o[it] + la * (target[it] - o[it]) } } ?: target
                    smooth[id] = sm
                    val hsv = FloatArray(3)
                    Color.RGBToHSV(sm[0].roundToInt().coerceIn(0, 255), sm[1].roundToInt().coerceIn(0, 255), sm[2].roundToInt().coerceIn(0, 255), hsv)
                    val sat = boostSat(hsv, ls)
                    // Zwartgrens zoals Ambilight: bijna-zwart telt als zwart, de rest wordt opnieuw verdeeld
                    val rawV = ((hsv[2] - black(ls)) / (1 - black(ls))).coerceAtLeast(0.0)
                    val v = max(ls.minLight, min(1.0, rawV * ls.brightness))
                    if (ls.minLight <= 0.001 && rawV * ls.brightness < 0.02) {
                        if (lampOn[id] != false) { worker.send(mapOf("20" to false)); lampOn[id] = false }
                        worker.shownColor = Color.BLACK
                        continue
                    }
                    val dk = darkMix(ls, rawV)
                    val dn = donorFor(l, la)
                    val vv = max(10, (v * 1000).roundToInt().coerceIn(0, 1000))
                    val corr = lowCorrect(l, if (dk > 0.5) dn[0].roundToInt() % 360 else hsv[0].roundToInt() % 360,
                        (min(1.0, (sat * (1 - dk) + dn[1] * dk) * l.satFactor) * 1000).roundToInt().coerceIn(0, 1000), vv)
                    val h = corr[0]; val sv = corr[1]
                    // Witte leds: bij (bijna) wit, grijs, warmgeel en donker beeld de witmodus van de lamp gebruiken
                    val wt = if (ls.whiteLeds && l.category != "dd") whiteTemp(
                        if (dk > 0.5) dn[0] else hsv[0].toDouble(), if (dk > 0.5) dn[1] else hsv[1].toDouble(), whiteMode[id] == true) else null
                    if (wt != null) {
                        val pw = lastWhite[id]
                        if (pw != null && whiteMode[id] == true && lampOn[id] == true && abs(wt - pw[0]) < 20 && abs(vv - pw[1]) < (if (ls.fade) 10 else 25)) continue
                        lastWhite[id] = intArrayOf(wt, vv); last.remove(id)
                        val dps = LinkedHashMap<String, Any>()
                        if (lampOn[id] != true) { dps["20"] = true; lampOn[id] = true }
                        if (whiteMode[id] != true) { dps["21"] = "white"; whiteMode[id] = true }
                        dps["22"] = vv.coerceIn(10, 1000); dps["23"] = wt
                        worker.send(dps)
                        worker.shownColor = whiteRgb(wt, vv)
                        continue
                    }
                    val backToColour = whiteMode[id] == true
                    if (backToColour) { whiteMode[id] = false; lastWhite.remove(id); last.remove(id) }
                    val prev = last[id]
                    val dh = prev?.let { abs(h - it[0]).let { d -> min(d, 360 - d) } } ?: 999
                    val fine = ls.fade
                    if (prev != null && lampOn[id] == true && dh < (if (fine) 2 else 4) && abs(sv - prev[1]) < (if (fine) 12 else 30) && abs(vv - prev[2]) < (if (fine) 10 else 25)) continue
                    last[id] = intArrayOf(h, sv, vv)
                    val dps = LinkedHashMap<String, Any>()
                    if (lampOn[id] != true) { dps["20"] = true; dps["21"] = "colour"; lampOn[id] = true }
                    if (backToColour) dps["21"] = "colour"
                    if (l.category == "dd") {
                        // Ledstrips (zoals de Wall Washer) faden zelf bij functie 24; het schilderbericht wisselt direct
                        if (dps.isNotEmpty()) worker.send(dps)
                        worker.sendAllSegments(h, sv, vv)
                    } else {
                        // Gewone lampen: functie 24; met vloeiende overgangen sturen we zelf vaker en in kleinere stapjes
                        dps["24"] = TuyaLamp.hsvHex(h, sv, vv)
                        worker.send(dps)
                    }
                    worker.shownColor = Color.HSVToColor(floatArrayOf(h.toFloat(), sv / 1000f, max(0.15f, vv / 1000f)))
                }
                val wait = 1000L / hzEff - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
        } finally {
            restoreAll(touched, saved)
            tvStatus = "Gestopt"
        }
    }
}
