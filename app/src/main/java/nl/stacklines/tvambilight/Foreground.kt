package nl.stacklines.tvambilight

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock

/**
 * Welke app staat er op de tv? De Philips-API zegt het niet (geeft "NA"), Android wel, via "Gebruikstoegang".
 * Die toestemming zet je één keer aan: tv-instellingen, Apps, Speciale app-toegang, Gebruikstoegang,
 * of met adb: appops set nl.stacklines.tvambilight GET_USAGE_STATS allow.
 * Zonder toestemming weet de app het niet en pauzeert hij nooit om deze reden.
 */
object Foreground {
    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }

    /** Startscherm, schermbeveiliging en tv-instellingen: daar valt niets te kijken. */
    private val IDLE = setOf(
        "com.google.android.apps.tv.launcherx",    // Google TV-startscherm
        "com.google.android.tvlauncher",           // Android TV-startscherm
        "com.google.android.leanbacklauncher",     // ouder Android TV-startscherm
        "com.google.android.apps.tv.dreamx",       // schermbeveiliging (Google TV)
        "com.android.dreams.basic",                // schermbeveiliging (standaard Android)
        "com.android.tv.settings",                 // instellingenmenu van de tv
    )

    /** Laatst bekende app op de voorgrond (pakketnaam), of null als onbekend. */
    @Volatile var current: String? = null
        private set
    private var queriedTo = 0L
    private var lastCheck = 0L

    /** Heeft de app toestemming om dit te zien? */
    fun allowed(): Boolean {
        val c = ctx ?: return false
        return try {
            val ops = c.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    /** Staat er nu iets op de tv waar niets te kijken valt? Hooguit eens per seconde opgevraagd. */
    @Synchronized
    fun idle(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck >= 1000) { lastCheck = now; refresh() }
        return current in IDLE
    }

    /** App die nu op de tv staat, of null bij het startscherm of als het onbekend is. */
    fun app(): String? { idle(); return current?.takeIf { it !in IDLE } }

    private var appsCache: List<Pair<String, String>> = emptyList()
    private var appsAt = 0L

    /** Apps op de tv die je kunt openen (pakketnaam, naam), op naam; hooguit eens per minuut opnieuw opgevraagd. */
    @Synchronized
    fun installed(): List<Pair<String, String>> {
        val c = ctx ?: return emptyList()
        val now = SystemClock.elapsedRealtime()
        if (appsAt != 0L && now - appsAt < 60_000) return appsCache
        appsAt = now
        appsCache = try {
            val pm = c.packageManager
            val found = LinkedHashMap<String, String>()
            for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == c.packageName || pkg in IDLE || pkg in found) continue
                    found[pkg] = ri.loadLabel(pm).toString()
                }
            }
            found.toList().sortedBy { it.second.lowercase() }
        } catch (_: Exception) { appsCache }
        return appsCache
    }

    /** Leest de app-wissels sinds de vorige keer (de eerste keer de afgelopen 6 uur, voor de huidige stand). */
    private fun refresh() {
        val c = ctx ?: return
        try {
            val usm = c.getSystemService(UsageStatsManager::class.java) ?: return
            val to = System.currentTimeMillis()
            val from = if (queriedTo == 0L) to - 6 * 3_600_000L else queriedTo - 2000   // kleine overlap: niets missen
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) current = e.packageName
            }
            queriedTo = to
        } catch (_: Exception) { }
    }
}
