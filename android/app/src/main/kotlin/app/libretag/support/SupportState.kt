package app.libretag.support

import app.libretag.support.SupportEngine.State
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * The stored engine state (SharedPreferences `support_prompt`, key `support.v1`) as one JSON value.
 * Anything unreadable is a fresh start: the worst case is one ask too many, never a crash.
 */
object SupportState {
    @Serializable
    private data class Stored(
        val activeDays: Int = 0,
        val lastActiveDay: String? = null,
        val actions: Int = 0,
        val asks: Int = 0,
        val lastAskDay: String? = null,
        val supporter: Boolean = false,
        val declined: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: State): String = json.encodeToString(
        Stored.serializer(),
        Stored(s.activeDays, s.lastActiveDay, s.actions, s.asks, s.lastAskDay, s.supporter, s.declined),
    )

    fun decode(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        return try {
            val d = json.decodeFromString(Stored.serializer(), raw)
            State(d.activeDays, d.lastActiveDay, d.actions, d.asks, d.lastAskDay, d.supporter, d.declined)
        } catch (e: Exception) {
            State()
        }
    }
}

/** The checkout page, opened in the browser with ACTION_VIEW by the UI. Never a request of its own. */
object SupportCheckout {
    private const val CHECKOUT = "https://hackyourself.io/support/s/libre-tag"

    /** Under the command block once the tag confirmed a command. */
    const val REF_COMMAND_CONFIRMED = "command-confirmed"

    /** Last step of the Add tag wizard. */
    const val REF_PAIRED = "paired"

    /** The permanent "Support the project" menu entry. */
    const val REF_MENU = "menu"

    /** lang, theme, ref and nothing else (no id, no key, no version). Languages other than sk/cs fall back to en. */
    fun url(ref: String, dark: Boolean, language: String): String {
        val lang = language.lowercase().take(2).let { if (it == "sk" || it == "cs") it else "en" }
        val theme = if (dark) "dark" else "light"
        return "$CHECKOUT?lang=$lang&theme=$theme&ref=${URLEncoder.encode(ref, "UTF-8")}"
    }
}
