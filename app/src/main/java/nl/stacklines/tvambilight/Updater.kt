package nl.stacklines.tvambilight

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kijkt op GitHub of er een nieuwere release is en installeert die op verzoek.
 * Releases moeten met dezelfde sleutel ondertekend zijn als de geïnstalleerde app.
 */
object Updater {
    private const val API = "https://api.github.com/repos/ingesluis1/AmbilightLampen/releases/latest"

    @Volatile var latest: String? = null
        private set
    @Volatile var apkUrl: String? = null
        private set
    @Volatile var status: String = ""
        private set
    /** Tekst van de release op GitHub: wat er nieuw is. */
    @Volatile var notes: String = ""
        private set
    private var lastCheck = 0L

    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    fun available(ctx: Context): Boolean = latest?.let { newer(it, current(ctx)) } == true && apkUrl != null

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Hooguit eens per uur echt opvragen, tenzij force. */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 3_600_000) return
        lastCheck = now
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "AmbilightLampen")
            c.connectTimeout = 6000; c.readTimeout = 10000
            if (c.responseCode != 200) return
            val j = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val assets = j.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            latest = j.optString("tag_name").removePrefix("v")
            notes = j.optString("body")
            apkUrl = url
        } catch (_: Exception) { }
    }

    /** Download en start de installatie. Geeft een melding terug voor de gebruiker. */
    fun install(ctx: Context): String {
        val url = apkUrl ?: return "Geen update gevonden"
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Sta eerst toe dat deze app updates mag installeren en druk dan nog een keer op Update"
        }
        Thread {
            try {
                status = "Downloaden..."
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    var c = URL(url).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "AmbilightLampen")
                    // GitHub stuurt door naar een ander domein; dat volgt HttpURLConnection niet vanzelf
                    var hops = 0
                    while (c.responseCode in 300..399 && hops++ < 5) {
                        val next = c.getHeaderField("Location") ?: break
                        c = URL(next).openConnection() as HttpURLConnection
                        c.setRequestProperty("User-Agent", "AmbilightLampen")
                    }
                    session.openWrite("update.apk", 0, -1).use { out -> c.inputStream.use { it.copyTo(out) }; session.fsync(out) }
                    status = "Installeren..."
                    val intent = Intent(ctx, UpdateReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(ctx, 0, intent, flags).intentSender)
                }
            } catch (e: Exception) {
                status = "Update mislukt: ${e.message}"
            }
        }.start()
        return "Update wordt gedownload"
    }

    internal fun setStatus(s: String) { status = s }
}

/** Ontvangt de uitkomst van de installatie; vraagt zo nodig om bevestiging. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Updater.setStatus("Bevestig de update op het scherm")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.setStatus("Bijgewerkt")
            else -> Updater.setStatus("Update mislukt: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "onbekend"))
        }
    }
}
