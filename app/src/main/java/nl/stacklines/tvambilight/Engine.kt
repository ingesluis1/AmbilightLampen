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
        val ys = when (lamp.height) { "ceiling" -> 1.0; "sill" -> 0.4; "table" -> 0.1; else -> -0.7 }
        return doubleArrayOf(xs, ys, depth)
    }

    fun weights(layout: JSONObject, lamp: Lamp, spread: Double, nLeft: Int, nTop: Int, nRight: Int): Weights {
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

    /** Gebruikt de nieuwste plek/hoogte van een lamp (de verbinding blijft dezelfde). */
    private fun current(id: String) = Store.lampsSnapshot().firstOrNull { it.id == id }

    fun blink(id: String) { syncWorkers(); workers[id]?.blink() }
    fun test(id: String) { syncWorkers(); workers[id]?.test() }
    fun setColor(id: String, rgb: Int) { syncWorkers(); workers[id]?.setColor(rgb) }

    /** Alle lampen na elkaar testen, zodat je ziet welke welke is. */
    fun testAll() {
        syncWorkers()
        Thread {
            for (l in Store.lampsSnapshot()) { workers[l.id]?.test(); Thread.sleep(4200) }
        }.start()
    }

    @Synchronized
    fun start() {
        if (running) return
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
    private fun syncLayers(l: Lamp, worker: TuyaLamp, sample: ZoneSample, s: Settings,
                           smooth: HashMap<String, DoubleArray>, last: HashMap<String, IntArray>, lampOn: HashMap<String, Boolean>) {
        if (lampOn[l.id] != true) { worker.send(mapOf("20" to true, "21" to "colour")); lampOn[l.id] = true }
        val cols = Mixer.layers(Store.layout, l, sample, l.layers)
        var shown = 0
        for ((i, target) in cols.withIndex()) {
            val key = l.id + "#" + i
            val sm = smooth[key]?.let { o -> DoubleArray(3) { o[it] + s.smooth * (target[it] - o[it]) } } ?: target
            smooth[key] = sm
            val hsv = FloatArray(3)
            Color.RGBToHSV(sm[0].roundToInt().coerceIn(0, 255), sm[1].roundToInt().coerceIn(0, 255), sm[2].roundToInt().coerceIn(0, 255), hsv)
            var sat = hsv[1].toDouble(); if (sat > 0.08) sat = min(1.0, sat * s.saturation + 0.1)
            val v = max(s.minLight, min(1.0, hsv[2] * s.brightness))
            val h = hsv[0].roundToInt() % 360
            val sv = (sat * 1000).roundToInt().coerceIn(0, 1000); val vv = max(10, (v * 1000).roundToInt().coerceIn(0, 1000))
            if (i == cols.size / 2) shown = Color.HSVToColor(floatArrayOf(h.toFloat(), sv / 1000f, max(0.15f, vv / 1000f)))
            val prev = last[key]
            val dh = prev?.let { abs(h - it[0]).let { d -> min(d, 360 - d) } } ?: 999
            if (prev != null && dh < 5 && abs(sv - prev[1]) < 40 && abs(vv - prev[2]) < 35) continue
            last[key] = intArrayOf(h, sv, vv)
            worker.sendLayer(i + 1, h, sv, vv)
        }
        if (shown != 0) worker.shownColor = shown
    }

    private fun loop() {
        val saved = HashMap<String, JSONObject?>()
        val smooth = HashMap<String, DoubleArray>()
        val last = HashMap<String, IntArray>()
        val lampOn = HashMap<String, Boolean>()
        var weightsVersion = -1
        var weights = HashMap<String, Mixer.Weights>()
        var zoneCount = Triple(0, 0, 0)

        val startLamps = Store.lampsSnapshot().filter { it.enabled && workers.containsKey(it.id) }
        val touched = LinkedHashSet<String>()
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
                val sample = PhilipsTv.measured()
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
                    for (l in Store.lampsSnapshot()) weights[l.id] = Mixer.weights(Store.layout, l, s.spread, zc.first, zc.second, zc.third)
                }
                // Elke ronde opnieuw: lampen die tijdens de sync zijn toegevoegd doen meteen mee
                for (l in Store.lampsSnapshot()) {
                    val id = l.id
                    val worker = workers[id] ?: continue
                    if (!l.enabled) continue
                    if (touched.add(id)) syncWorkers()
                    if (l.layers > 0) { syncLayers(l, worker, sample, s, smooth, last, lampOn); continue }
                    val target = Mixer.mix(weights[id] ?: continue, sample)
                    val sm = smooth[id]?.let { o -> DoubleArray(3) { o[it] + s.smooth * (target[it] - o[it]) } } ?: target
                    smooth[id] = sm
                    val hsv = FloatArray(3)
                    Color.RGBToHSV(sm[0].roundToInt().coerceIn(0, 255), sm[1].roundToInt().coerceIn(0, 255), sm[2].roundToInt().coerceIn(0, 255), hsv)
                    var sat = hsv[1].toDouble()
                    if (sat > 0.08) sat = min(1.0, sat * s.saturation + 0.1)
                    val rawV = hsv[2].toDouble()
                    val v = max(s.minLight, min(1.0, rawV * s.brightness))
                    if (s.minLight <= 0.001 && rawV * s.brightness < 0.02) {
                        if (lampOn[id] != false) { worker.send(mapOf("20" to false)); lampOn[id] = false }
                        worker.shownColor = Color.BLACK
                        continue
                    }
                    val h = hsv[0].roundToInt() % 360
                    val sv = (sat * 1000).roundToInt().coerceIn(0, 1000)
                    val vv = max(10, (v * 1000).roundToInt().coerceIn(0, 1000))
                    val prev = last[id]
                    val dh = prev?.let { abs(h - it[0]).let { d -> min(d, 360 - d) } } ?: 999
                    if (prev != null && lampOn[id] == true && dh < 4 && abs(sv - prev[1]) < 30 && abs(vv - prev[2]) < 25) continue
                    last[id] = intArrayOf(h, sv, vv)
                    val dps = LinkedHashMap<String, Any>()
                    if (lampOn[id] != true) { dps["20"] = true; dps["21"] = "colour"; lampOn[id] = true }
                    dps["24"] = TuyaLamp.hsvHex(h, sv, vv)
                    worker.send(dps)
                    worker.shownColor = Color.HSVToColor(floatArrayOf(h.toFloat(), sv / 1000f, max(0.15f, vv / 1000f)))
                }
                val wait = 1000L / s.hz - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (_: InterruptedException) {
        } finally {
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
            tvStatus = "Gestopt"
        }
    }
}
