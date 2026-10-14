package nl.stacklines.tvambilight

import android.os.SystemClock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lokaal Tuya-protocol.
 * 3.1/3.3: AES-ECB met de local key, frames 0x55AA met CRC32.
 * 3.4: eerst een sessiesleutel afspreken, daarna AES-ECB met die sleutel, frames 0x55AA met HMAC-SHA256.
 * 3.5: ook een sessiesleutel, frames 0x6699 met AES-GCM.
 */
object TuyaCodec {
    const val SESS_KEY_NEG_START = 3
    const val SESS_KEY_NEG_RESP = 4
    const val SESS_KEY_NEG_FINISH = 5
    const val CONTROL = 7
    const val STATUS = 8
    const val HEART_BEAT = 9
    const val DP_QUERY = 10
    const val CONTROL_NEW = 13
    const val DP_QUERY_NEW = 16

    private const val P55 = 0x000055AA
    private const val S55 = 0x0000AA55
    private const val P66 = 0x00006699
    private const val S66 = 0x00009966
    private val rnd = SecureRandom()

    fun aes(key: ByteArray, mode: Int, data: ByteArray, pad: Boolean = true): ByteArray {
        val c = Cipher.getInstance(if (pad) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    fun gcm(key: ByteArray, mode: Int, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** Frame voor 3.1/3.3: payload is al versleuteld. */
    fun frameCrc(seq: Int, cmd: Int, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(16 + payload.size + 8)
        bb.putInt(P55); bb.putInt(seq); bb.putInt(cmd); bb.putInt(payload.size + 8); bb.put(payload)
        val crc = CRC32(); crc.update(bb.array(), 0, 16 + payload.size)
        bb.putInt(crc.value.toInt()); bb.putInt(S55)
        return bb.array()
    }

    /** Frame voor 3.4: payload wordt hier versleuteld en ondertekend. */
    fun frameHmac(key: ByteArray, seq: Int, cmd: Int, plain: ByteArray): ByteArray {
        val enc = aes(key, Cipher.ENCRYPT_MODE, plain)
        val bb = ByteBuffer.allocate(16 + enc.size + 32 + 4)
        bb.putInt(P55); bb.putInt(seq); bb.putInt(cmd); bb.putInt(enc.size + 36); bb.put(enc)
        bb.put(hmac(key, bb.array().copyOfRange(0, 16 + enc.size))); bb.putInt(S55)
        return bb.array()
    }

    /** Frame voor 3.5: AES-GCM met de kop als extra geverifieerde data. */
    fun frameGcm(key: ByteArray, seq: Int, cmd: Int, plain: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(18).putInt(P66).putShort(0).putInt(seq).putInt(cmd).putInt(12 + plain.size + 16).array()
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val ct = gcm(key, Cipher.ENCRYPT_MODE, iv, plain, header.copyOfRange(4, 18))
        return header + iv + ct + ByteBuffer.allocate(4).putInt(S66).array()
    }

    class Frame(val cmd: Int, val plain: ByteArray)

    /** Leest één frame en ontsleutelt het (met de sleutel die nu geldt). */
    fun readFrame(inp: DataInputStream, version: String, key: ByteArray): Frame {
        var prefix = inp.readInt()
        while (prefix != P55 && prefix != P66) prefix = (prefix shl 8) or inp.readUnsignedByte()
        if (prefix == P66) {
            val unknown = inp.readUnsignedShort()
            val seq = inp.readInt(); val cmd = inp.readInt(); val len = inp.readInt()
            if (len < 28 || len > 8192) throw IOException("ongeldige lengte $len")
            val rest = ByteArray(len); inp.readFully(rest); inp.readInt() // suffix
            val header = ByteBuffer.allocate(18).putInt(P66).putShort(unknown.toShort()).putInt(seq).putInt(cmd).putInt(len).array()
            val plain = gcm(key, Cipher.DECRYPT_MODE, rest.copyOfRange(0, 12), rest.copyOfRange(12, len), header.copyOfRange(4, 18))
            return Frame(cmd, stripHeader(plain))
        }
        inp.readInt() // volgnummer
        val cmd = inp.readInt(); val len = inp.readInt()
        if (len < 8 || len > 8192) throw IOException("ongeldige lengte $len")
        val rest = ByteArray(len); inp.readFully(rest)
        if (version == "3.4") {
            val body = rest.copyOfRange(0, len - 36)
            for (off in intArrayOf(4, 0)) {
                if (body.size - off <= 0 || (body.size - off) % 16 != 0) continue
                try { return Frame(cmd, stripHeader(aes(key, Cipher.DECRYPT_MODE, body.copyOfRange(off, body.size)))) } catch (_: Exception) { }
            }
            return Frame(cmd, ByteArray(0))
        }
        return Frame(cmd, decodeLegacy(key, rest.copyOfRange(0, len - 8)) ?: ByteArray(0))
    }

    /** Haalt een returncode van 4 bytes en/of een "3.x"-kop van 15 bytes weg. */
    private fun stripHeader(p: ByteArray): ByteArray {
        var d = p
        if (d.size >= 4 && d[0] == 0.toByte() && d[1] == 0.toByte() && d[2] == 0.toByte() && d.size > 4 && d[4] != 0.toByte()) d = d.copyOfRange(4, d.size)
        if (d.size >= 15 && d[0] == '3'.code.toByte() && d[1] == '.'.code.toByte()) d = d.copyOfRange(15, d.size)
        return d
    }

    /** 3.1/3.3: met en zonder returncode en "3.3"-kop proberen. */
    fun decodeLegacy(key: ByteArray, p: ByteArray): ByteArray? {
        for (off in intArrayOf(4, 0)) {
            if (p.size <= off) continue
            var start = off
            if (p.size >= start + 15 && p[start] == '3'.code.toByte() && p[start + 1] == '.'.code.toByte()) start += 15
            val body = p.copyOfRange(start, p.size)
            if (body.isEmpty() || body.size % 16 != 0) continue
            try {
                val d = aes(key, Cipher.DECRYPT_MODE, body)
                if (d.isNotEmpty() && d[0] == '{'.code.toByte()) return d
            } catch (_: Exception) { }
        }
        val i = p.indexOf('{'.code.toByte())
        return if (i >= 0) p.copyOfRange(i, p.size) else null
    }

    /** Tekst-versie (voor Discovery en oude aanroepen). */
    fun decode(key: ByteArray, p: ByteArray): String? = decodeLegacy(key, p)?.toString(Charsets.UTF_8)

    fun jsonOf(b: ByteArray): JSONObject? {
        val s = b.toString(Charsets.UTF_8)
        val i = s.indexOf('{'); val j = s.lastIndexOf('}')
        if (i < 0 || j <= i) return null
        return try { JSONObject(s.substring(i, j + 1)) } catch (_: Exception) { null }
    }

    /** Haalt het getal van de lamp uit het antwoord op stap 1 (met of zonder returncode), gecontroleerd met de HMAC. */
    fun remoteNonce(plain: ByteArray, localKey: ByteArray, localNonce: ByteArray): ByteArray? {
        val expect = hmac(localKey, localNonce)
        for (off in intArrayOf(0, 4)) {
            if (plain.size >= off + 48 && plain.copyOfRange(off + 16, off + 48).contentEquals(expect)) return plain.copyOfRange(off, off + 16)
        }
        return null
    }

    /** Sessiesleutel uit beide getallen: 3.4 met AES-ECB, 3.5 met AES-GCM (iv = eerste 12 bytes van ons getal). */
    fun sessionKey(version: String, localKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
        val x = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        return if (version == "3.4") aes(localKey, Cipher.ENCRYPT_MODE, x, pad = false)
        else gcm(localKey, Cipher.ENCRYPT_MODE, localNonce.copyOfRange(0, 12), x, null).copyOfRange(0, 16)
    }

    /** De dps uit een statusbericht, ook als ze in "data" zitten (3.4/3.5). */
    fun dpsOf(j: JSONObject): JSONObject? = j.optJSONObject("dps") ?: j.optJSONObject("data")?.optJSONObject("dps")
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

    private val localKey = lamp.key.toByteArray(Charsets.UTF_8)
    private val ver = lamp.version
    private val modern = ver == "3.4" || ver == "3.5"
    private var key = localKey
    private val ip = lamp.ip
    private val pending = LinkedHashMap<String, Any>()
    /** Lagen die nog verstuurd moeten worden (segmentnummer naar h, s, v); de lamp neemt één laag per bericht. */
    private val layerPending = LinkedHashMap<Int, IntArray>()
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var queryWanted = false
    @Volatile private var statusResult: JSONObject? = null
    /** Oudere lampen/ledstrips (colour_data zonder _v2) willen de kleur als rrggbb + hhhh + ss + vv. */
    @Volatile private var colourV1 = false
    private var askedFormat = false
    /** Opdrachten die nog niet bevestigd zijn; zo krijgt elke lamp het tempo dat hij aankan. */
    @Volatile private var inflight = 0
    @Volatile private var lastRx = 0L
    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var inp: DataInputStream? = null
    private var seq = 1

    private val thread = Thread(::loop, "lamp-${lamp.id}").apply { isDaemon = true; start() }

    fun send(dps: Map<String, Any>) {
        synchronized(lock) { pending.putAll(dps); lock.notifyAll() }
    }

    /** Eén laag een kleur geven (h 0..360, s en v 0..1000). Alleen de nieuwste kleur per laag wordt verstuurd. */
    fun sendLayer(segment: Int, h: Int, s: Int, v: Int, priority: Int = 0) {
        synchronized(lock) {
            val old = layerPending[segment]
            layerPending[segment] = intArrayOf(h, s, v, maxOf(priority, old?.getOrNull(3) ?: 0)); lock.notifyAll()
        }
    }

    /** Ledstrip: hele lamp één kleur via het schilderbericht (functie 24 laat deze lampen traag overvloeien). */
    fun sendAllSegments(h: Int, s: Int, v: Int) {
        synchronized(lock) { layerPending.clear(); layerPending[0] = intArrayOf(h, s, v, 0); lock.notifyAll() }
    }

    /** Scènewissel bij een lamp met lagen: eerst één kleur voor de hele lamp (één bericht), oude laag-opdrachten vervallen. */
    fun sendWhole(h: Int, s: Int, v: Int) {
        synchronized(lock) { layerPending.clear(); pending["24"] = hsvHex(h, s, v); lock.notifyAll() }
    }

    /** Zet een kleur in het formaat dat deze lamp verwacht (v2: hhhhssssvvvv met 0..1000, v1: rrggbbhhhhssvv). */
    private fun adapt(dps: Map<String, Any>): Map<String, Any> {
        val c = dps["24"] as? String ?: return dps
        if (!colourV1 || c.length != 12) return dps
        val h = c.substring(0, 4).toInt(16); val s = c.substring(4, 8).toInt(16); val v = c.substring(8, 12).toInt(16)
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s / 1000f, v / 1000f))
        val v1 = "%02x%02x%02x%04x%02x%02x".format((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff,
            h, (s * 255 / 1000).coerceIn(0, 255), (v * 255 / 1000).coerceIn(0, 255))
        return LinkedHashMap(dps).apply { put("24", v1) }
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

    /** Rood, groen, blauw, wit; daarna terug naar de oude stand. */
    fun test() {
        Thread {
            val before = queryStatus()
            for (h in listOf(0, 120, 240)) {
                send(mapOf("20" to true, "21" to "colour", "24" to hsvHex(h, 1000, 1000)))
                shownColor = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f))
                Thread.sleep(900)
            }
            send(mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 500))
            shownColor = 0xFFFFE2A8.toInt()
            Thread.sleep(900)
            restore(before)
        }.start()
    }

