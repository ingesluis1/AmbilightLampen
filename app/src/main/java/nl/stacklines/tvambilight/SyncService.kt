package nl.stacklines.tvambilight

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.install(this)
        Store.load(filesDir)
        Reporter.init(this)
        Foreground.init(this)
        AutoDark.init(this)
        Discovery.start { id, ip, version ->
            if (!ip.matches(Net.IPV4)) return@start
            val l = Store.lampsSnapshot().firstOrNull { it.id == id } ?: return@start
            if (l.ip != ip || l.version != version) {
                l.ip = ip; l.version = version
                Store.save(); Engine.syncWorkers()
            }
        }
        Wiz.startBackground()
        Hue.startBackground()
        AutoDark.startBackground()
        Dirigera.startBackground()
        Engine.syncWorkers()
    }

    companion object {
        @Volatile var server: WebServer? = null
    }
}

/**
 * Houdt de app actief op de achtergrond: webserver voor de telefoon en de sync-lus.
 * Gaat het scherm uit (standby), dan stopt de sync en gaan de lampen terug; bij aan weer verder.
 */
class SyncService : Service() {
    private var pausedByScreen = false
    /** startForeground werd geweigerd: deze dienst stopt meteen weer (zie [RetryReceiver]). */
    private var blocked = false
    /** Scène die liep omdat de tv uit ging ("Scène als de tv uit staat"); stopt weer als de tv aangaat. */
    private var offScene: Scene? = null
    /** Scherm uit/aan na elkaar afhandelen, in volgorde en niet op de hoofdthread (stoppen kan even duren). */
    private val screenQueue = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val action = i.action
            screenQueue.execute {
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> if (Engine.running) {
                        pausedByScreen = true
                        offScene = Engine.stopThen(if (Store.settings.followAmbi) SceneEngine.find(Store.settings.offScene) else null)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // Alleen als het nog onze scène is (niet als je intussen zelf een andere hebt gekozen)
                        offScene?.let { if (SceneEngine.active === it) SceneEngine.stop() }; offScene = null
                        if (pausedByScreen) { pausedByScreen = false; if (Store.syncWanted && AutoDark.mayAutostart()) Engine.start() }
                        AutoDark.check()   // 's avonds aangezet: vanzelf syncen (als die instelling aan staat)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Ambilight Lampen", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "sync").setContentTitle("Ambilight Lampen").setContentText("Actief")
            .setSmallIcon(R.drawable.ic_app).build()
        // Herstart Android de dienst zelf op de achtergrond (bijvoorbeeld nadat een WebView-update de app stopte),
        // dan mag startForeground soms niet (Android 12+). Niet crashen, maar stoppen en het straks opnieuw proberen.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT < 31 || e !is android.app.ForegroundServiceStartNotAllowedException) throw e
            AppLog.add("Dienst mocht niet starten op de achtergrond, opnieuw over ${RetryReceiver.DELAY_S} s")
            blocked = true
            RetryReceiver.schedule(this)
            stopSelf()
            return
        }
        RetryReceiver.tries = 0
        if (App.server == null) App.server = WebServer(applicationContext).also { it.start() }
        // Elke paar uur kijken of er een nieuwe versie op GitHub staat
        Thread {
            while (true) { Updater.check(); try { Thread.sleep(6 * 3_600_000L) } catch (_: InterruptedException) { break } }
        }.apply { isDaemon = true }.start()
        // Nieuwe lampen of gewijzigde sleutels ophalen als de cloud is ingesteld
        if (Store.cloudConfigured) Thread { try { TuyaCloud.refresh() } catch (_: Exception) { } }.start()
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (blocked) return START_NOT_STICKY
        if (intent?.getBooleanExtra("boot", false) == true) {
            if (Store.settings.autoDark) AutoDark.check()
            else if (Store.syncWanted && Store.settings.autostart) Engine.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screen) } catch (_: Exception) { }
        screenQueue.shutdown()
        if (!blocked) Engine.stop()
        App.server?.stop(); App.server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context, boot: Boolean = false) {
            ctx.startForegroundService(Intent(ctx, SyncService::class.java).putExtra("boot", boot))
        }
        val serverPort get() = App.server?.port ?: 0
    }
}

/**
 * Opnieuw proberen nadat Android de dienst op de achtergrond niet in de voorgrond liet starten.
 * Een exacte wekker geeft de app even toestemming om dat wel te doen; elke keer wat later, tot 8 keer.
 */
class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { SyncService.start(c, boot = true) } catch (e: Exception) { AppLog.add("Opnieuw starten mislukt: ${e.javaClass.simpleName}"); schedule(c) }
    }

    companion object {
        const val DELAY_S = 60
        @Volatile var tries = 0

        fun schedule(c: Context) {
            if (++tries > 8) { AppLog.add("Dienst start niet op de achtergrond, wacht tot de app geopend wordt"); return }
            val am = c.getSystemService(android.app.AlarmManager::class.java)
            val pi = android.app.PendingIntent.getBroadcast(c, 0, Intent(c, RetryReceiver::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val at = System.currentTimeMillis() + DELAY_S * 1000L * tries
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Na het opstarten van de tv of na een update: weer actief worden (en de sync hervatten als die aan stond)
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) SyncService.start(c, boot = true)
    }
}
