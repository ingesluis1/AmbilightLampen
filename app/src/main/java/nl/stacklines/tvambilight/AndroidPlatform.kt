package nl.stacklines.tvambilight

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Vult [Platform] met de Android-versies; als eerste aanroepen bij het starten van de app. */
object AndroidPlatform {
    fun install(c: Context) {
        val ctx = c.applicationContext
        Platform.clock = { android.os.SystemClock.elapsedRealtime() }
        Platform.userStopped = { AutoDark.userStopped() }
        Platform.homeScreen = { Foreground.idle() }
        Platform.multicastLock = {
            try {
                val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager?
                wifi?.createMulticastLock("tuya")?.apply { setReferenceCounted(false); acquire() }
            } catch (_: Exception) { }
        }
        Platform.findService = { type, timeoutMs, accept -> findService(ctx, type, timeoutMs, accept) }
    }

    private fun findService(ctx: Context, type: String, timeoutMs: Long, accept: (String) -> Boolean): String? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        val found = CountDownLatch(1)
        var result: String? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        s.host?.hostAddress?.takeIf(accept)?.let { result = it; found.countDown() }
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, err: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, err: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(t: String, err: Int) {}
        }
        return try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
            result
        } catch (_: Exception) { null } finally { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { } }
    }
}
