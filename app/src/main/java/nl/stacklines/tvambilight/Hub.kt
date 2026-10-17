package nl.stacklines.tvambilight

import org.json.JSONObject

/**
 * Lampen achter een hub (Hue Bridge, IKEA DIRIGERA): de app stuurt elke lamp via de hub aan.
 * De lamp-lus in TuyaLamp is voor alle hubs gelijk; dit zijn de stukken die per hub verschillen.
 */
interface Hub {
    /** Begin van de lamp-id ("hue-", "ikea-"); de rest is de id van de lamp op de hub. */
    val prefix: String

    /** Huidige stand van een lamp als Tuya-functies (20..24), of null als de hub niet antwoordt. */
    fun light(id: String): JSONObject?

    /** Kan de hub deze lamp bereiken? null = onbekend. */
    fun reachable(id: String): Boolean?

    /** Wacht op een vrij moment bij de hub (gedeeld door alle lampen van die hub). */
    fun waitTurn()

    /** Stuurt Tuya-functies naar een lamp, met overgangstijd ms. false = de hub heeft het druk, later opnieuw. */
    fun setLight(id: String, dps: Map<String, Any>, mode: String, category: String, ms: Long): Boolean

    /** Snelle stream (alleen Hue Entertainment): neemt de opdracht over, of false. */
    fun offer(lampId: String, dps: Map<String, Any>, mode: String): Boolean = false

    /** Loopt de stream voor deze hub (dan geen losse statusvragen)? */
    fun streaming(): Boolean = false

    /** Vlak voor een gewone opdracht (Hue: een stream eerst laten stoppen, anders negeert de Bridge hem). */
    fun beforeSet(lampId: String) {}
}
