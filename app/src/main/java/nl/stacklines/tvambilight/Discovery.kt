package nl.stacklines.tvambilight

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * Tuya-lampen roepen elke paar seconden via UDP (poort 6666/6667) hun id en ip-adres om.
 * Zo vindt de app ze ook als de router ze een ander adres geeft.
 */
object Discovery {
    private val udpKey = MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".toByteArray())
    @Volatile private var started = false
    @Volatile private var sock7000: DatagramSocket? = null
    /** Alle lampen die zich gemeld hebben (id naar ip en versie), ook die nog niet in de lijst staan. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private var found: ((String, String, String) -> Unit)? = null

    /**
     * Lampen die net zijn toegevoegd (cloud, devices.json) meteen hun adres geven als ze zich al gemeld hadden,
     * en een paar keer kort na elkaar vragen wie er is (anders duurt het tot de volgende melding, soms minuten).
     */
    fun lookupNew() {
        val cb = found ?: return
        for ((id, v) in seen) cb(id, v.first, v.second)
        Thread({
            for (wait in longArrayOf(0, 2000, 5000)) {
                try { Thread.sleep(wait); ask() } catch (_: Exception) { }
            }
        }, "discovery-burst").apply { isDaemon = true; start() }
    }

    fun start(ctx: Context, onFound: (id: String, ip: String, version: String) -> Unit) {
        if (started) return
        started = true
        found = onFound
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            wifi?.createMulticastLock("tuya")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        for (port in intArrayOf(6666, 6667, 7000)) {
            Thread({ listen(port, onFound) }, "discovery-$port").apply { isDaemon = true; start() }
        }
        // Nieuwere 3.5-lampen melden zich pas als je erom vraagt: "wie is er?" naar poort 7000
        Thread({
            var n = 0
            while (true) {
                try { ask() } catch (_: Exception) { }
                n++
                Thread.sleep(if (n < 12) 15_000L else 120_000L)
            }
        }, "discovery-ask").apply { isDaemon = true; start() }
    }

    /** Stuurt het zoekverzoek (commando 0x25) als 6699-frame met de vaste UDP-sleutel. */
    fun ask() {
        val ip = WebServer.localIp() ?: return
        val body = JSONObject().put("from", "app").put("ip", ip).toString().toByteArray()
        val frame = TuyaCodec.frameGcm(udpKey, 0, 0x25, body)
        val bcast = ip.substringBeforeLast('.') + ".255"
        fun sendAll(s: DatagramSocket) {
            for (target in listOf(bcast, "255.255.255.255")) {
                try { s.send(DatagramPacket(frame, frame.size, java.net.InetAddress.getByName(target), 7000)) } catch (_: Exception) { }
            }
        }
        // Liefst vanaf de luisterpoort (dan komt het antwoord daar binnen); anders een tijdelijke socket die weer dicht gaat
        sock7000?.let { sendAll(it); return }
        DatagramSocket().use { it.broadcast = true; sendAll(it) }
    }

    private fun report(id: String, ip: String, version: String, onFound: (String, String, String) -> Unit) {
        seen[id] = ip to version
        onFound(id, ip, version)
    }

    private fun listen(port: Int, onFound: (String, String, String) -> Unit) {
        val sock = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(port)) }
        } catch (_: Exception) { return }
        if (port == 7000) sock7000 = sock
        val buf = ByteArray(2048)
        while (true) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val data = buf.copyOf(pkt.length)
                if (data.size < 28) continue
                if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0x66.toByte() && data[3] == 0x99.toByte()) {
                    val f = TuyaCodec.readFrame(java.io.DataInputStream(data.inputStream()), "3.5", udpKey)
                    val j = TuyaCodec.jsonOf(f.plain) ?: continue
                    val id = j.optString("gwId").ifBlank { j.optString("devId") }
                    if (id.isNotBlank() && j.optString("ip").isNotBlank()) report(id, j.optString("ip"), j.optString("version", "3.5"), onFound)
                    continue
                }
                val len = ((data[12].toInt() and 0xff) shl 24) or ((data[13].toInt() and 0xff) shl 16) or
                    ((data[14].toInt() and 0xff) shl 8) or (data[15].toInt() and 0xff)
                val end = (16 + len - 8).coerceAtMost(data.size)
                if (end <= 16) continue
                val payload = data.copyOfRange(16, end)
                val text = (if (port == 6667) TuyaCodec.decode(udpKey, payload) else null)
                    ?: String(payload, Charsets.UTF_8).let { s -> s.indexOf('{').let { if (it >= 0) s.substring(it) else null } }
                    ?: continue
                val j = JSONObject(text.trim { it <= ' ' || it == '\u0000' })
                val id = j.optString("gwId").ifBlank { j.optString("devId") }
                val ip = j.optString("ip")
                if (id.isNotBlank() && ip.isNotBlank()) report(id, ip, j.optString("version", "3.3"), onFound)
            } catch (_: Exception) { }
        }
    }
}
