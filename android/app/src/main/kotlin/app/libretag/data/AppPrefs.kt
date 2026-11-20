package app.libretag.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide settings (not per tag). Plain SharedPreferences, excluded from backups like the rest. */
class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    init { app.libretag.keys.Secrets.migrate(listOf("backupSk"), { p.getString(it, null) }, { k, v -> p.edit().putString(k, v).apply() }) }

    private val _changes = MutableStateFlow(0)
    /** bumps on every change, for screens that render settings */
    val changes: StateFlow<Int> = _changes
    private fun bump() { _changes.value = _changes.value + 1 }

    /** "system", "light", "dark" */
    var theme: String
        get() = p.getString("theme", "system")!!
        set(v) { p.edit().putString("theme", v).apply(); bump() }

    /** "metric" or "imperial" */
    var units: String
        get() = p.getString("units", "metric")!!
        set(v) { p.edit().putString("units", v).apply(); bump() }

    /** 0.8 global switches by alert type: read once at start and folded into each tag (LibreTagApp.foldGlobalAlerts) */
    var notifySilent: Boolean
        get() = p.getBoolean("nSilent", true)
        set(v) { p.edit().putBoolean("nSilent", v).apply(); bump() }
    var notifyBattery: Boolean
        get() = p.getBoolean("nBatt", true)
        set(v) { p.edit().putBoolean("nBatt", v).apply(); bump() }
    var notifyZone: Boolean
        get() = p.getBoolean("nZone", true)
        set(v) { p.edit().putBoolean("nZone", v).apply(); bump() }
    var notifyGuard: Boolean
        get() = p.getBoolean("nGuard", true)
        set(v) { p.edit().putBoolean("nGuard", v).apply(); bump() }
    var notifyLostFound: Boolean
        get() = p.getBoolean("nLost", true)
        set(v) { p.edit().putBoolean("nLost", v).apply(); bump() }

    /** home: the list fills the screen (map hidden); remembered */
    var mapCollapsed: Boolean
        get() = p.getBoolean("mapCollapsed", false)
        set(v) { p.edit().putBoolean("mapCollapsed", v).apply() }

    /** Wi-Fi positioning lookup: OFF (default), BEACONDB, GRAPHENEOS_PROXY, APPLE */
    var wifiService: String
        get() = p.getString("wifiService", "OFF")!!
        set(v) { p.edit().putString("wifiService", v).apply(); bump() }
    /**
     * 0.9: when the app looks a no-fix report up by itself: [WIFI_AUTO_TAP] (never, only on tap),
     * [WIFI_AUTO_LOST] (Lost reports), [WIFI_AUTO_ALWAYS] (every new report without GPS). Policy and
     * limits: wifi/WifiAutoLookup.kt. Unset: taken from the old "in Lost" switch (true = lost).
     */
    var wifiAuto: String
        get() = resolveWifiAuto(p.getString("wifiAuto", null), p.getBoolean("wifiAutoLost", false))
        set(v) { p.edit().putString("wifiAuto", v.takeIf { it in WIFI_AUTO_MODES } ?: WIFI_AUTO_TAP).apply(); bump() }

    /** pre-0.9 switch, now a view of [wifiAuto] (Lost reports are looked up in "lost" and "always") */
    var wifiAutoInLost: Boolean
        get() = wifiAuto != WIFI_AUTO_TAP
        set(v) { wifiAuto = if (v) WIFI_AUTO_LOST else WIFI_AUTO_TAP }

    /** the key backup on Nostr: when and to how many relays (the passphrase is never stored) */
    var backupAt: Long
        get() = p.getLong("backupAt", 0)
        set(v) { p.edit().putLong("backupAt", v).apply(); bump() }
    var backupRelays: Int
        get() = p.getInt("backupRelays", 0)
        set(v) { p.edit().putInt("backupRelays", v).apply(); bump() }
    /** the locator and author of the backup, so it can be deleted without the passphrase */
    var backupLocator: String?
        get() = p.getString("backupD", null)
        set(v) { p.edit().putString("backupD", v).apply() }
    var backupSkHex: String?
        get() = p.getString("backupSk", null)?.let { app.libretag.keys.Secrets.unwrap(it) }
        set(v) { p.edit().putString("backupSk", v?.let { app.libretag.keys.Secrets.wrap(it) }).apply() }
    var backupOfferDismissed: Boolean
        get() = p.getBoolean("backupOfferNo", false)
        set(v) { p.edit().putBoolean("backupOfferNo", v).apply() }

    /** when the backup silent-tag alarm is due (ms; Settings shows it) */
    var silenceAlarmAt: Long
        get() = p.getLong("silenceAlarmAt", 0)
        set(v) { p.edit().putLong("silenceAlarmAt", v).apply() }

    var askedBattery: Boolean
        get() = p.getBoolean("askedBattery", false)
        set(v) { p.edit().putBoolean("askedBattery", v).apply() }

    // ---- 0.12 tag diagnosis (PathLog, docs/design/tag-diagnosis.md 4.5) ----
    /** the app was alive and receiving at this time (ms), written on a tick: a restart was blind since */
    var aliveAt: Long
        get() = p.getLong("aliveAt", 0)
        set(v) { p.edit().putLong("aliveAt", v).apply() }
    var blindFrom: Long
        get() = p.getLong("blindFrom", 0)
        set(v) { p.edit().putLong("blindFrom", v).apply() }
    /** the blind stretch (its [blindFrom]) the "can't receive reports" notice went out for */
    var blindNotifiedFor: Long
        get() = p.getLong("blindNotifiedFor", 0)
        set(v) { p.edit().putLong("blindNotifiedFor", v).apply() }
    /** the last time the own node was connected (ms), 0 = never */
    var meshUpAt: Long
        get() = p.getLong("meshUpAt", 0)
        set(v) { p.edit().putLong("meshUpAt", v).apply() }
    /** "Notice my tags over Bluetooth": the background filtered scan for their adverts (on by default) */
    var bleSightings: Boolean
        get() = p.getBoolean("bleSightings", true)
        set(v) { p.edit().putBoolean("bleSightings", v).apply(); bump() }

    /** the last time the app reminded about an old repeater map */
    var repeaterReminderAt: Long
        get() = p.getLong("repRemind", 0)
        set(v) { p.edit().putLong("repRemind", v).apply() }

    companion object {
        const val WIFI_AUTO_TAP = "tap"
        const val WIFI_AUTO_LOST = "lost"
        const val WIFI_AUTO_ALWAYS = "always"
        val WIFI_AUTO_MODES = listOf(WIFI_AUTO_TAP, WIFI_AUTO_LOST, WIFI_AUTO_ALWAYS)

        /** the stored mode, else the pre-0.9 "look up automatically in Lost mode" switch */
        fun resolveWifiAuto(stored: String?, oldInLost: Boolean): String =
            stored?.takeIf { it in WIFI_AUTO_MODES } ?: if (oldInLost) WIFI_AUTO_LOST else WIFI_AUTO_TAP
    }
}
