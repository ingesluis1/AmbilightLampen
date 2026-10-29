package nl.stacklines.tvambilight

import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Wat per platform anders is (tv-app of server). Standaard zoals op een gewone JVM;
 * de tv-app vult de Android-versies in bij het opstarten, vóór er iets anders draait.
 */
object Platform {
    /** Monotone klok in ms. Android: SystemClock.elapsedRealtime (telt ook standby mee). */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    /** Je zette de sync zelf uit of koos een scène (tv: deze avond niet opnieuw vanzelf starten). */
    @Volatile var userStopped: () -> Unit = {}
    /** Staat het startscherm, de schermbeveiliging of de tv-instellingen in beeld? */
    @Volatile var homeScreen: () -> Boolean = { false }
    /** Pakketnaam van de app die nu op de tv staat (niet het startscherm), of null als onbekend. */
    @Volatile var currentApp: () -> String? = { null }
    /** Multicast-ontvangst aanhouden (Android zet die anders uit om stroom te sparen). */
    @Volatile var multicastLock: () -> Unit = {}
    /** Tuya: verbinding loslaten na zoveel ms zonder opdrachten (0 = altijd verbonden, zoals op de tv). Server: de tv moet de lampen ook kunnen bereiken. */
    @Volatile var idleCloseMs: Long = 0
    /** Zoekt een mDNS-dienst (bv. "_ihsp._tcp"); geeft het eerste adres dat [accept] goedkeurt, of null. */
    @Volatile var findService: (type: String, timeoutMs: Long, accept: (String) -> Boolean) -> String? = { _, _, _ -> null }
}

/** Zelfde aanroep als android.os.SystemClock, zodat de lampcode niet hoeft te weten waar hij draait. */
object SystemClock {
    fun elapsedRealtime() = Platform.clock()
}

/** Kleuren als int (ARGB), met dezelfde rekenwijze als android.graphics.Color (Skia), zodat tv en server dezelfde kleur sturen. */
object Color {
    const val BLACK = -0x1000000
    const val WHITE = -0x1

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun round(x: Float) = floor(x + 0.5f).toInt()

    /** SkRGBToHSV */
    fun RGBToHSV(r: Int, g: Int, b: Int, hsv: FloatArray) {
        val mn = min(r, min(g, b)); val mx = max(r, max(g, b)); val delta = mx - mn
        val v = mx / 255f
        if (delta == 0) { hsv[0] = 0f; hsv[1] = 0f; hsv[2] = v; return }
        val s = delta.toFloat() / mx
        var h = when (mx) {
            r -> (g - b).toFloat() / delta
            g -> 2f + (b - r).toFloat() / delta
            else -> 4f + (r - g).toFloat() / delta
        }
        h *= 60f
        if (h < 0) h += 360f
        hsv[0] = h; hsv[1] = s; hsv[2] = v
    }

    fun colorToHSV(c: Int, hsv: FloatArray) = RGBToHSV(red(c), green(c), blue(c), hsv)

    /** SkHSVToColor */
    fun HSVToColor(hsv: FloatArray): Int {
        val s = hsv[1].coerceIn(0f, 1f); val v = hsv[2].coerceIn(0f, 1f)
        val vb = round(v * 255)
        if (abs(s) <= 1f / 4096) return rgb(vb, vb, vb)
        val hx = if (hsv[0] < 0 || hsv[0] >= 360f) 0f else hsv[0] / 60
        val w = floor(hx)
        val f = hx - w
        val p = round((1 - s) * v * 255)
        val q = round((1 - s * f) * v * 255)
        val t = round((1 - s * (1 - f)) * v * 255)
        return when (w.toInt()) {
            0 -> rgb(vb, t, p)
            1 -> rgb(q, vb, p)
            2 -> rgb(p, vb, t)
            3 -> rgb(p, q, vb)
            4 -> rgb(t, p, vb)
            else -> rgb(vb, p, q)
        }
    }
}

object Net {
    val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Eigen adres in het thuisnetwerk (eerst een privé-adres, anders het eerste IPv4-adres). */
    fun localIp(): String? {
        try {
            var fallback: String? = null
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return null) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) {
                    val ip = a.hostAddress ?: continue
                    if (a.isSiteLocalAddress) return ip
                    if (fallback == null) fallback = ip
                }
            }
            return fallback
        } catch (_: Exception) { return null }
    }
}
