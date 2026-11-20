package app.libretag.ui

import android.content.Intent
import app.libretag.ble.BleSightScan
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.AppPrefs
import app.libretag.wifi.WifiService
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** Settings, daily things first: look (language, units, theme), notifications, position from Wi-Fi, keys, background, diagnostics. */
class SettingsActivity : ScreenActivity() {
    private val npubInput = NpubInput(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles(getString(R.string.menu_settings))
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        content.removeAllViews()
        val p = app.prefs
        // look
        h3(getString(R.string.set_look))
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "" }
        pad(listItem(R.drawable.ic_globe, languageName(cur), getString(R.string.set_language), icon(R.drawable.ic_chev_r, R.color.ink2)) { languageSheet() })
        sub(getString(R.string.set_units))
        pad(segment(listOf("metric" to R.string.set_metric, "imperial" to R.string.set_imperial), p.units) { p.units = it })
        sub(getString(R.string.set_theme))
        pad(segment(listOf("system" to R.string.set_theme_system, "light" to R.string.set_theme_light, "dark" to R.string.set_theme_dark), p.theme) {
            p.theme = it; app.applyTheme()
        })
        hr()
        // notifications: can they reach the user; which kinds is per tag (Tag settings) and per Android channel
        h3(getString(R.string.set_notifications))
        notificationStatus()
        hr()
        wifi()
        hr()
        // keys
        h3(getString(R.string.set_owner))
        val npub = app.store.ownerNpub
        val end = hbox {
            if (npub != null) {
                addView(iconButton(R.drawable.ic_copy, getString(R.string.a11y_copy_npub)) { copyText("npub", npub); content.snack(getString(R.string.key_copied)) })
                addView(iconButton(R.drawable.ic_qr, getString(R.string.a11y_show_qr)) { qrSheet(npub) })
            }
        }
        pad(listItem(R.drawable.ic_key, npub?.let { it.take(12) + "…" + it.takeLast(6) } ?: getString(R.string.set_owner_none),
            getString(R.string.set_owner_sub), end) { ownerDialog() })
        val bs = app.backup.state.value
        val on = listOfNotNull(
            app.signers.session?.let { getString(R.string.bk_npub_title) },
            if (bs.phrase.on) getString(R.string.bk_phrase_title) else null,
        )
        pad(listItem(R.drawable.ic_cloud, getString(R.string.menu_backup),
            if (on.isEmpty()) getString(R.string.set_backup_sub_none) else getString(R.string.set_backup_sub_on, on.joinToString(", ")),
            icon(R.drawable.ic_chev_r, R.color.ink2)) { startActivity(Intent(this, BackupActivity::class.java)) })
        hr()
        h3(getString(R.string.set_background))
        val pm = getSystemService(android.os.PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        pad(listItem(R.drawable.ic_bolt, getString(if (exempt) R.string.set_bg_ok else R.string.set_bg_needed), getString(R.string.set_bg_sub)) {
            runCatching {
                @android.annotation.SuppressLint("BatteryLife")
                val i = if (exempt) Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            }
        })
        // 0.12: the background filtered Bluetooth scan for the tags' adverts (tag-diagnosis 4.1)
        if (BleSightScan.supported) pad(switchRow(getString(R.string.set_ble_sightings), getString(R.string.set_ble_sightings_sub), p.bleSightings) { on ->
            p.bleSightings = on
            BleSightScan.update(app)
        })
        hr()
        h3(getString(R.string.set_diagnostics))
        pad(listItem(R.drawable.ic_diag, getString(R.string.set_log), getString(R.string.set_log_sub)) { shareLog() })
        silenceCheck()
    }

    /** A small label over a control inside a section. */
    private fun sub(s: String) = content.addView(text(R.style.TextAppearance_LibreTag_LabelLarge, s, R.color.ink2),
        lp().margins(l = dp(16), t = dp(12), r = dp(16), b = dp(4)))

