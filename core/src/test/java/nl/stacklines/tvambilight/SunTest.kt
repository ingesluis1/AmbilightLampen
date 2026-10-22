package nl.stacklines.tvambilight

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SunTest {
    private val ams = ZoneId.of("Europe/Amsterdam")

    private fun near(ms: Long, expected: ZonedDateTime, minutes: Long = 5) {
        val d = Math.abs(ms - expected.toInstant().toEpochMilli()) / 60_000
        assertTrue("verschil $d minuten met $expected", d <= minutes)
    }

    @Test
    fun amsterdamOktober() {
        // Amsterdam 17-10-2026: op ongeveer 08:13 op, 18:42 onder (zomertijd)
        val (rise, set) = Sun.times(LocalDate.of(2026, 10, 17), 52.37, 4.90)!!
        near(rise, ZonedDateTime.of(2026, 10, 17, 8, 13, 0, 0, ams))
        near(set, ZonedDateTime.of(2026, 10, 17, 18, 42, 0, 0, ams))
    }

    @Test
    fun amsterdamZomerEnWinter() {
        // 21 juni: ongeveer 05:18 op en 22:06 onder; 21 december: 08:48 op en 16:29 onder
        val (r1, s1) = Sun.times(LocalDate.of(2026, 6, 21), 52.37, 4.90)!!
        near(r1, ZonedDateTime.of(2026, 6, 21, 5, 18, 0, 0, ams))
        near(s1, ZonedDateTime.of(2026, 6, 21, 22, 6, 0, 0, ams))
        val (r2, s2) = Sun.times(LocalDate.of(2026, 12, 21), 52.37, 4.90)!!
        near(r2, ZonedDateTime.of(2026, 12, 21, 8, 48, 0, 0, ams))
        near(s2, ZonedDateTime.of(2026, 12, 21, 16, 29, 0, 0, ams))
    }
}
