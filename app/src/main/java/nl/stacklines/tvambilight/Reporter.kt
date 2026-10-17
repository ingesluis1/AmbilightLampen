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
 * Crashes en "Probleem melden" naar de maker, als pushbericht via ntfy.sh (gratis, geen account).
 * Een crash wordt eerst als bestand bewaard (het proces gaat daarna dood) en bij de volgende start verstuurd.
 * In een melding staan geen sleutels, wachtwoorden of ip-adressen. Uit te zetten met de instelling "Fouten naar de maker sturen".
 * Let op: de repo is openbaar, dus de kanaalnaam ook. Meldingen bevatten daarom alleen technische informatie.
 */
object Reporter {
    private const val TOPIC = "ambilight-lampen-u4uipunn83ug10xm"
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
    fun report(note: String): Boolean {
        val lamps = Store.lampsSnapshot().joinToString("\n") { l ->
            val w = Engine.workers[l.id]
            "- ${l.name} [${l.version}${if (l.category.isNotBlank()) ", " + l.category else ""}] " +
                (if (w?.online == true) "bereikbaar" else "NIET bereikbaar") + (w?.lastFail?.takeIf { it.isNotBlank() }?.let { " | laatste fout: $it" } ?: "")
        }
        val text = header("Probleemmelding") + "\n" + (note.trim().take(1000).ifBlank { "(geen toelichting)" }) + "\n\n" +
            "Tv: ${Engine.tvStatus}, sync ${if (Engine.running) "aan" else "uit"}, scène ${SceneEngine.active?.name ?: "-"}\n" +
            "Hue: ${if (Store.hueKey != null) "gekoppeld" else "-"} ${Hue.status} ${HueStream.status}\n" +
            "IKEA: ${if (Store.ikeaToken != null) "gekoppeld" else "-"} ${Dirigera.status}\n\n" +
            "Lampen:\n$lamps\n\nLaatste meldingen:\n" + AppLog.dump()
        return send("Ambilight Lampen probleem (${version()})", scrub(text), "speech_balloon", 3)
    }

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
        val c = URL("https://ntfy.sh/$TOPIC").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Title", title); c.setRequestProperty("Tags", tags); c.setRequestProperty("Priority", priority.toString())
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray()) }
        c.responseCode in 200..299
    } catch (_: Exception) { false }
}

/** De laatste interne meldingen (fouten van lampen, hubs, stream), om mee te sturen bij een crash of probleemmelding. */
object AppLog {
    private val lines = ArrayDeque<String>()
    private var last = ""; private var repeat = 0

    @Synchronized
    fun add(msg: String) {
        // Dezelfde fout die zich steeds herhaalt (lamp die blijft weigeren) maar één keer, met een teller
        if (msg == last) { repeat++; return }
        if (repeat > 0) lines.addLast("   (nog ${repeat}x)")
        last = msg; repeat = 0
        lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + msg)
        while (lines.size > 40) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = (lines + if (repeat > 0) listOf("   (nog ${repeat}x)") else emptyList()).joinToString("\n").ifBlank { "(geen)" }
}
