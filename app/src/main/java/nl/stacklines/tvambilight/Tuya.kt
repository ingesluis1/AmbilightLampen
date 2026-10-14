package nl.stacklines.tvambilight

import android.os.SystemClock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Lokaal Tuya-protocol (versie 3.1/3.3): AES-ECB met de local key, frames met CRC32. */
object TuyaCodec {
    const val CONTROL = 7
    const val STATUS = 8
    const val HEART_BEAT = 9
    const val DP_QUERY = 10

    fun aes(key: ByteArray, mode: Int, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    fun frame(seq: Int, cmd: Int, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(16 + payload.size + 8)
        bb.putInt(0x000055AA); bb.putInt(seq); bb.putInt(cmd); bb.putInt(payload.size + 8); bb.put(payload)
        val crc = CRC32(); crc.update(bb.array(), 0, 16 + payload.size)
        bb.putInt(crc.value.toInt()); bb.putInt(0x0000AA55)
        return bb.array()
    }

    /** Leest één frame; geeft (commando, payload zonder crc/suffix). */
    fun readFrame(inp: DataInputStream): Pair<Int, ByteArray> {
        var prefix = inp.readInt()
        while (prefix != 0x000055AA) prefix = (prefix shl 8) or inp.readUnsignedByte()
        inp.readInt() // volgnummer
        val cmd = inp.readInt()
        val len = inp.readInt()
        if (len < 8 || len > 8192) throw java.io.IOException("ongeldige lengte $len")
        val rest = ByteArray(len); inp.readFully(rest)
        return cmd to rest.copyOfRange(0, len - 8)
    }

    /** Ontsleutelt een payload; probeert met en zonder 4-byte returncode en "3.3"-kop. */
    fun decode(key: ByteArray, p: ByteArray): String? {
        for (off in intArrayOf(4, 0)) {
            if (p.size <= off) continue
            var start = off
            if (p.size >= start + 15 && p[start] == '3'.code.toByte() && p[start + 1] == '.'.code.toByte()) start += 15
            val body = p.copyOfRange(start, p.size)
            if (body.isEmpty() || body.size % 16 != 0) continue
            try {
                val s = String(aes(key, Cipher.DECRYPT_MODE, body), Charsets.UTF_8)
                if (s.startsWith("{")) return s
            } catch (_: Exception) { }
        }
        // Versie 3.1 stuurt status soms onversleuteld
        val plain = String(p, Charsets.UTF_8)
        val i = plain.indexOf('{')
        return if (i >= 0) plain.substring(i) else null
    }
}

/**
 * Houdt een vaste verbinding met één lamp open in een eigen thread.
 * send() voegt waarden samen; alleen de laatste stand wordt verstuurd (geen wachtrij die achterloopt).
 */
class TuyaLamp(val lamp: Lamp) {
    @Volatile var online = false
        private set
    @Volatile var lastError: String? = null
        private set
    /** Laatst verstuurde kleur, voor de weergave op het scherm en de telefoon. */
    @Volatile var shownColor: Int = 0xFF555555.toInt()

    private val key = lamp.key.toByteArray(Charsets.UTF_8)
    private val ip = lamp.ip
    private val pending = LinkedHashMap<String, Any>()
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var queryWanted = false
    @Volatile private var statusResult: JSONObject? = null
    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var inp: DataInputStream? = null
    private var seq = 1

    private val thread = Thread(::loop, "lamp-${lamp.id}").apply { isDaemon = true; start() }

    fun send(dps: Map<String, Any>) {
        synchronized(lock) { pending.putAll(dps); lock.notifyAll() }
    }

    /** Vraagt de huidige stand op (aan/uit, modus, kleur), om die na afloop terug te zetten. */
    fun queryStatus(timeoutMs: Long = 2500): JSONObject? {
        statusResult = null
        queryWanted = true
        synchronized(lock) { lock.notifyAll() }
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            statusResult?.let { return it }
            Thread.sleep(50)
        }
        queryWanted = false
        return null
    }

