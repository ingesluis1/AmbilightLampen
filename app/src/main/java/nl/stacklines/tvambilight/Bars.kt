package nl.stacklines.tvambilight

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zwarte balken opvangen. Een breedbeeldfilm (21:9) heeft boven en onder zwarte balken: dan meet Ambilight
 * aan de bovenrand zwart, terwijl er volop beeld is, en blijven lampen bij de bovenrand donker. Andersom bij
 * 4:3-beeld (balken links en rechts). Is een rand langer dan 1,5 seconde zwart terwijl de andere randen beeld
 * hebben, dan krijgt die rand de kleuren van de randen ernaast.
 */
object Bars {
    /** Onder deze waarde (0..255, hoogste kleurkanaal) telt een rand als zwart; boven LIT als beeld. */
    private const val DARK = 14
    private const val LIT = 40
    /** Eenmaal herkend: blijven zolang de andere randen nog een beetje beeld hebben (ook bij een donkere scène). */
    private const val KEEP = 12
    private const val AFTER_MS = 1500L

    private var topDarkSince = 0L
    private var sideDarkSince = 0L
    /** "top" (balken boven en onder), "side" (balken links en rechts) of "" (geen). */
    @Volatile var active = ""
        private set

    fun fix(z: ZoneSample, on: Boolean): ZoneSample {
        if (!on || z.top.isEmpty() || z.left.isEmpty() || z.right.isEmpty()) { reset(); return z }
        val now = SystemClock.elapsedRealtime()
        val topMax = z.top.maxOf { it.max() }
        val sideMax = max(z.left.maxOf { it.max() }, z.right.maxOf { it.max() })
        val topBars = topMax < DARK && sideMax > (if (active == "top") KEEP else LIT)
        val sideBars = !topBars && sideMax < DARK && topMax > (if (active == "side") KEEP else LIT)
        topDarkSince = if (topBars) (if (topDarkSince == 0L) now else topDarkSince) else 0L
        sideDarkSince = if (sideBars) (if (sideDarkSince == 0L) now else sideDarkSince) else 0L
        active = when {
            topDarkSince != 0L && now - topDarkSince > AFTER_MS -> "top"
            sideDarkSince != 0L && now - sideDarkSince > AFTER_MS -> "side"
            else -> ""
        }
        return when (active) {
            // Zijkanten lopen links van onder naar boven en rechts van boven naar onder: de bovenste zone is links de laatste, rechts de eerste
            "top" -> {
                val a = z.left.last(); val b = z.right.first(); val n = z.top.size
                ZoneSample(z.left, List(n) { i -> val f = (i + 0.5) / n; IntArray(3) { k -> (a[k] * (1 - f) + b[k] * f).roundToInt() } }, z.right)
            }
            "side" -> ZoneSample(List(z.left.size) { z.top.first().copyOf() }, z.top, List(z.right.size) { z.top.last().copyOf() })
            else -> z
        }
    }

    private fun reset() { topDarkSince = 0L; sideDarkSince = 0L; active = "" }
}
