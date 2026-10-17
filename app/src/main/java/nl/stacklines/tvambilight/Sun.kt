package nl.stacklines.tvambilight

import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** Zonsopkomst en -ondergang uitrekenen (zonsopkomstvergelijking, nauwkeurig op een paar minuten). */
object Sun {
    /** Tijden (ms sinds 1970, UTC) van zonsopkomst en zonsondergang op deze datum, of null bij poolnacht/middernachtzon. */
    fun times(date: LocalDate, lat: Double, lon: Double): Pair<Long, Long>? {
        val rad = Math.PI / 180
        val jdNoon = date.toEpochDay() + 2440587.5 + 0.5
        val n = Math.round(jdNoon - 2451545.0).toDouble()
        val jStar = n - lon / 360
        val m = (357.5291 + 0.98560028 * jStar) % 360
        val c = 1.9148 * sin(m * rad) + 0.02 * sin(2 * m * rad) + 0.0003 * sin(3 * m * rad)
        val lambda = (m + c + 180 + 102.9372) % 360
        val transit = 2451545.0 + jStar + 0.0053 * sin(m * rad) - 0.0069 * sin(2 * lambda * rad)
        val decl = asin(sin(lambda * rad) * sin(23.4397 * rad))
        val cosW = (sin(-0.833 * rad) - sin(lat * rad) * sin(decl)) / (cos(lat * rad) * cos(decl))
        if (cosW < -1 || cosW > 1) return null
        val w = acos(cosW) / rad
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000).roundToLong()
        return ms(transit - w / 360) to ms(transit + w / 360)
    }
}
