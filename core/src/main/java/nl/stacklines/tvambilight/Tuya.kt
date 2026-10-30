package nl.stacklines.tvambilight

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
import kotlin.math.abs
import kotlin.math.roundToInt

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
            val plain = try { gcm(key, Cipher.DECRYPT_MODE, rest.copyOfRange(0, 12), rest.copyOfRange(12, len), header.copyOfRange(4, 18)) }
                        catch (_: Exception) { return Frame(cmd, ByteArray(0)) }   // niet te ontsleutelen: overslaan
            return Frame(cmd, stripHeader(plain))
        }
        inp.readInt() // volgnummer
        val cmd = inp.readInt(); val len = inp.readInt()
        if (len < 8 || len > 8192) throw IOException("ongeldige lengte $len")
        val rest = ByteArray(len); inp.readFully(rest)
        if (version == "3.4") {
            if (len < 36) return Frame(cmd, ByteArray(0))   // te kort voor inhoud + HMAC: overslaan
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
/** keepOpen = verbinding altijd openhouden, ook op de hub (airco: dan komen standen uit Smart Life meteen binnen). */
class TuyaLamp(val lamp: Lamp, private val keepOpen: Boolean = false) {
    @Volatile var online = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var reconnects = 0
        private set
    /** Laatste reden waarom de verbinding viel (blijft staan na opnieuw verbinden). */
    @Volatile var lastFail: String = ""
        private set
    /** Laatst verstuurde kleur, voor de weergave op het scherm en de telefoon. */
    @Volatile var shownColor: Int = 0xFF555555.toInt()
    /** Laatst bekende stand van de lamp (functies 20..24), uit wat we sturen en wat de lamp meldt. */
    private val known = HashMap<String, Any>()
    /** Staat de lamp aan? null = nog onbekend. */
    @Volatile var powered: Boolean? = null
        private set
    /** Alle functies (dps) zoals het apparaat ze laatst meldde of wij ze stuurden (airco), en wanneer dat was. */
    val allDps = java.util.concurrent.ConcurrentHashMap<String, Any>()
    @Volatile var dpsAt = 0L
        private set
    private fun noteAll(d: JSONObject) { d.keys().forEach { k -> d.opt(k)?.let { if (it != JSONObject.NULL) allDps[k] = it } }; dpsAt = SystemClock.elapsedRealtime() }
    /** Stand opnieuw opvragen zonder te wachten (het antwoord komt in allDps). */
    fun refresh() { queryWanted = true; synchronized(lock) { lock.notifyAll() } }

    fun noteState(d: Map<String, Any?>) {
        synchronized(known) { for (k in STATE_KEYS) d[k]?.let { known[k] = it } }
        powered = known["20"] as? Boolean
    }
    private fun noteState(d: JSONObject) = noteState(d.keys().asSequence().associateWith { d.opt(it) })

    /** Kleur die de lamp nu (buiten de sync) laat zien, of null als dat onbekend is of hij uit staat. */
    fun idleColor(): Int? {
        val st = synchronized(known) { HashMap(known) }
        if (st["20"] == false) return null
        return when (st["21"]) {
            "colour" -> (st["24"] as? String)?.let { c ->
                val (h, s, v) = when (c.length) {
                    12 -> parseHsvHex(c).let { Triple(it[0], it[1] / 1000f, it[2] / 1000f) }
                    14 -> Triple(c.substring(6, 10).toInt(16), c.substring(10, 12).toInt(16) / 255f, c.substring(12, 14).toInt(16) / 255f)
                    else -> return null
                }
                Color.HSVToColor(floatArrayOf(h.toFloat(), s.coerceIn(0f, 1f), 0.45f + 0.55f * v.coerceIn(0f, 1f)))
            }
            "white" -> Engine.whiteRgb((st["23"] as? Number)?.toInt() ?: 500, 1000)
            else -> null
        }
    }

    /** Waarmee deze verbinding gestart is; wijkt de lamp daarvan af, dan maakt de Engine een nieuwe verbinding. */
    val connIp = lamp.ip
    val connKey = lamp.key
    val connVersion = lamp.version
    private val localKey = lamp.key.toByteArray(Charsets.UTF_8)
    private val ver = lamp.version
    private val modern = ver == "3.4" || ver == "3.5"
    private val wiz = ver == Wiz.VERSION
    private val hue = ver == Hue.VERSION
    private val ikea = ver == Dirigera.VERSION
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

    /**
     * Opdracht naar de lamp. Een kleur (functie 24) krijgt eerst de kleurbalans van deze lamp; raw = een stand die de
     * lamp zelf meldde (terugzetten), die is al gecorrigeerd.
     */
    fun send(dps: Map<String, Any>, raw: Boolean = false) {
        val d = if (raw) dps else (dps["24"] as? String)?.takeIf { it.length == 12 && hasBalance() }?.let { c ->
            val (h, s, v) = parseHsvHex(c); val b = balanced(h, s, v)
            LinkedHashMap(dps).apply { put("24", hsvHex(b[0], b[1], b[2])) }
        } ?: dps
        synchronized(lock) { pending.putAll(d); lock.notifyAll() }
        noteState(d); d.forEach { (k, v) -> allDps[k] = v }
    }

    private fun hasBalance() = abs(lamp.balWarm) > 0.005 || abs(lamp.balTint) > 0.005

    /**
     * Kleurbalans: zoals witbalans op een tv dempen we per lamp het rood, groen of blauw een beetje, zodat lampen van
     * verschillende merken dezelfde kleur geven. Warmer = minder blauw, koeler = minder rood, rozer = minder groen,
     * groener = minder rood en blauw. h 0..360, s en v 0..1000.
     */
    fun balanced(h: Int, s: Int, v: Int): IntArray {
        if (!hasBalance()) return intArrayOf(h, s, v)
        val w = lamp.balWarm; val t = lamp.balTint
        val gr = (1 - 0.5 * maxOf(0.0, -w)) * (1 - 0.5 * maxOf(0.0, -t))
        val gg = 1 - 0.5 * maxOf(0.0, t)
        val gb = (1 - 0.5 * maxOf(0.0, w)) * (1 - 0.5 * maxOf(0.0, -t))
        val rgb = Color.HSVToColor(floatArrayOf(h.toFloat(), s / 1000f, 1f))
        val hsv = FloatArray(3)
        Color.RGBToHSV((Color.red(rgb) * gr).roundToInt(), (Color.green(rgb) * gg).roundToInt(),
            (Color.blue(rgb) * gb).roundToInt(), hsv)
        // De gedempte kleur is iets minder fel; die verhouding gaat mee in de helderheid
        return intArrayOf(hsv[0].roundToInt() % 360, (hsv[1] * 1000).roundToInt(), (v * hsv[2]).roundToInt().coerceIn(if (v > 0) 10 else 0, 1000))
    }

    /** Eén laag een kleur geven (h 0..360, s en v 0..1000). Alleen de nieuwste kleur per laag wordt verstuurd. */
    fun sendLayer(segment: Int, hIn: Int, sIn: Int, vIn: Int, priority: Int = 0) {
        val (h, s, v) = balanced(hIn, sIn, vIn).let { Triple(it[0], it[1], it[2]) }
        synchronized(lock) {
            val old = layerPending[segment]
            layerPending[segment] = intArrayOf(h, s, v, maxOf(priority, old?.getOrNull(3) ?: 0)); lock.notifyAll()
        }
    }

    /** Ledstrip: hele lamp één kleur via het schilderbericht (functie 24 laat deze lampen traag overvloeien). */
    fun sendAllSegments(h: Int, s: Int, v: Int) {
        val b = balanced(h, s, v)
        synchronized(lock) { layerPending.clear(); layerPending[0] = intArrayOf(b[0], b[1], b[2], 0); lock.notifyAll() }
    }

    /** Zet een kleur in het formaat dat deze lamp verwacht (v2: hhhhssssvvvv met 0..1000, v1: rrggbbhhhhssvv). */
    private fun adapt(dps: Map<String, Any>): Map<String, Any> {
        val c = dps["24"] as? String ?: return dps
        if (!colourV1 || c.length != 12) return dps
        val (h, s, v) = parseHsvHex(c)
        val rgb = Color.HSVToColor(floatArrayOf(h.toFloat(), s / 1000f, v / 1000f))
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
                shownColor = Color.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f))
                Thread.sleep(900)
            }
            send(WARM_WHITE)
            shownColor = 0xFFFFE2A8.toInt()
            Thread.sleep(900)
            restore(before)
        }.start()
    }

    /** Zet een vaste kleur (#rrggbb). */
    fun setColor(rgb: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(rgb, hsv)
        send(mapOf("20" to true, "21" to "colour",
            "24" to hsvHex(hsv[0].toInt() % 360, (hsv[1] * 1000).toInt(), maxOf(10, (hsv[2] * 1000).toInt()))))
        shownColor = rgb or 0xFF000000.toInt()
    }

    /** Terug naar een opgeslagen stand (functies 20..24); zonder stand warm wit. */
    fun restore(st: JSONObject?) {
        val m = LinkedHashMap<String, Any>()
        if (st != null) for (k in STATE_KEYS) if (st.has(k)) m[k] = st.get(k)
        send(if (m.isNotEmpty()) m else WARM_WHITE, raw = true)
        shownColor = 0xFF555555.toInt()
    }

    /** Lampen zonder lagen (WiZ, Hue): de belangrijkste laag wordt de kleur van de hele lamp; geeft de opdrachten die klaarstaan. */
    private fun takeFlatBatch(): Map<String, Any>? = synchronized(lock) {
        layerPending.entries.maxByOrNull { it.value.getOrElse(3) { 0 } }?.let { e ->
            if (!pending.containsKey("24")) pending["24"] = hsvHex(e.value[0], e.value[1], e.value[2]); layerPending.clear()
        }
        if (pending.isNotEmpty()) LinkedHashMap(pending).also { pending.clear() } else null
    }

    private fun hasWork() = synchronized(lock) { pending.isNotEmpty() || layerPending.isNotEmpty() || queryWanted }

    /** Wachten tot er iets te doen is (send, queryStatus en stop maken wakker), hooguit maxMs. */
    private fun idleWait(maxMs: Long) = synchronized(lock) {
        if (running && pending.isEmpty() && layerPending.isEmpty() && !queryWanted) lock.wait(maxMs.coerceAtLeast(1))
    }

    private fun noteFailure(e: Exception) {
        lastError = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(120); lastFail = lastError ?: ""
        AppLog.add("${lamp.name}: $lastFail")
        reconnects++; online = false
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    /** WiZ: losse UDP-berichten; wachten op het antwoord geeft vanzelf het tempo dat de lamp aankan. */
    private fun wizLoop() {
        var mode = "white"
        var lastSend = 0L; var lastBeat = 0L
        var udp: java.net.DatagramSocket? = null
        val buf = ByteArray(2048)
        fun exchange(method: String, params: JSONObject?, waitMs: Int): JSONObject? {
            val s = udp ?: java.net.DatagramSocket().also { udp = it }
            val msg = JSONObject().put("method", method).apply { if (params != null) put("params", params) }.toString().toByteArray()
            s.send(java.net.DatagramPacket(msg, msg.size, java.net.InetAddress.getByName(ip), Wiz.PORT))
            val end = SystemClock.elapsedRealtime() + waitMs
            while (true) {
                val left = end - SystemClock.elapsedRealtime(); if (left <= 0) return null
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val pkt = java.net.DatagramPacket(buf, buf.size)
                try { s.receive(pkt) } catch (_: java.net.SocketTimeoutException) { return null }
                val j = try { JSONObject(String(buf, 0, pkt.length)) } catch (_: Exception) { continue }
                if (j.optString("method") != method) continue
                lastRx = SystemClock.elapsedRealtime(); online = true; lastError = null
                return j
            }
        }
        while (running) {
            try {
                if (ip.isBlank()) { online = false; sleepQuiet(2000); continue }
                val now = SystemClock.elapsedRealtime()
                if (queryWanted || lastBeat == 0L) {
                    queryWanted = false; lastBeat = now
                    exchange("getPilot", null, 600)?.optJSONObject("result")?.let { r ->
                        val d = Wiz.dps(r); mode = d.optString("21", mode); statusResult = d; noteState(d)
                    }
                    continue
                }
                val batch = takeFlatBatch()
                if (batch != null) {
                    val gap = if (Store.settingsFor(lamp).fade) FADE_GAP_MS else MIN_GAP_MS
                    val wait = gap - (now - lastSend); if (wait > 0) Thread.sleep(wait)
                    val p = Wiz.pilot(batch, mode)
                    (batch["21"] as? String)?.let { mode = it }
                    // Geen antwoord meer (stroom eraf): na 5 seconden als niet bereikbaar tonen, ook tijdens de sync
                    if (p.length() > 0 && exchange("setPilot", p, 250) == null && SystemClock.elapsedRealtime() - lastRx > 5000) online = false
                    lastSend = SystemClock.elapsedRealtime(); lastBeat = lastSend
                } else if (now - lastBeat > 5000) {
                    lastBeat = now
                    val r = exchange("getPilot", null, 600)
                    r?.optJSONObject("result")?.let { noteState(Wiz.dps(it)) }
                    if (r == null && now - lastRx > 12_000) online = false
                } else {
                    idleWait(5000 - (now - lastBeat))   // tot de volgende hartslag; nieuwe opdrachten maken eerder wakker
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                noteFailure(e)
                try { udp?.close() } catch (_: Exception) { }; udp = null
                sleepQuiet(500)
            }
        }
        try { udp?.close() } catch (_: Exception) { }
    }

    /**
     * Hue: lampen in de Entertainment-ruimte gaan tijdens sync en scènes via de stream (HueStream);
     * de rest via gewone opdrachten aan de Bridge, met een gedeelde rem van ±10 per seconde voor alle Hue-lampen.
     */
    private fun hubLoop(hub: Hub) {
        val hueId = lamp.id.removePrefix(hub.prefix)
        var mode = "white"
        var lastSend = 0L; var lastBeat = 0L
        // De hub antwoordde; of de lamp zelf bereikbaar is, weet alleen de hub (Zigbee-status)
        fun alive(t: Long) { lastRx = t; online = hub.reachable(hueId) != false; lastError = null }
        while (running) {
            try {
                if (ip.isBlank()) { online = false; sleepQuiet(2000); continue }
                val now = SystemClock.elapsedRealtime()
                if (queryWanted || lastBeat == 0L) {
                    queryWanted = false; lastBeat = now
                    // Ook statusvragen op hun beurt: met veel lampen vroegen ze anders allemaal tegelijk en liep de hub vast (timeouts)
                    hub.waitTurn()
                    val d = hub.light(hueId)
                    if (d != null) { mode = d.optString("21", mode); statusResult = d; noteState(d); alive(now) }
                    else if (now - lastRx > 12_000) online = false
                    continue
                }
                val batch = takeFlatBatch()
                if (batch != null) {
                    val m = mode
                    (batch["21"] as? String)?.let { mode = it }
                    if (hub.offer(lamp.id, batch, m)) {
                        if (hub.streaming()) alive(now)
                        lastSend = now; lastBeat = now
                        continue
                    }
                    hub.beforeSet(lamp.id)
                    hub.waitTurn()
                    val t = SystemClock.elapsedRealtime()
                    // Overgang zo lang als de tijd sinds de vorige opdracht: dan loopt de kleur vloeiend door
                    val ms = if (Store.settingsFor(lamp).fade) (t - lastSend).coerceIn(100L, 800L) else 0L
                    if (!hub.setLight(hueId, batch, m, lamp.category, ms)) {
                        // Hub heeft het druk: opnieuw proberen, tenzij er intussen iets nieuwers klaarstaat
                        synchronized(lock) { for ((k, v) in batch) pending.putIfAbsent(k, v) }
                        sleepQuiet(500); continue
                    }
                    lastSend = SystemClock.elapsedRealtime(); lastBeat = lastSend
                    alive(lastSend)
                } else if (now - lastBeat > 5000 && !hub.streaming()) {
                    lastBeat = now
                    hub.waitTurn()
                    val d = hub.light(hueId)
                    if (d != null) { noteState(d); alive(now) } else if (now - lastRx > 12_000) online = false
                } else {
                    idleWait(5000 - (now - lastBeat))
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                noteFailure(e)
                sleepQuiet(1000)
            }
        }
    }

    private fun loop() {
        if (wiz) { wizLoop(); return }
        if (hue) { hubLoop(Hue); return }
        if (ikea) { hubLoop(Dirigera); return }
        var lastSend = 0L
        var lastBeat = 0L
        var connectedOnce = false
        var failSince = 0L
        val idleClose = if (keepOpen) 0L else Platform.idleCloseMs
        while (running) {
            try {
                if (sock == null) {
                    if (ip.isBlank() || !lamp.supported) { online = false; sleepQuiet(2000); continue }
                    // Server: pas verbinden als er iets te sturen is (de tv moet de lamp ook kunnen bereiken)
                    if (idleClose > 0 && connectedOnce && !hasWork()) { idleWait(5000); continue }
                    if (idleClose > 0 && lastError != null) {
                        // Houdt iemand anders de lamp vast (tv of server), dan rustig opnieuw proberen en opdrachten
                        // na 15 s laten vallen, in plaats van de lamp elke 0,3 s lastig te vallen
                        connectedOnce = true
                        val now = SystemClock.elapsedRealtime()
                        if (failSince == 0L) failSince = now
                        else if (now - failSince > 15_000) { synchronized(lock) { pending.clear(); layerPending.clear(); queryWanted = false }; failSince = now }
                        sleepQuiet(1500)
                    }
                    connect(); connectedOnce = true; failSince = 0L
                    lastBeat = SystemClock.elapsedRealtime(); lastSend = lastBeat
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
                        val segs = if (0 in keys) (1..5).toList() else keys
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
                    val gap = when {
                        isLayer && lamp.category == "dd" -> STRIP_GAP_MS          // ledstrips: rustig tempo, anders resetten ze
                        !Store.settingsFor(lamp).fade -> MIN_GAP_MS
                        isLayer -> LAYER_GAP_MS
                        else -> FADE_GAP_MS
                    }
                    val wait = gap - (now - lastSend)
                    if (wait > 0) Thread.sleep(wait)
                    sendControl(adapt(batch)); inflight++
                    lastSend = SystemClock.elapsedRealtime(); lastBeat = lastSend
                } else if (idleClose > 0 && now - lastSend > idleClose) {
                    close()   // even niets te doen: verbinding vrijgeven (online blijft de laatst bekende stand)
                } else if (now - lastBeat > 8000) {
                    write(TuyaCodec.HEART_BEAT, "{}", header = false)
                    lastBeat = now
                } else {
                    idleWait(40)   // Tuya: ook binnenkomende standen van de lamp bijhouden (drain)
                }
            } catch (e: Exception) {
                noteFailure(e)
                close()
                // Sommige lampen (Wall Washer) resetten onder belasting om de paar seconden: dan meteen opnieuw verbinden
                if (e !is java.net.SocketException || e.message?.contains("reset", true) != true) sleepQuiet(300)
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
                noteState(dps); noteAll(dps)
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
            return java.util.Base64.getEncoder().encodeToString(b.array())
        }

        /** Minimale tijd tussen twee opdrachten; sneller verslikken de lampen zich. */
        const val MIN_GAP_MS = 70L
        const val LAYER_GAP_MS = 18L
        /** Ledstrips (Wall Washer) verbreken de verbinding als ze sneller dan ±6 berichten per seconde krijgen. */
        const val STRIP_GAP_MS = 160L
        /** Met vloeiende overgangen: gewone lampen vaker bijwerken (ongeveer 25 keer per seconde). */
        const val FADE_GAP_MS = 40L
        /** Hoeveel opdrachten er tegelijk onbevestigd onderweg mogen zijn. */
        const val MAX_INFLIGHT = 2
        /** Lamp met lagen antwoordt trager (±155 ms) maar kan meer tegelijk aan. */
        const val MAX_INFLIGHT_LAYERS = 1

        /** Twee laagkleuren (h, s, v) die zo dicht bij elkaar liggen dat één bericht volstaat. */
        fun similar(a: IntArray, b: IntArray): Boolean {
            val dh = Math.abs(a[0] - b[0]).let { minOf(it, 360 - it) }
            return (dh < 8 || a[1] < 80 && b[1] < 80) && Math.abs(a[1] - b[1]) < 60 && Math.abs(a[2] - b[2]) < 50
        }

        fun hsvHex(h: Int, s: Int, v: Int) = "%04x%04x%04x".format(h, s, v)
        /** Omgekeerde van hsvHex: "hhhhssssvvvv" naar h, s, v (de aanroeper controleert de lengte). */
        fun parseHsvHex(c: String) = intArrayOf(c.substring(0, 4).toInt(16), c.substring(4, 8).toInt(16), c.substring(8, 12).toInt(16))
        /** De functies die samen de stand van een lamp vormen: aan/uit, modus, helderheid, temperatuur, kleur. */
        val STATE_KEYS = listOf("20", "21", "22", "23", "24")
        /** Neutraal warm wit: terugval als er geen opgeslagen stand is. */
        val WARM_WHITE: Map<String, Any> = mapOf("20" to true, "21" to "white", "22" to 1000, "23" to 500)
    }
}
