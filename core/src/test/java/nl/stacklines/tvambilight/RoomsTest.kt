package nl.stacklines.tvambilight

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Kamers: een oude config wordt één woonkamer met de tv; lampen horen bij hun kamer. */
class RoomsTest {
    private fun load(config: JSONObject): File {
        val dir = Files.createTempDirectory("rooms").toFile()
        File(dir, "config.json").writeText(config.toString())
        Store.load(dir)
        return dir
    }

    private fun lamp(id: String, room: String? = null) = JSONObject().put("id", id).put("key", "0123456789abcdef").put("name", id)
        .put("x", 1.0).put("y", 1.0).apply { room?.let { put("room", it) } }

    @Test fun oldConfigBecomesLivingRoom() {
        val layout = JSONObject().put("w", 8).put("h", 4).put("tv", JSONObject().put("x", 4.0).put("y", 0.2).put("w", 1.4).put("dir", 90))
        load(JSONObject().put("layout", layout).put("lamps", JSONArray().put(lamp("a")).put(lamp("b"))))
        assertEquals(1, Store.rooms.size)
        assertEquals(Room.FIRST, Store.tvRoom.id)
        assertEquals(8, Store.layout.getInt("w"))
        assertTrue(Store.lampsSnapshot().all { it.room == Room.FIRST && it.synced })
    }

    @Test fun lampsOnlySyncInTvRoom() {
        val rooms = JSONArray()
            .put(JSONObject().put("id", "woonkamer").put("name", "Woonkamer").put("tv", true).put("layout", JSONObject().put("w", 8).put("h", 4)))
            .put(JSONObject().put("id", "keuken").put("name", "Keuken").put("tv", false).put("layout", JSONObject().put("w", 3).put("h", 3)))
        load(JSONObject().put("rooms", rooms).put("lamps", JSONArray().put(lamp("a", "woonkamer")).put(lamp("b", "keuken")).put(lamp("c", "weg"))))
        val l = Store.lampsSnapshot().associateBy { it.id }
        assertTrue(l.getValue("a").synced)
        assertFalse(l.getValue("b").synced)
        // Onbekende kamer met meer kamers: zonder kamer, en niet meer geplaatst (de plek hoorde bij die kamer)
        assertEquals("", l.getValue("c").room)
        assertFalse(l.getValue("c").placed)
        // Ook de oude "layout" (van de tv-kamer) gaat mee in de backup, voor oudere versies van de app
        val b = Store.backup(false).getJSONObject("config")
        assertEquals(2, b.getJSONArray("rooms").length())
        assertEquals(8, b.getJSONObject("layout").getInt("w"))
        // Blijft er één kamer over, dan komt een lamp zonder kamer daarin, maar nog niet geplaatst
        Store.rooms = Store.rooms.filter { it.id == "woonkamer" }; Store.normalizeRooms()
        assertEquals("woonkamer", l.getValue("b").room)
        assertFalse(l.getValue("b").synced)
        assertTrue(l.getValue("a").synced)
    }

    @Test fun exactlyOneTvRoom() {
        val rooms = JSONArray()
            .put(JSONObject().put("id", "x").put("name", "X").put("tv", false))
            .put(JSONObject().put("id", "y").put("name", "Y").put("tv", false))
        load(JSONObject().put("rooms", rooms))
        assertEquals(1, Store.rooms.count { it.tv })
        assertEquals("x", Store.tvRoom.id)
    }
}
