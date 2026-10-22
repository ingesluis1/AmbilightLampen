package nl.stacklines.tvambilight

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crashes en "Probleem melden" naar de maker, als pushbericht via de eigen ntfy-server (ntfy.stacklines.nl).
 * Iedereen mag naar het kanaal sturen, alleen de maker kan het lezen (met account).
 * Een crash wordt eerst als bestand bewaard (het proces gaat daarna dood) en bij de volgende start verstuurd.
 * In een melding staan geen sleutels, wachtwoorden of ip-adressen. Uit te zetten met de instelling "Fouten naar de maker sturen".
 */
object Reporter {
    private const val URL_NTFY = "https://ntfy.stacklines.nl/ambilight"
    private var ctx: Context? = null
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

    fun init(c: Context) {
        ctx = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { save(t, e) } catch (_: Throwable) { }
            // Fout in een achtergrondtaak (webserver, lamp, sync): melden, maar de app laten doordraaien.
            // Anders kan één fout bij het opstarten de app steeds opnieuw laten crashen. Alleen het hoofdscherm valt terug op Android.
            if (t === android.os.Looper.getMainLooper().thread) previous?.uncaughtException(t, e)
            else Thread({ try { sendPending() } catch (_: Throwable) { } }, "crash-send-now").apply { isDaemon = true; start() }
        }
        // Crashes van de vorige keer versturen (op de achtergrond, internet kan nog even op zich laten wachten)
        Thread({ try { Thread.sleep(15_000); sendPending() } catch (_: Throwable) { } }, "crash-send").apply { isDaemon = true; start() }
    }

    private fun dir() = File(ctx!!.filesDir, "crashes").apply { mkdirs() }

    private fun header(kind: String) = buildString {
        appendLine("$kind - Ambilight Lampen ${version()}")
        appendLine("Installatie ${Store.installId.take(6)} - ${Build.MANUFACTURER} ${Build.MODEL} - Android ${Build.VERSION.RELEASE}")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
    }

    private fun version() = try { ctx?.let { Updater.current(it) } ?: "?" } catch (_: Throwable) { "?" }

    private fun save(t: Thread, e: Throwable) {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val text = header("Crash") + "Thread: ${t.name}\n\n" + sw.toString().take(3000) + "\n\nLaatste meldingen:\n" + AppLog.dump()
        File(dir(), "crash-${System.currentTimeMillis()}.txt").writeText(scrub(text))
    }

    fun sendPending() {
        if (!Store.settings.reports) return
        for (f in dir().listFiles()?.sortedBy { it.name } ?: return) {
            if (send("Ambilight Lampen crash (${version()})", f.readText(), "rotating_light", 4)) f.delete()
        }
    }

    /** "Probleem melden" vanaf de telefoon: wat de gebruiker schrijft plus de stand van de app. */
    fun report(note: String): Boolean = send("Ambilight Lampen probleem (${version()})", reportText(note), "speech_balloon", 3)

    /** Tekst van een probleemmelding (ook om zelf te kopiëren als versturen niet lukt). */
    fun reportText(note: String): String {
        val lamps = Store.lampsSnapshot().joinToString("\n") { l ->
            val w = Engine.workers[l.id]
            "- ${l.name} [${l.version}${if (l.category.isNotBlank()) ", " + l.category else ""}] " +
                (if (w?.online == true) "bereikbaar" else "NIET bereikbaar") + (w?.lastFail?.takeIf { it.isNotBlank() }?.let { " | laatste fout: $it" } ?: "")
        }
        val text = header("Probleemmelding") + "\n" + (note.trim().take(1000).ifBlank { "(geen toelichting)" }) + "\n\n" +
            "Tv: ${Engine.tvStatus}, sync ${if (Engine.running) "aan" else "uit"}, scène ${SceneEngine.active?.name ?: "-"}\n" +
            "Hue: ${if (Store.hueKey != null) "gekoppeld" else "-"} ${Hue.status} ${HueStream.status}\n" +
            "IKEA: ${if (Store.ikeaToken != null) "gekoppeld" else "-"} ${Dirigera.status}\n\n" +
            "Lampen:\n$lamps\n\nLaatste meldingen:\n" + AppLog.dump() +
            (lastSendError?.let { "\n\nVersturen naar ntfy mislukte eerder: $it" } ?: "") + exitReasons() + unsentCrash()
        return scrub(text)
    }

    /**
     * Waarom de app de laatste keren stopte (Android 11+). Een stop door geheugentekort is geen crash en laat dus
     * geen crashbestand achter; zo zien we toch het verschil tussen crash, geheugen en vastgelopen.
     */
    private fun exitReasons(): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val am = ctx?.getSystemService(android.app.ActivityManager::class.java) ?: return ""
        // Alleen de app zelf, niet de processen van de ingebouwde browser
        val list = try { am.getHistoricalProcessExitReasons(ctx!!.packageName, 0, 20) } catch (_: Exception) { return "" }
            .filter { it.processName == ctx!!.packageName }.take(5)
        if (list.isEmpty()) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        return "\n\nVorige keren gestopt:\n" + list.joinToString("\n") { e ->
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
                android.app.ApplicationExitInfo.REASON_ANR -> "vastgelopen (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "geheugentekort"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "gestopt door systeem (signaal ${e.status})"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "door gebruiker"
                android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "update"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "zelf afgesloten"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "onderdeel gestopt"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "te veel verbruik"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "toestemming gewijzigd"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start mislukt"
                else -> "anders (${e.reason})"
            }
            "- ${fmt.format(Date(e.timestamp))} $why, geheugen ${e.pss / 1024} MB" +
                (e.description?.takeIf { it.isNotBlank() }?.let { " | ${it.take(120)}" } ?: "")
        }
    }

    /** Laatste crash die nog niet verstuurd kon worden (geen internet op de tv): mee in de melding, zodat hij via kopiëren toch aankomt. */
    private fun unsentCrash(): String {
        val files = dir().listFiles()?.sortedBy { it.name } ?: return ""
        val last = files.lastOrNull() ?: return ""
        val more = if (files.size > 1) " (en nog ${files.size - 1} eerdere)" else ""
        return "\n\nLaatste crash, nog niet verstuurd$more:\n" + last.readText().take(3500)
    }

    /** Waarom het laatste versturen mislukte (HTTP-code of foutmelding), of null. */
    @Volatile var lastSendError: String? = null
        private set

    private val lastScreen = HashMap<String, Long>()

    /**
     * Probleem met het tv-scherm (pagina laadt niet, JavaScript-fout, ingebouwde browser gestopt). Gaat in het logboek
     * en naar de maker; dezelfde melding hooguit eens per half uur, zodat een herhalende fout niet blijft versturen.
     */
    fun screenProblem(msg: String) {
        AppLog.add(msg)
        if (!Store.settings.reports) return
        val now = System.currentTimeMillis()
        synchronized(lastScreen) {
            if (now - (lastScreen[msg] ?: 0L) < 30 * 60_000L) return
            lastScreen[msg] = now
        }
        Thread({ try { send("Ambilight Lampen tv-scherm (${version()})", scrub(header("Tv-scherm") + "\n" + msg + "\n\nLaatste meldingen:\n" + AppLog.dump()), "tv", 4) } catch (_: Throwable) { } },
            "screen-report").apply { isDaemon = true; start() }
    }

    private fun scrub(s: String) = IPV4.replace(s, "<ip>")

    /** Bericht naar ntfy; ntfy maakt van lange berichten zelf een bijlage. */
    private fun send(title: String, body: String, tags: String, priority: Int): Boolean = try {
        val c = URL(URL_NTFY).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Title", title); c.setRequestProperty("Tags", tags); c.setRequestProperty("Priority", priority.toString())
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        lastSendError = if (code in 200..299) null else "HTTP $code " + (c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }?.take(150) ?: "")
        code in 200..299
    } catch (e: Exception) { lastSendError = e.javaClass.simpleName + ": " + (e.message ?: ""); false }
}
