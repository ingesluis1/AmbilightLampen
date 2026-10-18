package nl.stacklines.tvambilight

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

/**
 * Tv-scherm. De inhoud is tv.html van de eigen webserver, zodat tv en telefoon
 * dezelfde tekenmotor (room.js) en stijl delen.
 *
 * Robuust opstarten: wachten tot de webserver er is (na een crash herstart Android de dienst soms met vertraging),
 * opnieuw laden als laden mislukt, en de ingebouwde browser opnieuw aanmaken als die crasht. Fouten gaan naar de maker.
 */
class MainActivity : Activity() {
    private var web: WebView? = null
    private val ui = Handler(Looper.getMainLooper())
    /** Hoe vaak de ingebouwde browser al opnieuw is aangemaakt; daarna een melding in plaats van eindeloos opnieuw. */
    private var rebuilds = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncService.start(this)
        createWebView()
        waitForServer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val w = WebView(this).apply {
            setBackgroundColor(0xFF1D1124.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    Reporter.screenProblem("Tv-pagina laadt niet: ${error.description} (${error.errorCode})")
                    ui.postDelayed({ waitForServer() }, 1500)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // Zonder dit sluit Android de hele app; nu een nieuwe browser en opnieuw laden
                    Reporter.screenProblem("Ingebouwde browser gestopt (crash: ${detail.didCrash()}, keer ${rebuilds + 1})")
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    web = null
                    if (++rebuilds <= 3) { createWebView(); waitForServer() } else showFallback()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                        Reporter.screenProblem("Tv-pagina: ${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})")
                    return true
                }
            }
        }
        web = w
        setContentView(w)
    }

    /** Laadt de pagina zodra de webserver draait; blijft wachten (ook als dat na een crash even duurt). */
    private fun waitForServer() {
        val w = web ?: return
        val port = SyncService.serverPort
        if (port > 0) {
            // Testpagina's (bijvoorbeeld calib.html voor de zonevolgorde) via adb: --es page calib.html
            val page = intent?.getStringExtra("page")?.takeIf { it.matches(Regex("""[a-z]+\.html""")) } ?: "tv.html"
            w.loadUrl("http://127.0.0.1:$port/$page")
            w.requestFocus()
        } else {
            SyncService.start(this)
            ui.postDelayed({ waitForServer() }, 300)
        }
    }

    /** De browser blijft crashen: dan in elk geval uitleggen dat de lampen en de telefoon wel werken. */
    private fun showFallback() {
        setContentView(TextView(this).apply {
            setBackgroundColor(0xFF1D1124.toInt()); setTextColor(0xFFF5DEB3.toInt()); textSize = 22f; gravity = Gravity.CENTER
            setPadding(80, 80, 80, 80)
            text = tr("Het tv-scherm van Ambilight Lampen start niet. De lampen en de telefoonpagina werken wel.\n\nDe maker heeft een melding gekregen. Druk op Terug en open de app opnieuw.",
                "The Ambilight Lampen TV screen doesn't start. The lamps and the phone page still work.\n\nThe maker has been notified. Press Back and open the app again.")
        })
    }

    override fun onResume() {
        super.onResume()
        // Bij openen van de app meteen kijken of er een nieuwe versie is
        Thread { Updater.check(maxAgeMs = 10 * 60_000L) }.start()
    }

    override fun onPause() {
        super.onPause()
        web?.evaluateJavascript("window.onHide && window.onHide()", null)
    }

    /** Terug in beeld: de browser is bij het weggaan vrijgegeven, dus opnieuw aanmaken en laden. */
    override fun onStart() {
        super.onStart()
        if (web == null && rebuilds <= 3) { createWebView(); waitForServer() }
    }

    /**
     * Uit beeld (film kijken): de ingebouwde browser vrijgeven. Het geheugen van de tv is krap en anders
     * sluit Android bij tekort de hele app, ook de sync.
     */
    override fun onStop() {
        super.onStop()
        val w = web ?: return
        web = null
        ui.removeCallbacksAndMessages(null)
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val w = web ?: run { finish(); return true }
            // Eerst de pagina laten beslissen (bijvoorbeeld het instellingenpaneel sluiten)
            w.evaluateJavascript("window.onBack ? window.onBack() : false") { r -> if (r != "true") finish() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
