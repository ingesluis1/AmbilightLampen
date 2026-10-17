package nl.stacklines.tvambilight

import android.content.Context
import android.os.PowerManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Alleen automatisch syncen na zonsondergang": overdag is het buiten te licht om iets van de lampen te merken.
 * Overdag start de sync dan nooit vanzelf (wel met de hand). Vanaf zonsondergang (met verschuiving) start hij
 * vanzelf als de tv aan staat, één keer per avond: zet je hem 's avonds zelf uit, dan blijft hij die avond uit.
 */
object AutoDark {
    private var ctx: Context? = null
    /** Avond (datum) waarop de sync al vanzelf gestart is, of waarop je hem zelf hebt uitgezet. */
    @Volatile private var startedEvening: String? = null
    @Volatile private var stoppedEvening: String? = null

    fun init(c: Context) { ctx = c.applicationContext }

    /** Zonsopkomst en -ondergang van vandaag (ms, UTC), of null in poolnacht/middernachtzon. */
    fun today(): Pair<Long, Long>? = Sun.times(LocalDate.now(), Store.settings.lat, Store.settings.lon)

    /** Welke avond het nu is (datum van de zonsondergang), of null als het licht is. */
    fun evening(now: Long = System.currentTimeMillis()): String? {
        val d = LocalDate.now()
        val t = Sun.times(d, Store.settings.lat, Store.settings.lon) ?: return null
        return when {
            now >= t.second + Store.settings.darkOffset * 60_000L -> d.toString()
            now < t.first -> d.minusDays(1).toString()
            else -> null
        }
    }

    /** Je zette de sync zelf uit (of koos een scène): deze avond niet opnieuw vanzelf starten. */
    fun userStopped() { evening()?.let { stoppedEvening = it } }

    /** Mag de sync bij het aanzetten van de tv vanzelf starten? Met deze instelling alleen als het donker is. */
    fun mayAutostart(): Boolean = !Store.settings.autoDark || evening() != null

    /** Elke minuut, en bij het aanzetten van de tv: donker geworden en de tv staat aan? Dan de sync starten. */
    fun check() {
        if (!Store.settings.autoDark) return
        val e = evening() ?: return
        if (e == startedEvening || e == stoppedEvening) return
        if (Engine.running || SceneEngine.active != null) return
        val pm = ctx?.getSystemService(PowerManager::class.java) ?: return
        if (!pm.isInteractive) return
        startedEvening = e
        Store.syncWanted = true; Store.save()
        Engine.start()
    }

    @Volatile private var started = false
    fun startBackground() {
        if (started) return
        started = true
        Thread({
            while (true) {
                try { check() } catch (_: Exception) { }
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "auto-dark").apply { isDaemon = true; start() }
    }

    /** Voor de telefoon: tijden van vandaag en of het nu donker is. */
    fun info(): org.json.JSONObject {
        val o = org.json.JSONObject().put("dark", evening() != null)
        today()?.let { (rise, set) ->
            val f = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            o.put("sunrise", f.format(Instant.ofEpochMilli(rise))).put("sunset", f.format(Instant.ofEpochMilli(set)))
            o.put("start", f.format(Instant.ofEpochMilli(set + Store.settings.darkOffset * 60_000L)))
        }
        return o
    }
}
