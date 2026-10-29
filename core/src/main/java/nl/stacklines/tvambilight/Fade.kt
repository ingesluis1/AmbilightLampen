package nl.stacklines.tvambilight

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zacht wisselen: lampen in een paar seconden van hun huidige kleur naar een nieuwe stand laten overvloeien (sync
 * stoppen, startscherm, tv uit, scène aan of uit) in plaats van in één keer te springen. Na het overvloeien stuurt
 * de aanroeper zelf de precieze eindstand (witmodus, uit, de bewaarde stand).
 */
object Fade {
    private const val STEP = 100L
    /** Grijs dat een lamp krijgt als we niet weten wat hij laat zien: dan niet overvloeien. */
    private const val UNKNOWN = 0xFF555555.toInt()
    /** Doel "uit". */
    val OFF = intArrayOf(0, 0, 0)

    /** Lamp met de kleur waar hij heen moet: h 0..360, s en v 0..1000 (v = 0 is uit). */
    class Target(val lamp: Lamp, val worker: TuyaLamp, val hsv: IntArray)

    /** Hoe lang, uit de instelling "Zacht wisselen". */
    fun ms() = (Store.settings.switchFade * 1000).toLong()

    /** Overvloeien naar de doelen. Stopt meteen bij een onderbreking (de vlag blijft dan staan). */
    fun run(targets: List<Target>, ms: Long = ms()) {
        if (ms <= 0) return
        // Lagen en ledstrips vloeien zelf al (of hebben een eigen bericht): die slaan we over
        val go = targets.mapNotNull { t ->
            if (t.lamp.layers > 0 || t.lamp.category == "dd") return@mapNotNull null
            val f = from(t.worker) ?: return@mapNotNull null
            if (f[2] == 0 && t.hsv[2] == 0) null else t to f
        }
        if (go.isEmpty()) return
        val t0 = SystemClock.elapsedRealtime()
        var first = true
        try {
            while (true) {
                val k = ((SystemClock.elapsedRealtime() - t0).toDouble() / ms).coerceIn(0.0, 1.0)
                val e = k * k * (3 - 2 * k)   // rustig beginnen en rustig eindigen
                for ((t, f) in go) {
                    val c = mix(f, t.hsv, e)
                    val dps = LinkedHashMap<String, Any>()
                    if (first) { dps["20"] = true; dps["21"] = "colour" }
                    dps["24"] = TuyaLamp.hsvHex(c[0], c[1], max(10, c[2]))
                    t.worker.send(dps)
                    t.worker.shownColor = Color.HSVToColor(floatArrayOf(c[0].toFloat(), c[1] / 1000f, max(0.15f, c[2] / 1000f)))
                }
                first = false
                if (k >= 1.0) break
                Thread.sleep(STEP)
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Waar de lamp nu staat, uit wat we hem het laatst lieten zien; null = onbekend. */
    private fun from(w: TuyaLamp): IntArray? {
        val c = w.shownColor
        if (c == UNKNOWN) return null
        if (c == Color.BLACK) return OFF
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        return intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), (hsv[2] * 1000).roundToInt())
    }

    /** Tussen twee kleuren; de tint gaat de korte kant om, en vanaf of naar "uit" blijft de tint gelijk. */
    private fun mix(a: IntArray, b: IntArray, e: Double): IntArray {
        val ah = if (a[2] == 0) b[0] else a[0]; val bh = if (b[2] == 0) ah else b[0]
        val ast = if (a[2] == 0) b[1] else a[1]; val bs = if (b[2] == 0) ast else b[1]
        var d = bh - ah
        if (abs(d) > 180) d -= 360 * Integer.signum(d)
        val h = ((ah + d * e).roundToInt() % 360 + 360) % 360
        return intArrayOf(h, (ast + (bs - ast) * e).roundToInt(), (a[2] + (b[2] - a[2]) * e).roundToInt())
    }

    /** Kleur van een stand zoals de lamp hem meldt (functies 20..24); null = warm wit (zoals TuyaLamp.restore). */
    fun target(st: JSONObject?): IntArray {
        if (st == null) return white(500, 1000)
        if (st.opt("20") == false) return OFF
        return when (st.opt("21")) {
            "colour" -> (st.opt("24") as? String)?.takeIf { it.length == 12 }?.let { TuyaLamp.parseHsvHex(it) } ?: white(500, 1000)
            else -> white((st.opt("23") as? Number)?.toInt() ?: 500, (st.opt("22") as? Number)?.toInt() ?: 1000)
        }
    }

    /** Witlicht (temperatuur 0 warm..1000 koel, helderheid 0..1000) als kleur om naartoe te vloeien. */
    fun white(temp: Int, bright: Int): IntArray {
        val hsv = FloatArray(3); Color.colorToHSV(Engine.whiteRgb(temp, bright, 0.01f), hsv)
        return intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), (hsv[2] * 1000).roundToInt())
    }
}
