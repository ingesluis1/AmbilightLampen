package nl.stacklines.tvambilight

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.DataInputStream

/** Vergelijkt het Tuya 3.4/3.5-protocol met vectoren die tinytuya maakte (src/test/resources/tuya_vectors.json). */
class TuyaCodecTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("tuya_vectors.json")!!.readText())
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun str(k: String) = v.getString(k)
    private val local = str("local").toByteArray()
    private val key = str("key").toByteArray()

    @Test fun sessiesleutels() {
        assertArrayEquals(hex(str("sk34")), TuyaCodec.sessionKey("3.4", local, hex(str("ln")), hex(str("rn"))))
        assertArrayEquals(hex(str("sk35")), TuyaCodec.sessionKey("3.5", local, hex(str("ln")), hex(str("rn"))))
    }

    @Test fun frame34VanAppIsGelijkAanTinytuya() {
        val plain = "3.4".toByteArray() + ByteArray(12) + str("js").toByteArray()
        assertArrayEquals(hex(str("f34c")), TuyaCodec.frameHmac(key, 5, 13, plain))
    }

    @Test fun frame34VanLampLezen() {
        val f = TuyaCodec.readFrame(DataInputStream(hex(str("f34d")).inputStream()), "3.4", key)
        assertEquals(8, f.cmd)
        assertEquals(str("st"), String(f.plain))
        assertEquals("colour", TuyaCodec.dpsOf(TuyaCodec.jsonOf(f.plain)!!)!!.getString("21"))
    }

    @Test fun frame35VanLampLezen() {
        val f = TuyaCodec.readFrame(DataInputStream(hex(str("f35d")).inputStream()), "3.5", key)
        assertEquals(8, f.cmd)
        assertEquals(str("st"), String(f.plain))
    }

    @Test fun frame35VanAppHeeftZelfdeKopEnIsLeesbaar() {
        val ours = TuyaCodec.frameGcm(key, 5, 13, hex(str("pl")))
        val theirs = hex(str("f35c"))
        assertArrayEquals(theirs.copyOfRange(0, 18), ours.copyOfRange(0, 18))   // kop: prefix, 0, seq, cmd, lengte
        assertEquals(theirs.size, ours.size)
        // tinytuya-frame (vaste iv) en ons frame (willekeurige iv) moeten allebei te ontsleutelen zijn
        val t = TuyaCodec.readFrame(DataInputStream(theirs.inputStream()), "3.5", key)
        val o = TuyaCodec.readFrame(DataInputStream(ours.inputStream()), "3.5", key)
        assertEquals(str("js"), String(t.plain)); assertEquals(str("js"), String(o.plain))
    }

    @Test fun sessieAntwoordVanLamp() {
        for ((ver, k) in listOf("3.4" to "resp34", "3.5" to "resp35")) {
            val f = TuyaCodec.readFrame(DataInputStream(hex(str(k)).inputStream()), ver, local)
            assertEquals(4, f.cmd)
            val r = TuyaCodec.remoteNonce(f.plain, local, hex(str("ln")))
            assertNotNull("$ver remote nonce", r)
            assertArrayEquals(hex(str("rn")), r)
        }
    }
}