    fun blink() {
        Thread {
            repeat(3) {
                send(mapOf("20" to false)); Thread.sleep(450)
                send(mapOf("20" to true)); Thread.sleep(450)
            }
        }.start()
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loop() {
        var lastSend = 0L
        var lastBeat = 0L
        while (running) {
            try {
                if (sock == null) {
                    if (ip.isBlank() || !lamp.supported) { online = false; sleepQuiet(2000); continue }
                    connect()
                    lastBeat = SystemClock.elapsedRealtime()
                }
                drain()
                if (queryWanted) {
                    queryWanted = false
                    val j = JSONObject().put("gwId", lamp.id).put("devId", lamp.id).put("uid", lamp.id)
                        .put("t", (System.currentTimeMillis() / 1000).toString())
                    write(TuyaCodec.DP_QUERY, enc(j.toString()))
                    continue
                }
                val batch: Map<String, Any>? = synchronized(lock) {
                    if (pending.isEmpty()) null else LinkedHashMap(pending).also { pending.clear() }
                }
                val now = SystemClock.elapsedRealtime()
                if (batch != null) {
                    val wait = MIN_GAP_MS - (now - lastSend)
                    if (wait > 0) Thread.sleep(wait)
                    write(TuyaCodec.CONTROL, control(batch))
                    lastSend = SystemClock.elapsedRealtime(); lastBeat = lastSend
                } else if (now - lastBeat > 8000) {
                    write(TuyaCodec.HEART_BEAT, enc("{}"))
                    lastBeat = now
                } else {
                    synchronized(lock) { if (pending.isEmpty() && !queryWanted) lock.wait(40) }
                }
            } catch (e: Exception) {
                lastError = e.message
                online = false
                close()
                sleepQuiet(2000)
            }
        }
        close()
    }

    private fun connect() {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(ip, 6668), 2500)
        s.soTimeout = 1500
        sock = s; out = s.getOutputStream(); inp = DataInputStream(s.getInputStream().buffered())
        online = true; lastError = null
    }

    private fun drain() {
        val i = inp ?: return
        val raw = sock?.getInputStream() ?: return
        while (raw.available() > 0 || i.available() >= 16) {
            val (cmd, p) = TuyaCodec.readFrame(i)
            if (cmd == TuyaCodec.DP_QUERY || cmd == TuyaCodec.STATUS) {
                val s = TuyaCodec.decode(key, p) ?: continue
                try {
                    JSONObject(s).optJSONObject("dps")?.let { if (it.has("20") && it.has("21")) statusResult = it }
                } catch (_: Exception) { }
            }
        }
    }

    private fun write(cmd: Int, payload: ByteArray) {
        val o = out ?: throw java.io.IOException("geen verbinding")
        o.write(TuyaCodec.frame(seq++, cmd, payload)); o.flush()
    }

    private fun enc(s: String) = TuyaCodec.aes(key, Cipher.ENCRYPT_MODE, s.toByteArray(Charsets.UTF_8))

    private fun control(dps: Map<String, Any>): ByteArray {
        val j = JSONObject().put("devId", lamp.id).put("uid", lamp.id)
            .put("t", (System.currentTimeMillis() / 1000).toString())
            .put("dps", JSONObject(dps))
        val body = enc(j.toString())
        return if (lamp.version == "3.3") "3.3".toByteArray() + ByteArray(12) + body else body
    }

    private fun close() {
        try { sock?.close() } catch (_: Exception) { }
        sock = null; out = null; inp = null
    }

    private fun sleepQuiet(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    companion object {
        /** Minimale tijd tussen twee opdrachten; sneller verslikken de lampen zich. */
        const val MIN_GAP_MS = 70L

        fun hsvHex(h: Int, s: Int, v: Int) = "%04x%04x%04x".format(h, s, v)
    }
}