    /** One row: can notifications reach the user (tap: Android's settings), with Preview at its end. */
    private fun notificationStatus() {
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        val ch = getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(LibreTagApp.CH_ALERT)
        val ok = nm.areNotificationsEnabled() && !(ch != null && ch.importance == android.app.NotificationManager.IMPORTANCE_NONE)
        val preview = button(getString(R.string.set_preview), Btn.TEXT, R.drawable.ic_bell) { app.previewNotification() }
        pad(listItem(if (ok) R.drawable.ic_check else R.drawable.ic_alert,
            getString(if (ok) R.string.set_notif_allowed else R.string.set_notif_blocked), getString(R.string.set_notif_status_sub), preview) {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }
        })
    }

    /** Diagnostics: when the backup silent-tag check last ran and is next due. */
    private fun silenceCheck() {
        val last = app.store.lastSilenceCheck
        val next = app.prefs.silenceAlarmAt.takeIf { it > System.currentTimeMillis() }
        val nextText = next?.let { Format.clock(this, it) } ?: "?"
        pad(listItem(R.drawable.ic_clock, getString(R.string.set_silence_check),
            if (last > 0) getString(R.string.set_silence_check_sub, Format.clock(this, last), nextText)
            else getString(R.string.set_silence_check_never, nextText)))
    }

    /**
     * Position from Wi-Fi as two questions: which service, and when the app looks a report up
     * ([AppPrefs.wifiAuto]). Each service says what it means; one summary line says what leaves the
     * phone. "Look up" stays visible but greyed while the service is Off. Tor is not offered yet.
     */
    private fun wifi() {
        val p = app.prefs
        h3(getString(R.string.wifi2_title))
        sub(getString(R.string.wifi2_service))
        val svc = WifiPositioning.service()
        for (s in WifiService.entries) pad(radioRow(WifiPositioning.serviceName(this, s), getString(when (s) {
            WifiService.OFF -> R.string.wifi2_off_sub
            WifiService.BEACONDB -> R.string.wifi2_beacondb_sub
            WifiService.GRAPHENEOS_PROXY -> R.string.wifi2_grapheneos_sub
            WifiService.APPLE -> R.string.wifi2_apple_sub
        }), s == svc) { if (s != svc) chooseWifi(s) })
        val off = svc == WifiService.OFF
        sub(getString(R.string.wifi2_when))
        val mode = p.wifiAuto
        for ((m, l) in listOf(AppPrefs.WIFI_AUTO_TAP to R.string.wifi2_tap, AppPrefs.WIFI_AUTO_LOST to R.string.wifi2_lost, AppPrefs.WIFI_AUTO_ALWAYS to R.string.wifi2_always))
            pad(radioRow(getString(l), null, m == mode, enabled = !off) { if (m != mode) { p.wifiAuto = m; build() } })
        if (off) return
        val name = WifiPositioning.serviceName(this, svc)
        para(when (mode) {
            AppPrefs.WIFI_AUTO_LOST -> getString(R.string.wifi2_sum_lost, name) + "\n" + getString(R.string.wifi2_sum_places)
            AppPrefs.WIFI_AUTO_ALWAYS -> getString(R.string.wifi2_sum_always, name) + "\n" + getString(R.string.wifi2_sum_places)
            else -> getString(R.string.wifi2_sum_tap)
        })
    }

    private fun radioRow(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onPick: () -> Unit): View {
        val rb = MaterialRadioButton(this).apply { isChecked = checked; isClickable = false; isFocusable = false; isEnabled = enabled }
        val row = listItem(null, title, sub, null, if (enabled) onPick else null)
        row.addView(rb, 0, lp(WRAP, WRAP).margins(r = dp(12)))
        if (!enabled) row.alpha = 0.5f
        row.contentDescription = listOfNotNull(title, sub).joinToString(". ")
        row.isSelected = checked
        return row
    }

    private fun segment(items: List<Pair<String, Int>>, cur: String, onPick: (String) -> Unit): View {
        val g = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val ids = HashMap<Int, String>()
        for ((v, l) in items) {
            val b = segmentButton(getString(l))
            ids[b.id] = v
            g.addView(b)
            if (v == cur) g.check(b.id)
        }
        g.addOnButtonCheckedListener { _, id, checked -> if (checked) ids[id]?.let(onPick) }
        return hbox { addView(g) }
    }

    /** The one consent: when a service is chosen, data starts leaving the phone. */
    private fun chooseWifi(s: WifiService) {
        if (s == WifiService.OFF) { app.prefs.wifiService = s.name; build(); return }
        val name = WifiPositioning.serviceName(this, s)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wifi_disclosure_title, name))
            .setMessage(getString(R.string.wifi2_consent, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wifi_disclosure_ok) { _, _ -> app.prefs.wifiService = s.name; build() }
            .show()
    }

    private val languages = listOf("" to 0, "en" to 0, "sk" to 0, "cs" to 0, "de" to 0, "es" to 0, "nl" to 0)

    private fun languageName(tag: String): String = when (tag.take(2)) {
        "" -> getString(R.string.set_language_system)
        "en" -> "English"
        "sk" -> "Slovenčina"
        "cs" -> "Čeština"
        "de" -> "Deutsch"
        "es" -> "Español"
        "nl" -> "Nederlands"
        else -> tag
    }

    private fun languageSheet() {
        val s = Sheet(this, getString(R.string.set_language))
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags().take(2)
        for ((tag, _) in languages) {
            val rb = MaterialRadioButton(this).apply { isChecked = tag == cur; isClickable = false }
            val row = listItem(null, languageName(tag)) {
                s.dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
            }
            row.addView(rb, 0, lp(WRAP, WRAP).margins(r = dp(12)))
            s.body.addView(row)
        }
        s.show()
    }

    private fun qrSheet(npub: String) {
        val s = Sheet(this, getString(R.string.set_owner))
        s.body.addView(qrImage("nostr:$npub"))
        s.body.addView(text(R.style.TextAppearance_LibreTag_Status, npub, R.color.ink2).apply { gravity = android.view.Gravity.CENTER; setTextIsSelectable(true) })
        s.foot(button(getString(R.string.a11y_copy_npub), Btn.OUTLINED, R.drawable.ic_copy) { copyText("npub", npub); s.dialog.dismiss() },
            button(getString(R.string.done)) { s.dialog.dismiss() })
        s.show()
    }

    private fun ownerDialog() {
        val edit = TextInputEditText(this).apply { setText(app.store.ownerNpub.orEmpty()) }
        val box = vbox {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(npubInput.field(getString(R.string.npub_hint), edit))
            addView(text(R.style.TextAppearance_LibreTag_BodySmall, getString(R.string.set_owner_help)), lp().margins(t = dp(8)))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_owner)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                when (val n = NpubInput.parse(edit.text)) {
                    null -> app.store.ownerNpub = null
                    "" -> content.snack(getString(R.string.npub_invalid))
                    else -> { app.store.ownerNpub = n; content.snack(getString(R.string.saved)) }
                }
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The diagnostic log (no positions in it beyond what DiagLog writes) via the share sheet. */
    private fun shareLog() {
        val f = getExternalFilesDir(null)?.resolve("libretag.log")
        if (f == null || !f.exists()) { content.snack(getString(R.string.set_log_empty)); return }
        lifecycleScope.launch {
            val uri = FileProvider.getUriForFile(this@SettingsActivity, "$packageName.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), getString(R.string.set_log)))
        }
    }
}
