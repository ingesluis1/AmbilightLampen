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
        Store.load(this)
        Discovery.start(this) { id, ip, version ->
            if (!ip.matches(WebServer.IPV4)) return@start
            val l = Store.lampsSnapshot().firstOrNull { it.id == id } ?: return@start
            if (l.ip != ip || l.version != version) {
                l.ip = ip; l.version = version
                Store.save(); Engine.syncWorkers()
            }
        }
        Wiz.startBackground()
        Hue.startBackground()
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
    /** Scherm uit/aan na elkaar afhandelen, in volgorde en niet op de hoofdthread (stoppen kan even duren). */
    private val screenQueue = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val action = i.action
            screenQueue.execute {
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> if (Engine.running) { pausedByScreen = true; Engine.stop() }
                    Intent.ACTION_SCREEN_ON -> if (pausedByScreen) { pausedByScreen = false; if (Store.syncWanted) Engine.start() }
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
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
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
        if (intent?.getBooleanExtra("boot", false) == true) {
            if (Store.syncWanted && Store.settings.autostart) Engine.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screen) } catch (_: Exception) { }
        screenQueue.shutdown()
        Engine.stop()
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Na het opstarten van de tv of na een update: weer actief worden (en de sync hervatten als die aan stond)
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) SyncService.start(c, boot = true)
    }
}
