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

    fun start(ctx: Context, onFound: (id: String, ip: String, version: String) -> Unit) {
        if (started) return
        started = true
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            wifi?.createMulticastLock("tuya")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        for (port in intArrayOf(6666, 6667, 7000)) {
            Thread({ listen(port, onFound) }, "discovery-$port").apply { isDaemon = true; start() }
        }
    }

    private fun listen(port: Int, onFound: (String, String, String) -> Unit) {
        val sock = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(port)) }
        } catch (_: Exception) { return }
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
                    if (id.isNotBlank() && j.optString("ip").isNotBlank()) onFound(id, j.optString("ip"), j.optString("version", "3.5"))
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
                if (id.isNotBlank() && ip.isNotBlank()) onFound(id, ip, j.optString("version", "3.3"))
            } catch (_: Exception) { }
        }
    }
}