    /** Zet een vaste kleur (#rrggbb). */
    fun setColor(rgb: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        send(mapOf("20" to true, "21" to "colour",
            "24" to hsvHex(hsv[0].toInt() % 360, (hsv[1] * 1000).toInt(), maxOf(10, (hsv[2] * 1000).toInt()))))
        shownColor = rgb or 0xFF000000.toInt()
    }

    fun restore(st: JSONObject?) {
        val m = LinkedHashMap<String, Any>()
        if (st != null) for (k in listOf("20", "21", "22", "23", "24")) if (st.has(k)) m[k] = st.get(k)
        send(if (m.isNotEmpty()) m else mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 500))
        shownColor = 0xFF555555.toInt()
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
                // Eén keer de stand opvragen om het kleurformaat te herkennen
                if (!askedFormat) { askedFormat = true; queryWanted = true }
                if (queryWanted) {
                    queryWanted = false
                    if (modern) write(TuyaCodec.DP_QUERY_NEW, "{}", header = false)
                    else write(TuyaCodec.DP_QUERY, JSONObject().put("gwId", lamp.id).put("devId", lamp.id).put("uid", lamp.id)
                        .put("t", (System.currentTimeMillis() / 1000).toString()).toString(), header = false)
                    continue
                }
                var isLayer = false
                val batch: Map<String, Any>? = synchronized(lock) {
                    if (pending.isNotEmpty()) LinkedHashMap(pending).also { pending.clear() }
                    else layerPending.entries.maxByOrNull { it.value.getOrElse(3) { 0 } }?.let { e ->
                        // Lagen met (bijna) dezelfde kleur in één bericht: scheelt berichten bij egaal beeld en scènewissels
                        val keys = layerPending.filter { (_, c) -> similar(c, e.value) }.keys.toList()
                        keys.forEach { layerPending.remove(it) }; isLayer = true
                        // Sleutel 0 = alle segmenten (niet-bestaande segmenten negeert de lamp)
                        val segs = if (0 in keys) (1..10).toList() else keys
                        mapOf<String, Any>("61" to layerValue(segs, e.value))
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (batch != null) {
                    // Wachten tot de lamp eerdere opdrachten heeft bevestigd (anders loopt de wachtrij vol)
                    val until = SystemClock.elapsedRealtime() + 400
                    val cap = if (isLayer) MAX_INFLIGHT_LAYERS else MAX_INFLIGHT
                    while (inflight >= cap && SystemClock.elapsedRealtime() < until) { drain(); Thread.sleep(5) }
                    if (inflight >= cap && SystemClock.elapsedRealtime() - lastRx > 4000) throw IOException("lamp reageert niet meer")
                    if (inflight >= cap) inflight = cap - 1   // antwoorden kunnen wegvallen; niet eeuwig blijven wachten
                    // Laag-berichten zijn klein: die mogen sneller achter elkaar (getest op de Wall Washer)
                    val gap = when { !Store.settingsForId(lamp.id).fade -> MIN_GAP_MS; isLayer -> LAYER_GAP_MS; else -> FADE_GAP_MS }
                    val wait = gap - (now - lastSend)
                    if (wait > 0) Thread.sleep(wait)
                    sendControl(adapt(batch)); inflight++
                    lastSend = SystemClock.elapsedRealtime(); lastBeat = lastSend
                } else if (now - lastBeat > 8000) {
                    write(TuyaCodec.HEART_BEAT, "{}", header = false)
                    lastBeat = now
                } else {
                    synchronized(lock) { if (pending.isEmpty() && layerPending.isEmpty() && !queryWanted) lock.wait(40) }
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
        s.sendBufferSize = 4096   // geen grote achterstand met oude kleuren opbouwen
        s.connect(InetSocketAddress(ip, 6668), 2500)
        s.soTimeout = 1500
        inflight = 0; lastRx = SystemClock.elapsedRealtime()
        sock = s; out = s.getOutputStream(); inp = DataInputStream(s.getInputStream().buffered())
        key = localKey
        if (modern) negotiate()
        online = true; lastError = null
    }

    /** 3.4/3.5: sessiesleutel afspreken met twee willekeurige getallen (van ons en van de lamp). */
    private fun negotiate() {
        val localNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        writeRaw(TuyaCodec.SESS_KEY_NEG_START, localNonce)
        var remote: ByteArray? = null
        val end = SystemClock.elapsedRealtime() + 3000
        while (remote == null && SystemClock.elapsedRealtime() < end) {
            val f = TuyaCodec.readFrame(inp!!, ver, localKey)
            if (f.cmd == TuyaCodec.SESS_KEY_NEG_RESP) remote = TuyaCodec.remoteNonce(f.plain, localKey, localNonce) ?: throw IOException("sleutel van de lamp klopt niet")
        }
        val r = remote ?: throw IOException("lamp gaf geen sessiesleutel")
        writeRaw(TuyaCodec.SESS_KEY_NEG_FINISH, TuyaCodec.hmac(localKey, r))
        key = TuyaCodec.sessionKey(ver, localKey, localNonce, r)
    }

    private fun drain() {
        val i = inp ?: return
        val raw = sock?.getInputStream() ?: return
        while (raw.available() > 0 || i.available() >= 16) {
            val f = TuyaCodec.readFrame(i, ver, key)
            lastRx = SystemClock.elapsedRealtime()
            if (f.cmd == TuyaCodec.CONTROL || f.cmd == TuyaCodec.CONTROL_NEW || f.cmd == TuyaCodec.STATUS) inflight = (inflight - 1).coerceAtLeast(0)
            if (f.cmd == TuyaCodec.DP_QUERY || f.cmd == TuyaCodec.DP_QUERY_NEW || f.cmd == TuyaCodec.STATUS) {
                val dps = TuyaCodec.jsonOf(f.plain)?.let { TuyaCodec.dpsOf(it) } ?: continue
                (dps.opt("24") as? String)?.let { colourV1 = it.length == 14 }
                if (dps.has("20") && dps.has("21")) statusResult = dps
            }
        }
    }

    private fun sendControl(dps: Map<String, Any>) {
        val t = System.currentTimeMillis() / 1000
        if (modern) {
            write(TuyaCodec.CONTROL_NEW, JSONObject().put("protocol", 5).put("t", t)
                .put("data", JSONObject().put("dps", JSONObject(dps))).toString(), header = true)
        } else {
            write(TuyaCodec.CONTROL, JSONObject().put("devId", lamp.id).put("uid", lamp.id).put("t", t.toString())
                .put("dps", JSONObject(dps)).toString(), header = ver == "3.3")
        }
    }

    /** Versleutelt en verstuurt een JSON-bericht; header = de "3.x"-kop van 15 bytes ervoor. */
    private fun write(cmd: Int, json: String, header: Boolean) {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = if (header) ver.toByteArray() + ByteArray(12) else ByteArray(0)
        when (ver) {
            "3.4" -> writeBytes(TuyaCodec.frameHmac(key, seq++, cmd, head + body))
            "3.5" -> writeBytes(TuyaCodec.frameGcm(key, seq++, cmd, head + body))
            else -> {
                // 3.1/3.3: kop staat buiten de versleuteling
                writeBytes(TuyaCodec.frameCrc(seq++, cmd, head + TuyaCodec.aes(key, Cipher.ENCRYPT_MODE, body)))
            }
        }
    }

    /** Ruwe bytes (sessiesleutel-stappen) met de local key. */
    private fun writeRaw(cmd: Int, payload: ByteArray) {
        writeBytes(if (ver == "3.5") TuyaCodec.frameGcm(localKey, seq++, cmd, payload) else TuyaCodec.frameHmac(localKey, seq++, cmd, payload))
    }

    private fun writeBytes(b: ByteArray) {
        val o = out ?: throw IOException("geen verbinding")
        o.write(b); o.flush()
    }

    private fun close() {
        try { sock?.close() } catch (_: Exception) { }
        sock = null; out = null; inp = null
    }

    private fun sleepQuiet(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    companion object {
        /** Functie 61 "schilderen": 00 02 00 14 01, dan h s v (elk 2 bytes), dan 0x80|aantal segmenten en de segmentnummers. */
        fun layerValue(segments: List<Int>, c: IntArray): String {
            val b = java.nio.ByteBuffer.allocate(12 + segments.size)
            b.put(byteArrayOf(0, 2, 0, 0x14, 1)); b.putShort(c[0].toShort()); b.putShort(c[1].toShort()); b.putShort(c[2].toShort())
            b.put((0x80 or segments.size).toByte()); segments.forEach { b.put(it.toByte()) }
            return android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP)
        }

        /** Minimale tijd tussen twee opdrachten; sneller verslikken de lampen zich. */
        const val MIN_GAP_MS = 70L
        const val LAYER_GAP_MS = 18L
        /** Met vloeiende overgangen: gewone lampen vaker bijwerken (ongeveer 25 keer per seconde). */
        const val FADE_GAP_MS = 40L
        /** Hoeveel opdrachten er tegelijk onbevestigd onderweg mogen zijn. */
        const val MAX_INFLIGHT = 2
        /** Lamp met lagen antwoordt trager (±155 ms) maar kan meer tegelijk aan. */
        const val MAX_INFLIGHT_LAYERS = 4

        /** Twee laagkleuren (h, s, v) die zo dicht bij elkaar liggen dat één bericht volstaat. */
        fun similar(a: IntArray, b: IntArray): Boolean {
            val dh = Math.abs(a[0] - b[0]).let { minOf(it, 360 - it) }
            return (dh < 8 || a[1] < 80 && b[1] < 80) && Math.abs(a[1] - b[1]) < 60 && Math.abs(a[2] - b[2]) < 50
        }

        fun hsvHex(h: Int, s: Int, v: Int) = "%04x%04x%04x".format(h, s, v)
    }
}
