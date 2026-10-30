package nl.stacklines.tvambilight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** De wittinten van de telefoon (index.html, whiteHex) gaan met de witte leds; gewone kleuren niet. */
class WhiteTest {
    private fun hsvOf(hex: String): IntArray {
        val c = hex.removePrefix("#").toInt(16); val hsv = FloatArray(3)
        Color.RGBToHSV((c shr 16) and 255, (c shr 8) and 255, c and 255, hsv)
        return intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), 1000)
    }
    // Zelfde som whiteHex in index.html
    private fun whiteHex(k: Int): String {
        val t = k / 1000.0; val c = doubleArrayOf(255 - 35 * t, 190 + 50 * t, 110 + 145 * t); val m = c.max()
        return "#" + c.joinToString("") { "%02x".format((it * 255 / m).roundToInt()) }
    }

    @Test fun phoneWhitesUseWhiteLeds() {
        for (k in listOf(0, 200, 400, 600, 1000)) {
            val t = Scene.whiteTempOf(hsvOf(whiteHex(k)))
            assertTrue("$k gaf $t", t != null && abs(t - k) <= 30)
        }
        // Koel wit ligt bijna op grijs: alleen dat het wit is en koel
        assertTrue((Scene.whiteTempOf(hsvOf(whiteHex(800))) ?: 0) >= 700)
        assertTrue((Scene.whiteTempOf(hsvOf("#ffffff")) ?: 0) >= 700)
    }

    @Test fun coloursStayColour() {
        for (c in listOf("#ff2a1a", "#ff7a00", "#ffc400", "#00a0ff", "#ff5a8a")) assertNull(c, Scene.whiteTempOf(hsvOf(c)))
        assertEquals(null, Scene.whiteTempOf(intArrayOf(33, 700, 1000)))
        // Het oude warm-wit vakje (#ffd29a) in opgeslagen scènes wordt nu ook echt warm wit
        assertTrue((Scene.whiteTempOf(hsvOf("#ffd29a")) ?: 999) < 400)
    }
}
