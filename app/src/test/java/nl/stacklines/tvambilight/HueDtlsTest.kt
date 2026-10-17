package nl.stacklines.tvambilight

import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** Nagebootste Bridge: alleen DTLS 1.2 met TLS_PSK_WITH_AES_128_GCM_SHA256, zoals de echte op poort 2100. */
class HueDtlsTest {
    private val user = "abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    private val clientKey = "00112233445566778899AABBCCDDEEFF"

    @Test
    fun handshakeAndFrame() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        var got: ByteArray? = null
        var identity: String? = null
        val t = Thread {
            // Eerste pakket afwachten om te weten waar de client zit
            val first = DatagramPacket(ByteArray(2048), 2048)
            server.receive(first)
            server.connect(first.socketAddress)
            val psk = ByteArray(16) { clientKey.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val tls = object : PSKTlsServer(BcTlsCrypto(SecureRandom()), object : TlsPSKIdentityManager {
                override fun getHint(): ByteArray? = null
                override fun getPSK(id: ByteArray): ByteArray? { identity = String(id); return if (String(id) == user) psk else null }
            }) {
                override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
                override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
            }
            // Het eerste pakket (ClientHello) opnieuw aanbieden via een transport dat het eerst teruggeeft
            val transport = object : UDPTransport(server, 1400) {
                var pending: ByteArray? = first.data.copyOf(first.length)
                override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
                    pending?.let { p -> pending = null; System.arraycopy(p, 0, buf, off, p.size); return p.size }
                    return super.receive(buf, off, len, waitMillis)
                }
            }
            val dtls = DTLSServerProtocol().accept(tls, transport)
            val buf = ByteArray(2048)
            val n = dtls.receive(buf, 0, buf.size, 5000)
            got = buf.copyOf(n)
            dtls.close()
        }
        t.start()
        val (udp, dtls) = HueDtls.open("127.0.0.1", server.localPort, user, clientKey)
        val area = "1a8d99cc-967b-44f2-9202-43f976c0fa6b"
        val f = HueDtls.frame(area, linkedMapOf(0 to intArrayOf(0xffff, 0x8000, 0), 3 to intArrayOf(1, 2, 3)), 7)
        dtls.send(f, 0, f.size)
        t.join(8000)
        dtls.close(); udp.close(); server.close()

        assertEquals(user, identity)
        val g = got!!
        assertArrayEquals(f, g)
        assertEquals("HueStream", String(g, 0, 9))
        assertEquals(2, g[9].toInt()); assertEquals(7, g[11].toInt()); assertEquals(0, g[14].toInt())
        assertEquals(area, String(g, 16, 36))
        assertEquals(52 + 14, g.size)
        // Kanaal 0: ff ff 80 00 00 00
        assertArrayEquals(byteArrayOf(0, -1, -1, -128, 0, 0, 0), g.copyOfRange(52, 59))
    }
}
