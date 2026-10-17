package nl.stacklines.tvambilight

import android.os.SystemClock
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Hue Entertainment: de snelle weg van Hue Sync. Na "start" op een Entertainment-ruimte stuurt de app
 * via een versleutelde UDP-verbinding (DTLS met de clientkey) tot 50 keer per seconde de kleur van elk kanaal.
 * De Bridge geeft dat met ±25 per seconde door aan de lampen, zonder de rem van gewone opdrachten.
 * Alleen Hue-lampen kunnen in een Entertainment-ruimte; andere (IKEA) krijgen gewone opdrachten.
 */
object HueDtls {
    const val PORT = 2100

    /** DTLS 1.2 met PSK: identiteit = application key (username), sleutel = clientkey (hex). */
    fun open(host: String, port: Int, identity: String, pskHex: String, timeoutMs: Int = 5000): Pair<DatagramSocket, DTLSTransport> {
        val psk = ByteArray(pskHex.length / 2) { pskHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val client = object : PSKTlsClient(BcTlsCrypto(SecureRandom()), BasicTlsPSKIdentity(identity, psk)) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
            override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
            override fun getHandshakeTimeoutMillis() = timeoutMs
        }
        val udp = DatagramSocket()
        try {
            udp.connect(InetSocketAddress(host, port))
            return udp to DTLSClientProtocol().connect(client, UDPTransport(udp, 1400))
        } catch (e: Exception) { udp.close(); throw e }
    }

    /** HueStream v2: kop (16 bytes), id van de ruimte (36 tekens), dan per kanaal id + R, G, B (elk 16 bits). */
    fun frame(area: String, colors: Map<Int, IntArray>, seq: Int): ByteArray {
        val b = ByteBuffer.allocate(52 + 7 * colors.size)
        b.put("HueStream".toByteArray(Charsets.US_ASCII))
        b.put(2); b.put(0)               // versie 2.0
        b.put(seq.toByte())
        b.put(0); b.put(0)               // gereserveerd
        b.put(0)                         // kleurruimte RGB
        b.put(0)                         // gereserveerd
        b.put(area.toByteArray(Charsets.US_ASCII).copyOf(36))
        for ((ch, c) in colors) { b.put(ch.toByte()); b.putShort(c[0].toShort()); b.putShort(c[1].toShort()); b.putShort(c[2].toShort()) }
        return b.array()
    }
}

object HueStream {
    /** Lamp-id naar de kanalen van die lamp in de gekozen Entertainment-ruimte. */
    @Volatile var channels: Map<String, List<Int>> = emptyMap()
    @Volatile var areaId: String? = null
    /** Draait de stream nu echt (verbonden)? */
    @Volatile var live = false
        private set
    /** Laatste melding (fout of "actief"), voor de telefoon. */
    @Volatile var status = ""
        private set

    private val colors = ConcurrentHashMap<Int, IntArray>()
    @Volatile private var thread: Thread? = null
    /** Na een fout even gewone opdrachten gebruiken in plaats van steeds opnieuw proberen. */
    @Volatile private var failedUntil = 0L
    /** Mislukte pogingen achter elkaar; de wachttijd loopt op (20 s, 40 s, ... tot 10 minuten). */
    private var fails = 0

    private fun wanted() = (Engine.running || SceneEngine.active != null) && Store.hueKey != null &&
        Store.hueClientKey != null && areaId != null && channels.isNotEmpty()

    /** Hoort deze lamp bij de stream (zit hij in de Entertainment-ruimte)? */
    fun holds(lampId: String) = channels.containsKey(lampId)

    /**
     * Neemt de opdracht over als deze lamp via de stream kan. false = gewoon via de Bridge sturen.
     * Kleuren worden onthouden; de stream stuurt steeds de nieuwste.
     */
    fun offer(lampId: String, dps: Map<String, Any>, mode: String): Boolean {
        val ch = channels[lampId] ?: return false
        if (!wanted() || SystemClock.elapsedRealtime() < failedUntil) return false
        Hue.rgb16(dps, mode)?.let { c -> for (n in ch) colors[n] = c }
        ensureRunning()
        return true
    }

    @Synchronized
    private fun ensureRunning() {
        if (thread?.isAlive == true) return
        thread = Thread(::run, "hue-stream").apply { isDaemon = true; start() }
    }

    /** Wacht tot de stream gestopt is (de Bridge negeert gewone opdrachten aan lampen die gestreamd worden). */
    fun awaitStop() {
        val t = thread ?: return
        if (t.isAlive && wanted()) return
        t.join(3000)
    }

    private fun area(action: String): Boolean {
        val host = Store.hueIp ?: return false
        val id = areaId ?: return false
        val (code, text) = Hue.request("PUT", "https://$host/clip/v2/resource/entertainment_configuration/$id",
            JSONObject().put("action", action).toString())
        if (code != 200 && action == "start") status = tr("Bridge wil de stream niet starten: ", "Bridge won't start the stream: ") + "HTTP $code " + text.take(100)
        return code == 200
    }

    private fun run() {
        var udp: DatagramSocket? = null
        var dtls: DTLSTransport? = null
        var started = false
        var seq = 0
        try {
            while (wanted()) {
                if (dtls == null) {
                    status = tr("Stream starten...", "Starting stream...")
                    if (!area("start")) throw Hue.HueException(status)
                    started = true
                    val host = Store.hueIp!!.substringBefore(':')
                    val c = HueDtls.open(host, HueDtls.PORT, Store.hueKey!!, Store.hueClientKey!!)
                    udp = c.first; dtls = c.second
                    live = true; fails = 0; status = tr("Stream actief", "Stream active")
                }
                val area = areaId ?: break
                val f = HueDtls.frame(area, HashMap(colors), seq++)
                dtls.send(f, 0, f.size)
                Thread.sleep(20)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (status.isBlank() || live || status == tr("Stream starten...", "Starting stream...")) status = tr("Stream mislukt: ", "Stream failed: ") + (e.message ?: e.javaClass.simpleName)
            fails++
            failedUntil = SystemClock.elapsedRealtime() + minOf(600_000L, 20_000L shl minOf(fails - 1, 5))
        } finally {
            live = false
            try { dtls?.close() } catch (_: Exception) { }
            try { udp?.close() } catch (_: Exception) { }
            if (started) try { area("stop") } catch (_: Exception) { }
            colors.clear()
            if (status == tr("Stream actief", "Stream active")) status = ""
        }
    }
}
