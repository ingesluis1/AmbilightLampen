package nl.stacklines.tvambilight

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zacht wisselen (eindkleuren) en profielen per app. */
class SwitchTest {
    @Test fun fadeTargetFromLampState() {
        assertArrayEquals(Fade.OFF, Fade.target(JSONObject().put("20", false)))
        assertArrayEquals(intArrayOf(120, 500, 800), Fade.target(JSONObject().put("20", true).put("21", "colour").put("24", TuyaLamp.hsvHex(120, 500, 800))))
        // Witmodus en onbekend: warm wit, niet uit
        assertTrue(Fade.target(JSONObject().put("20", true).put("21", "white").put("22", 1000).put("23", 500))[2] > 500)
        assertTrue(Fade.target(null)[2] > 500)
    }

    @Test fun profilesNudgeSettings() {
        val b = Settings()
        val film = Profiles.apply(b, "film"); val game = Profiles.apply(b, "game"); val calm = Profiles.apply(b, "calm")
        assertTrue(film.smooth < b.smooth)
        assertTrue(game.smooth > b.smooth); assertEquals(25, game.hz); assertFalse(game.fade)
        assertTrue(calm.smooth < film.smooth); assertTrue(calm.brightness < b.brightness); assertFalse(calm.cuts)
        // Gewoon: alles gelijk, en het origineel blijft onaangeroerd
        assertEquals(b.toJson().toString(), Profiles.apply(b, "").toJson().toString())
        assertEquals(Settings().toJson().toString(), b.toJson().toString())
    }
}
