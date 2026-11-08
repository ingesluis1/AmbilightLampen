package nl.stacklines.tvambilight

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** De laatste interne meldingen (fouten van lampen, hubs, stream), om mee te sturen bij een crash of probleemmelding. */
object AppLog {
    private val lines = ArrayDeque<String>()
    private var last = ""; private var repeat = 0
    /** Ook naar de console schrijven (de hub: dan staan lampfouten in `docker logs`). */
    @Volatile var toConsole = false

    @Synchronized
    fun add(msg: String) {
        // Dezelfde fout die zich steeds herhaalt (lamp die blijft weigeren) maar één keer, met een teller
        if (msg == last) { repeat++; return }
        if (repeat > 0) lines.addLast("   (nog ${repeat}x)").also { if (toConsole) println("   (nog ${repeat}x)") }
        last = msg; repeat = 0
        lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + msg)
        if (toConsole) println(java.time.LocalDateTime.now().withNano(0).toString() + " " + msg)
        while (lines.size > 40) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = (lines + if (repeat > 0) listOf("   (nog ${repeat}x)") else emptyList()).joinToString("\n").ifBlank { "(geen)" }
}
