package nl.stacklines.tvambilight

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Meetlog "na aanzetten" (16-11-2026): hoe lang duurt het na scherm aan tot het netwerk er is en tot elke lamp
 * verbonden is, en wat ging er mis. Voor de spotjes die pas na een tijdje meedoen. Bewaard in `aanzetlog.txt`
 * (laatste 300 regels), te zien via /api/aanzetlog en in Probleem melden.
 */
object WakeTrace {
    @Volatile var file: File? = null
    private const val WINDOW_MS = 120_000L
    @Volatile private var since = 0L
    @Volatile private var round = 0
    private val connected = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val lastErr = HashMap<String, String>()

    private fun active() = since > 0 && SystemClock.elapsedRealtime() - since < WINDOW_MS
    private fun secs() = "%.1f s".format(Locale.ROOT, (SystemClock.elapsedRealtime() - since) / 1000.0)

    /** Losse gebeurtenis (scherm uit, lampen losgelaten, sync gestart) altijd vastleggen. */
    fun note(msg: String) = line(if (active()) "$msg (na ${secs()})" else msg)

    /** Scherm aan: nieuwe meting. Kijkt zelf wanneer het netwerk er is en maakt na 2 minuten een samenvatting. */
    fun start(what: String) {
        val my: Int
        synchronized(this) { since = SystemClock.elapsedRealtime(); round++; my = round; connected.clear(); fails.clear(); lastErr.clear() }
        line("=== Tv aan: $what. Netwerk nu: ${Net.localIp() ?: "geen"}")
        Thread({
            try {
                var had = Net.localIp() != null
                while (!had && round == my && active()) { Thread.sleep(200); had = Net.localIp() != null; if (had) note("Netwerk terug: ${Net.localIp()}") }
                Thread.sleep(WINDOW_MS - (SystemClock.elapsedRealtime() - since).coerceAtMost(WINDOW_MS))
                if (round == my) summary()
            } catch (_: Exception) { }
        }, "aanzetlog").apply { isDaemon = true; start() }
    }

    fun connected(name: String) {
        if (!active()) return
        val first = synchronized(this) { if (name in connected) false else { connected[name] = SystemClock.elapsedRealtime() - since; true } }
        if (first) line("$name verbonden na ${secs()}" + (fails[name]?.let { ", na $it mislukte pogingen (laatste: ${lastErr[name]})" } ?: ""))
    }

    fun failed(name: String, err: String) {
        if (!active()) return
        synchronized(this) { fails[name] = (fails[name] ?: 0) + 1; lastErr[name] = err.take(100) }
    }

    private fun summary() {
        val lines = synchronized(this) {
            val names = (fails.keys + connected.keys).toSortedSet()
            names.map { n -> "  $n: " + (connected[n]?.let { "verbonden na %.1f s".format(Locale.ROOT, it / 1000.0) } ?: "NIET verbonden") +
                (fails[n]?.let { ", $it mislukt, laatste fout: ${lastErr[n]}" } ?: "") }
        }
        line("Samenvatting 2 min na aanzetten:" + if (lines.isEmpty()) " geen lampverbindingen gezien" else "")
        lines.forEach { line(it) }
    }

    @Synchronized
    private fun line(msg: String) {
        AppLog.add("Aanzetlog: $msg")
        val f = file ?: return
        try {
            val all = (if (f.exists()) f.readLines() else emptyList()) + (SimpleDateFormat("dd-MM HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + msg)
            f.writeText(all.takeLast(300).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) { }
    }

    fun dump(): String = try { file?.takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "(leeg)" } catch (_: Exception) { "(niet te lezen)" }
}
