package nl.stacklines.tvambilight

/**
 * Per app: de gewone instellingen een kant op duwen, passend bij wat je kijkt. Film = rustiger en vloeiender,
 * game = snel en direct, sfeer = zacht en traag. "" = gewoon (de instellingen zoals ze zijn).
 */
object Profiles {
    val IDS = listOf("", "film", "game", "calm")

    /** Kopie van de instellingen met het profiel erover. */
    fun apply(b: Settings, p: String): Settings {
        val s = Settings(); s.apply(b.toJson())
        when (p) {
            "film" -> { s.smooth = (b.smooth * 0.6).coerceIn(0.05, 1.0); s.fade = true }
            "game" -> { s.smooth = maxOf(b.smooth * 1.8, 0.6).coerceAtMost(1.0); s.hz = 25; s.fade = false; s.cuts = true }
            "calm" -> {
                s.smooth = (b.smooth * 0.35).coerceIn(0.05, 1.0); s.fade = true; s.cuts = false
                s.saturation = (b.saturation * 0.8).coerceAtLeast(0.5); s.brightness = (b.brightness * 0.8).coerceAtLeast(0.3)
            }
        }
        return s
    }

    /** Profiel van de app die nu op de tv staat ("" = gewoon of onbekend). */
    fun current(): String = Platform.currentApp()?.let { Store.appProfiles[it] } ?: ""
}
