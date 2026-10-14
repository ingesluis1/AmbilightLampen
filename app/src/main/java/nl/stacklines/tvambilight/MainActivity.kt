package nl.stacklines.tvambilight

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Tv-scherm. De inhoud is tv.html van de eigen webserver, zodat tv en telefoon
 * dezelfde tekenmotor (room.js) en stijl delen.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncService.start(this)
        web = WebView(this).apply {
            setBackgroundColor(0xFF1D1124.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            isFocusable = true
        }
        setContentView(web)
        waitForServer(0)
    }

    private fun waitForServer(tries: Int) {
        val port = SyncService.serverPort
        if (port > 0) {
            // Testpagina's (bijvoorbeeld calib.html voor de zonevolgorde) via adb: --es page calib.html
            val page = intent?.getStringExtra("page")?.takeIf { it.matches(Regex("""[a-z]+\.html""")) } ?: "tv.html"
            web.loadUrl("http://127.0.0.1:$port/$page")
            web.requestFocus()
        } else if (tries < 50) {
            ui.postDelayed({ waitForServer(tries + 1) }, 100)
        }
    }

    override fun onResume() {
        super.onResume()
        // Bij openen van de app meteen kijken of er een nieuwe versie is
        Thread { Updater.check(force = true) }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Eerst de pagina laten beslissen (bijvoorbeeld het instellingenpaneel sluiten)
            web.evaluateJavascript("window.onBack ? window.onBack() : false") { r -> if (r != "true") finish() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
