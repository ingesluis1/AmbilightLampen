package nl.stacklines.tvambilight

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
        if (!lamp.synced) return null
        val tx = tv.optDouble("x"); val ty = tv.optDouble("y")
        val w = tv.optDouble("w", 1.44).coerceAtLeast(0.3)
        val a = Math.toRadians(tv.optDouble("dir", 90.0))
        val fx = cos(a); val fy = sin(a)       // kijkrichting van het scherm, de kamer in
        val rx = fy; val ry = -fx               // "rechts" gezien vanaf de bank
        // Een gerichte spot rekent vanaf waar zijn licht ongeveer valt, niet vanaf de lamp zelf
        val (ax, ay) = aimShift(layout, lamp)
        val dx = lamp.x + ax - tx; val dy = lamp.y + ay - ty
        val lateral = dx * rx + dy * ry
        val depth = max(0.0, dx * fx + dy * fy)
        val xs = (lateral / (w / 2)).coerceIn(-1.8, 1.8)
        val ys = (lamp.heightY ?: when (lamp.height) { "ceiling" -> 1.0; "sill" -> 0.4; "table" -> 0.1; else -> -0.7 }) + tiltShift(layout, lamp)
        return doubleArrayOf(xs, ys, depth)
    }

    /** Wandspot omhoog of omlaag gekanteld (op de kaart): hij volgt dan een hoger of lager stuk van het beeld. */
    private fun tiltShift(layout: JSONObject, lamp: Lamp): Double {
        if (lamp.look != "wallspot") return 0.0
        return when (layout.optJSONObject("tilts")?.optString(lamp.id)) { "up" -> 0.45; "down" -> -0.45; else -> 0.0 }
    }

    /** Hoe ver (meter) het licht van een gerichte spot van de lamp af valt. */
    private const val AIM_REACH = 0.8

    /**
     * Verschuiving (meter) door de richting van een spot of wandspot, zoals ingesteld op de kaart: van de lamp zelf
     * (aims, gaat voor) of van de rails waar hij in zit (fixtures[].aim). 0 = rechts, 90 = naar voren op de kaart; geen richting =
     * recht omlaag, dus geen verschuiving. Een wandspot zonder richting schijnt naar voren (90).
     */
    fun aimShift(layout: JSONObject, lamp: Lamp): Pair<Double, Double> {
        if (lamp.look != "spot" && lamp.look != "wallspot") return 0.0 to 0.0
        var aim: Double? = null
        // Eigen richting van de lamp gaat voor die van de rails (-1 = recht omlaag)
        val aims = layout.optJSONObject("aims")
        if (aims != null && aims.has(lamp.id) && !aims.isNull(lamp.id)) {
            val own = aims.optDouble(lamp.id)
            if (own < 0) return 0.0 to 0.0
            val r = Math.toRadians(own)
            return AIM_REACH * cos(r) to AIM_REACH * sin(r)
        }
        var inFixture = false
        val fs = layout.optJSONArray("fixtures")
        if (fs != null) for (i in 0 until fs.length()) {
            val f = fs.optJSONObject(i) ?: continue
            val ls = f.optJSONArray("lamps") ?: continue
            if ((0 until ls.length()).none { ls.optString(it) == lamp.id }) continue
            inFixture = true
            if (f.has("aim") && !f.isNull("aim")) aim = f.optDouble("aim")
            break
        }
        if (aim == null && !inFixture && lamp.look == "wallspot") aim = 90.0
        val deg = aim ?: return 0.0 to 0.0
        val r = Math.toRadians(deg)
        return AIM_REACH * cos(r) to AIM_REACH * sin(r)
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
    val calibrating get() = calibUntil > SystemClock.elapsedRealtime()

    /** Zones aan de zijkant volgens de tv zelf (topology); opgehaald bij het begin van de kalibratie. */
    @Volatile private var sideZones: Int? = null

    /**
     * Aantal banden = aantal zones aan de zijkant, zodat elke band precies één zone vult (de lamp toont dan zuiver
     * de kleur van zijn band). Tussenhoogtes kiest de telefoon zelf, tussen twee banden in.
     */
    fun calibBands() = (sideZones ?: lastSample?.left?.size ?: 3).let { if (it in 2..9) it else 3 }

    fun calib(on: Boolean) {
        if (on) {
            if (sideZones == null) sideZones = PhilipsTv.sideZones()
            calibUntil = SystemClock.elapsedRealtime() + 10 * 60_000
            tempStart("calib")
        } else endCalib()
    }

    /** Stopt de kalibratie, ook als de telefoon is weggelegd (na tien minuten). */
    fun endCalib() {
        calibUntil = 0L
        tempEnd("calib")
    }

    fun checkCalib() { if (calibUntil != 0L && !calibrating) endCalib(); checkDemo() }

    /** Demo van een instelling: de tv toont een bewegend testbeeld zolang je die instelling aanpast (30 s na de laatste wijziging). */
    @Volatile var demoKey = ""
        private set
    @Volatile private var demoUntil = 0L

    fun demo(key: String) {
        // "Demo tonen" uit: geen testbeeld (een lopende demo stopt)
        if (key.isBlank() || !Store.settings.demos) { if (demoKey.isNotEmpty() || key.isBlank()) endDemo(); return }
        demoKey = key
        demoUntil = SystemClock.elapsedRealtime() + 30_000
        tempStart("demo")
    }

    private fun endDemo() {
        demoKey = ""; demoUntil = 0L
        tempEnd("demo")
    }

    /**
     * Redenen waarom de sync tijdelijk draait (demo, calib, rainbow) terwijl je hem zelf niet hebt aangezet.
     * Pas als de laatste reden klaar is, stopt hij weer; heb je de sync zelf aangezet, dan blijft hij aan.
     */
    private val tempReasons = HashSet<String>()

    fun tempStart(reason: String) {
        synchronized(tempReasons) {
            if (running && tempReasons.isEmpty()) return   // draait al omdat jij hem aanzette
            tempReasons += reason
        }
        start()
    }

    fun tempEnd(reason: String) {
        val last = synchronized(tempReasons) { tempReasons.remove(reason) && tempReasons.isEmpty() }
        if (last && !Store.syncWanted) stop()
    }

    private fun checkDemo() { if (demoUntil != 0L && demoUntil < SystemClock.elapsedRealtime()) endDemo() }
    private var thread: Thread? = null

    /**
     * Houdt de verbindingen in lijn met de lampenlijst (nieuw, verwijderd, ander ip-adres, sleutel of protocol).
     * Een eigen slot, zodat de sync-lus dit kan aanroepen terwijl stop() op die lus wacht.
     */
    private val workersLock = Object()
    fun syncWorkers() = synchronized(workersLock) {
        Store.normalizeRooms()   // nieuwe lampen: bij één kamer meteen in die kamer
        val lamps = Store.lampsSnapshot().associateBy { it.id }
        for ((id, w) in workers) {
            val l = lamps[id]
            // De Lamp wordt ter plekke aangepast; de verbinding houdt de waarden waarmee hij gestart is
            if (l == null || !l.isLight || l.ip != w.connIp || l.key != w.connKey || l.version != w.connVersion) { w.stop(); workers.remove(id) }
        }
        // Alleen lampen krijgen een lamp-verbinding; een airco heeft andere functies (die bedienen we later apart)
        for (l in lamps.values) if (l.isLight && !workers.containsKey(l.id) && l.ip.isNotBlank()) workers[l.id] = TuyaLamp(l)
    }

    /** Vraagt de stand van een stel lampen tegelijk op (wacht hooguit één keer, niet per lamp). */
    fun queryAll(ids: Collection<String>): Map<String, JSONObject> {
        val out = ConcurrentHashMap<String, JSONObject>()
        val threads = ids.mapNotNull { id -> workers[id]?.let { w -> Thread { try { w.queryStatus()?.let { out[id] = it } } catch (_: Exception) { } }.apply { isDaemon = true; start() } } }
        try { threads.forEach { it.join(3000) } } catch (e: InterruptedException) { threads.forEach { it.interrupt() }; throw e }
        return out
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
    /** Voorbeeldkleur die de tv laat zien zolang er een lamp gekalibreerd wordt (grey/blue), anders null. Niet bij kleurbalans. */
    val previewRef get() = if (preview.keys.any { inPreview(it) && previewArgs[it]?.solid == null }) previewBase else null

    /** Instellingen van het lopende voorbeeld per lamp: helderheid (0..1000), tint, sterkte, soort (white/blue). */
    private class Preview(val vv: Int, val hue: Double, val strength: Double, val base: String, val solid: IntArray? = null)
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
        ensurePreviewThread()
    }

    /** Eén thread voor alle voorbeelden: wisselt "white" elke 1,5 seconde en ruimt verlopen voorbeelden op. */
    private fun ensurePreviewThread() = synchronized(this) {
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

    /** Voorbeeld tonen en stoppen na elkaar: anders kon de voorbeeld-thread net na het stoppen nog één kleur sturen. */
    private val previewLock = Object()

    private fun showPreview(id: String, colour: Boolean): Unit = synchronized(previewLock) {
        val p = previewArgs[id] ?: return
        val w = workers[id] ?: return
        // Kleurbalans: vaste testkleur; de balans van de lamp komt er bij het versturen overheen
        p.solid?.let { c ->
            w.send(mapOf("20" to true, "21" to "colour", "24" to TuyaLamp.hsvHex(c[0], c[1], c[2])))
            w.shownColor = Scene.hsv(c)
            return
        }
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
        val saved = synchronized(previewLock) { preview.remove(id); previewArgs.remove(id); previewSaved.remove(id) }
        if (!running) workers[id]?.restore(saved)
    }

    fun endAllPreviews() { for (id in preview.keys.toList()) endPreview(id) }

    /**
     * Kleurbalans instellen: deze lamp en de referentielamp tonen allebei dezelfde testkleur (h, s, v), zodat je
     * de schuiven kunt bijstellen tot ze gelijk zijn. Opnieuw aanroepen (na het schuiven) stuurt de kleur opnieuw.
     */
    fun previewMatch(id: String, ref: String, colour: IntArray) {
        syncWorkers()
        for (x in listOf(id, ref).distinct()) {
            val w = workers[x] ?: continue
            if (!inPreview(x) && !running) w.queryStatus()?.let { previewSaved[x] = it }
            preview[x] = SystemClock.elapsedRealtime() + 5 * 60_000
            previewArgs[x] = Preview(colour[2], 0.0, 0.0, "solid", colour)
            showPreview(x, colour = true)
        }
        ensurePreviewThread()
    }

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
        } else if (before.version == Hue.VERSION) {
            try { Hue.refresh() } catch (e: Exception) { notes += e.message ?: tr("Bridge niet bereikbaar", "Bridge not reachable") }
        } else if (before.version == Dirigera.VERSION) {
            try { Dirigera.refresh() } catch (e: Exception) { notes += e.message ?: tr("DIRIGERA niet bereikbaar", "DIRIGERA not reachable") }
        } else {
            if (Store.cloudConfigured) {
                try { TuyaCloud.refresh() } catch (_: Exception) { notes += tr("cloud niet bereikbaar", "cloud not reachable") }
            }
            Discovery.lookupNew()
            val until = SystemClock.elapsedRealtime() + if (before.ip.isBlank()) 6000 else 1500
            while (SystemClock.elapsedRealtime() < until) {
                Thread.sleep(250)
                if (before.ip.isBlank() && Store.lampsSnapshot().firstOrNull { it.id == id }?.ip?.isNotBlank() == true) break
            }
        }
        val l = Store.lampsSnapshot().firstOrNull { it.id == id } ?: return tr("Lamp niet gevonden", "Lamp not found")
        if (l.key != before.key) notes += tr("nieuwe sleutel", "new key")
        if (l.ip != before.ip) notes += tr("nieuw adres ", "new address ") + l.ip
        synchronized(workersLock) { workers.remove(id)?.stop() }
        syncWorkers()
        val w = workers[id] ?: return tr("Geen ip-adres bekend", "No IP address known")
        val until = SystemClock.elapsedRealtime() + 6000
        while (!w.online && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        val extra = if (notes.isEmpty()) "" else " (" + notes.joinToString(", ") + ")"
        if (!w.online) {
            // Lokaal hangt hij (komt voor bij Tuya-lampen): via de cloud aanzetten, dat lukt vaak nog wel
            if (l.version == Dirigera.VERSION) return tr("De DIRIGERA bereikt de lamp niet. Staat hij aan (lichtschakelaar)? Kijk ook in de IKEA Home smart-app.",
                "The DIRIGERA can't reach the lamp. Is it powered (light switch)? Check the IKEA Home smart app too.") + extra
            if (l.version == Hue.VERSION) return tr("De Bridge bereikt de lamp niet. Staat hij aan (lichtschakelaar)? Kijk ook in de Hue-app.",
                "The Bridge can't reach the lamp. Is it powered (light switch)? Check the Hue app too.") + extra
            if (l.version != Wiz.VERSION && Store.cloudConfigured) {
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

    /** Eén lamp aan of uit (buiten sync en scènes). */
    fun power(id: String, on: Boolean) { syncWorkers(); workers[id]?.send(mapOf("20" to on)) }

    /** Alle lampen na elkaar testen, zodat je ziet welke welke is. */
    fun testAll() {
        syncWorkers()
        Thread {
            for (l in Store.lampsSnapshot()) { workers[l.id]?.test(); Thread.sleep(4200) }
        }.start()
    }

    /** Staan alle lampen uit via de knop "Lampen uit"? */
    @Volatile var lampsOff = false

    /**
     * Lampen van één kamer uit (of weer aan in warm wit); een scène daar stopt. De tv-kamer: ook de sync stopt.
     * room "*" = hele huis, maar bij uit blijft de tv-kamer aan als de tv synct of daar een scène loopt.
     * Geeft de namen van de kamers die bleven staan.
     */
    fun roomLamps(room: String, on: Boolean): List<String> {
        val tv = Store.tvRoom
        val targets = if (room == "*") Store.rooms.filter { r -> on || !(r === tv && (running || SceneEngine.activeIn(r.id) != null)) }
            else listOfNotNull(Store.room(room))
        for (r in targets) {
            if (r === tv) { if (running) { Platform.userStopped(); Store.syncWanted = false; Store.save(); stop(handOver = true) }; lampsOff = !on }
            SceneEngine.stop(handOver = true, room = r.id)
        }
        syncWorkers()
        val ids = targets.map { it.id }.toSet() + (if (room == "*") setOf("") else emptySet())
        for (l in Store.lampsSnapshot().filter { it.enabled && it.room in ids }) {
            val w = workers[l.id] ?: continue
            w.send(if (on) mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 300) else mapOf("20" to false))
            w.shownColor = if (on) 0xFFFFE1B4.toInt() else 0xFF555555.toInt()
        }
        return Store.rooms.filter { it !in targets }.map { it.name }.takeIf { room == "*" } ?: emptyList()
    }

    /** Sync en scène stoppen en alle lampen uit (of weer aan in warm wit). */
    fun allLamps(on: Boolean) {
        if (!on) {
            Platform.userStopped()
            // Niet eerst terugzetten: de lampen gaan meteen uit
            Store.syncWanted = false; Store.save(); stop(handOver = true); SceneEngine.stopAll(handOver = true)
        }
        syncWorkers()
        for (l in Store.lampsSnapshot().filter { it.enabled }) {
            val w = workers[l.id] ?: continue
            w.send(if (on) mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 300) else mapOf("20" to false))
            w.shownColor = if (on) 0xFFFFE1B4.toInt() else 0xFF555555.toInt()
        }
        lampsOff = !on
    }

    fun start() {
        // Buiten het slot van de Engine: SceneEngine.start houdt zijn eigen slot vast en roept Engine.stop aan.
        // De sync neemt het over, dus de scène hoeft de lampen niet eerst terug te zetten.
        if (!running) SceneEngine.stop(handOver = true)
        synchronized(this) {
            if (running) return
            // Vorige lus eerst laten terugzetten, anders lopen twee lussen door elkaar
            thread?.let { if (it.isAlive) it.join(3000) }
            lampsOff = false
            syncWorkers()
            running = true
            startedAt = System.currentTimeMillis()
            thread = Thread(::loop, "sync").apply { isDaemon = true; start() }
        }
    }

    /** handOver: lampen niet terugzetten, er neemt meteen iets anders over; de bewaarde stand staat dan in handedOver. */
    fun stop(handOver: Boolean = false) {
        val t = synchronized(this) {
            if (!running) return
            if (handOver) { handedOver = emptyMap(); handOff = true }
            running = false
            synchronized(tempReasons) { tempReasons.clear() }
            thread
        } ?: return
        t.interrupt()
        if (t !== Thread.currentThread()) t.join(3000)
    }

    /** Scène voor "Na stoppen" (after = "scene:<id>"), of null. */
    private fun afterScene(): Scene? = Store.settings.after.let { if (it.startsWith("scene:")) SceneEngine.find(it.removePrefix("scene:")) else null }

    /** Zet de lus in finally de lampen niet terug, maar laat hij de bewaarde stand achter voor de scène van "Na stoppen". */
    @Volatile private var handOff = false
    @Volatile var handedOver: Map<String, JSONObject?> = emptyMap()
        private set

    /** Sync stoppen met de knop: daarna "Na stoppen" uitvoeren, ook als dat een scène is. */
    fun stopByUser() { stopThen(afterScene()) }

    /**
     * Sync stoppen en daarna deze scène starten (of gewoon stoppen bij null). De scène zet de lampen bij het stoppen
     * terug naar hoe ze vóór de sync stonden. Geeft de gestarte scène terug.
     */
    fun stopThen(sc: Scene?): Scene? {
        if (sc == null || !running) { stop(); return null }
        stop(handOver = true)
        SceneEngine.start(sc, handedOver)
        return sc
    }

    /** Profiel van de app op de tv (zie [Profiles]) en de instellingen met dat profiel erover, per groep. */
    private var profile = ""
    private var profileVersion = -1
    private val profiled = HashMap<Settings, Settings>()
    private fun eff(base: Settings): Settings = if (profile.isEmpty()) base else profiled.getOrPut(base) { Profiles.apply(base, profile) }

    /** Wanneer de sync voor het laatst gestart is (ms); "alleen na zonsondergang" stopt bij zonsopkomst een sync van de avond ervoor. */
    @Volatile var startedAt = 0L
        private set

    /** Pauze (tv uit of standby, of startscherm in beeld); lampen staan dan in hun gewone stand (of in de startschermscène). */
    @Volatile var paused = false
        private set

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
            val vv = if (s.minLight <= 0.001 && raw * s.brightness < 0.02) 0 else max(10, (v * l.maxBright * 1000).roundToInt().coerceIn(0, 1000))
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

    /** Hoe witlicht met deze temperatuur (0 warm..1000 koel) en helderheid eruitziet; floor = minimale helderheid voor de weergave. */
    fun whiteRgb(temp: Int, vv: Int, floor: Float = 0.15f): Int {
        val t = temp / 1000f; val k = max(floor, (vv / 1000f).coerceAtMost(1f))
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
        // Eerst zacht naar de nieuwe stand toe, dan precies die stand
        val lamps = Store.lampsSnapshot().associateBy { it.id }
        Fade.run(touched.mapNotNull { id ->
            val w = workers[id] ?: return@mapNotNull null
            val l = lamps[id] ?: return@mapNotNull null
            Fade.Target(l, w, when (Store.settings.after) { "off" -> Fade.OFF; "white" -> Fade.target(null); else -> Fade.target(saved[id]) })
        })
        for (id in touched) {
                val w = workers[id] ?: continue
                when (Store.settings.after) {
                    "off" -> { w.send(mapOf("20" to false)); w.shownColor = 0xFF555555.toInt() }
                    "white" -> { w.send(TuyaLamp.WARM_WHITE); w.shownColor = 0xFF555555.toInt() }
                    else -> w.restore(saved[id])
                }
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

        // Alleen lampen die in de kamer staan: een niet-geplaatste lamp hoort (nog) niet bij de tv
        val startLamps = Store.lampsSnapshot().filter { it.enabled && it.synced && workers.containsKey(it.id) }
        val touched = LinkedHashSet<String>()
        var blackSince = 0L
        var pauseScene = ""   // id van de scène die nu speelt tijdens de pauze (startscherm of tv uit)
        paused = false

        try {
            // Binnen try: wordt de sync tijdens het opvragen al gestopt, dan worden de lampen toch teruggezet
            // Ook voor scènes: die zetten de lampen na afloop terug naar deze stand
            if (Store.settings.after == "restore" || Store.settings.after.startsWith("scene:") || Store.settings.offScene.isNotEmpty()) saved.putAll(queryAll(startLamps.map { it.id }))
            for (l in startLamps) {
                val w = workers[l.id] ?: continue
                touched += l.id
                w.send(mapOf("20" to true, "21" to "colour"))
                lampOn[l.id] = true
            }
            while (running) {
                val t0 = SystemClock.elapsedRealtime()
                // Andere app of andere instellingen: profiel opnieuw toepassen
                val p = Profiles.current()
                if (p != profile || Store.version != profileVersion) { profile = p; profileVersion = Store.version; profiled.clear() }
                val s = eff(Store.settings)
                // Met vloeiende overgangen vaker meten; de gladheid per meting omrekenen zodat het even snel aanvoelt
                val hzEff = if (s.fade || Store.groups.any { eff(it.settings).fade }) maxOf(s.hz, 25) else s.hz
                // Startscherm, schermbeveiliging of tv-instellingen: niets te kijken, lampen in hun gewone stand.
                // Demo en kalibratie draaien in deze app zelf en tellen dus als "kijken".
                val home = s.pauseHome && Platform.homeScreen()
                val homeSc = if (home) SceneEngine.find(s.homeScene) else null
                // Met een startschermscène toch meten: zo zien we nog of de tv uit gaat en stopt de scène dan
                val sample = if (home && homeSc == null) null else PhilipsTv.measured()?.let { Bars.fix(it, s.fixBars) }
                // Tv uit of standby: de API van sommige tv's meldt Ambilight altijd uit, dus we kijken naar het beeld zelf.
                // Langer dan 20 seconden volledig zwart = tv uit: lampen terug naar hun gewone stand, tot er weer beeld is.
                // Op het startscherm telt geen meting ook als zwart (tv in standby geeft soms geen antwoord meer).
                if (s.followAmbi && (sample != null || homeSc != null)) {
                    val maxC = sample?.let { (it.left + it.top + it.right).maxOfOrNull { z -> z.max() } } ?: 0
                    if (maxC > 10) blackSince = 0L else if (blackSince == 0L) blackSince = t0
                }
                val tvOff = s.followAmbi && blackSince != 0L && t0 - blackSince > 20_000
                if (home || tvOff) {
                    val sc = if (tvOff) SceneEngine.find(s.offScene) else homeSc
                    if (!paused || (sc?.id ?: "") != pauseScene) {
                        if (pauseScene.isNotEmpty()) SceneEngine.stopPause()
                        if (sc != null) SceneEngine.startPause(sc, touched.toSet()) else restoreAll(touched, saved)
                        pauseScene = sc?.id ?: ""
                        paused = true
                        lampOn.clear(); last.clear(); smooth.clear(); whiteMode.clear(); lastWhite.clear()
                    }
                    tvStatus = if (tvOff) "Tv lijkt uit" else "Startscherm"; Thread.sleep(500); continue
                }
                if (pauseScene.isNotEmpty()) { SceneEngine.stopPause(); pauseScene = "" }
                paused = false
                if (sample == null) {
                    tvStatus = if (Store.tvPaired) "Tv niet bereikbaar" else "Tv nog niet gekoppeld"
                    Thread.sleep(2000); continue
                }
                tvStatus = "Verbonden"
                lastSample = sample
                val frameLamps = Store.lampsSnapshot()
                val zc = Triple(sample.left.size, sample.top.size, sample.right.size)
                if (Store.version != weightsVersion || zc != zoneCount) {
                    weightsVersion = Store.version; zoneCount = zc
                    weights = HashMap()
                    // Instellingen gewijzigd (kleurbalans, helderheid...): elke lamp één keer opnieuw sturen, ook bij stilstaand beeld
                    last.clear(); lastWhite.clear()
                    // Lampen op "Bovenrand" krijgen op volgorde van links naar rechts een gelijk verdeelde plek op de bovenrand
                    val topLamps = frameLamps.filter { it.follow == "top" && it.synced }
                        .sortedBy { Mixer.project(Store.layout, it)?.get(0) ?: 0.0 }
                    val topX = topLamps.withIndex().associate { (k, l) -> l.id to (-1 + (2.0 * k + 1) / topLamps.size) }
                    for (l in frameLamps) weights[l.id] = Mixer.weights(Store.layout, l, Store.settingsFor(l).spread, zc.first, zc.second, zc.third, topX[l.id])
                }
                // Voorronde voor donker beeld: welke lampen hebben nog kleur (tint + verzadiging), en wat is het gemiddelde van het hele beeld
                lit.clear()
                for (l in frameLamps) {
                    if (!l.enabled || !l.synced || l.layers > 0) continue
                    val ls = eff(Store.settingsFor(l))
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
                for (l in frameLamps) {
                    val id = l.id
                    val worker = workers[id] ?: continue
                    if (!l.enabled || !l.synced) continue
                    // Lamp toont een kalibratievoorbeeld: niet overschrijven, en daarna alles opnieuw sturen.
                    // Het voorbeeld zet de lamp zelf aan en in wit- of kleurmodus; zonder dit vergeten stuurde de sync
                    // daarna soms geen "21" mee of sloeg hij de lamp over, en bleef die op de voorbeeldkleur hangen.
                    if (inPreview(id)) {
                        last.remove(id); (0..5).forEach { last.remove("$id#$it") }
                        lampOn.remove(id); whiteMode.remove(id); lastWhite.remove(id)
                        continue
                    }
                    val ls = eff(Store.settingsFor(l))
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
                    val vv = max(10, (v * l.maxBright * 1000).roundToInt().coerceIn(0, 1000))
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
                        // Witte leds zijn veel feller dan de kleurleds bij dezelfde stand: terugschalen
                        val wv = (vv * ls.whiteLevel).roundToInt().coerceIn(10, 1000)
                        dps["22"] = wv; dps["23"] = wt
                        worker.send(dps)
                        worker.shownColor = whiteRgb(wt, wv)
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
        } catch (e: Exception) {
            // Onverwachte fout in de sync: melden en netjes stoppen (lampen terug), niet de app laten crashen
            AppLog.add("Sync: " + (e.message ?: e.javaClass.simpleName))
            running = false
        } finally {
            // Gestopt via een onderbreking: die vlag weg, anders stopt het overvloeien meteen
            Thread.interrupted()
            paused = false
            if (pauseScene.isNotEmpty()) SceneEngine.stopPause()
            if (handOff) { handOff = false; handedOver = HashMap(saved) } else restoreAll(touched, saved)
            tvStatus = "Gestopt"
        }
    }
}
