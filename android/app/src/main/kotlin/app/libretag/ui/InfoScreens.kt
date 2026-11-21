package app.libretag.ui

import app.libretag.Reach
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.BuildConfig
import app.libretag.R
import io.github.jooray.meshcore.localapi.SessionState
import app.libretag.support.SupportAsk
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A small status tag ("working", "off"). */
fun android.content.Context.statusTag(label: String, ok: Boolean): View = text(R.style.TextAppearance_LibreTag_LabelSmall, label).apply {
    background = rounded(if (ok) androidx.core.graphics.ColorUtils.setAlphaComponent(col(R.color.ok), 46)
        else androidx.core.graphics.ColorUtils.setAlphaComponent(col(R.color.warn), 77), 4f)
    setPadding(dp(8), dp(3), dp(8), dp(3))
    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.archivo_narrow_bold)
}

/** Connections: MeshCore Open, the relays (live), bridges seen. */
class ConnectionsActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles(getString(R.string.menu_connections))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { render(); delay(2_000) }
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        h3(getString(R.string.conn_meshcore))
        val s = app.mesh.state.value
        val ok = s is SessionState.Connected
        val last = app.lastMeshPacket.value
        val sub = when (s) {
            is SessionState.Connected -> listOfNotNull(s.deviceName?.let { getString(R.string.conn_node, it) }, getString(R.string.conn_local_api),
                if (last > 0) getString(R.string.conn_last_packet, Format.at(this, last)) else null).joinToString(" · ")
            SessionState.Connecting -> getString(R.string.mesh_connecting)
            SessionState.Disconnected -> getString(R.string.mesh_disconnected)
            SessionState.WaitingForApproval -> getString(R.string.mesh_approve)
            SessionState.NotApproved -> getString(R.string.mesh_not_approved)
            SessionState.NoRadio -> getString(R.string.mesh_no_radio)
            SessionState.WaitingForChannel -> getString(R.string.mesh_add_channel)
            SessionState.ChannelDenied -> getString(R.string.mesh_channel_denied)
            is SessionState.ChannelMissing -> getString(R.string.mesh_channel_missing, s.code ?: "?")
            is SessionState.Refused -> getString(R.string.mesh_refused, s.code ?: "?")
            is SessionState.Failed -> getString(R.string.mesh_failed, s.message)
        }
        pad(listItem(R.drawable.ic_mesh, getString(if (ok) R.string.conn_connected else R.string.conn_not_connected), sub,
            statusTag(getString(if (ok) R.string.conn_working else R.string.conn_off), ok)))
        val row = FlowRow(this)
        row.addView(button(getString(R.string.conn_open_meshcore), Btn.OUTLINED, R.drawable.ic_open) {
            packageManager.getLaunchIntentForPackage("com.meshcore.meshcore_open")?.let { startActivity(it) }
                ?: content.snack(getString(R.string.conn_meshcore_missing))
        })
        row.addView(button(getString(R.string.conn_fingerprint), Btn.TEXT) {
            lifecycleScope.launch {
                // the first call loads or creates the key: off the main thread
                val fp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { app.mesh.fingerprint() }
                MaterialAlertDialogBuilder(this@ConnectionsActivity).setTitle(R.string.conn_fingerprint)
                    .setMessage(getString(R.string.conn_fingerprint_text, fp))
                    .setPositiveButton(R.string.ok, null).show()
            }
        })
        pad(row)
        hr()
        h3(getString(R.string.conn_relays))
        val st = app.relays.status.value
        val now = System.currentTimeMillis()
        for (url in app.store.relays) {
            val r = st[url]
            val working = r?.connected == true
            val sub2 = when {
                working -> getString(R.string.relay_working, Format.agoLong(this, r!!.since, now)) + (r.rttMs?.let { " · $it ms" } ?: "")
                r?.lastErrorAt != null && r.lastErrorAt > 0 -> getString(R.string.relay_failing, Format.agoLong(this, r.lastErrorAt, now))
                app.reach.value == Reach.OFFLINE -> getString(R.string.conn_offline)
                else -> getString(R.string.relay_connecting)
            }
            val host = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
            pad(listItem(R.drawable.ic_cloud, host, sub2, hbox {
                addView(statusTag(getString(if (working) R.string.conn_working else R.string.conn_not_working), working))
                addView(iconButton(R.drawable.ic_trash, getString(R.string.relay_remove, host), R.color.ink2) { removeRelay(url) })
            }))
        }
        pad(hbox { addView(button(getString(R.string.relay_add), Btn.OUTLINED, R.drawable.ic_plus) { addRelay() }) })
        hr()
        h3(getString(R.string.conn_bridges))
        val recent = app.bridgeReports.value.count { now - it < 24 * 3_600_000L }
        pad(listItem(R.drawable.ic_sync, if (app.reach.value == Reach.OFFLINE) getString(R.string.bridges_offline)
            else resources.getQuantityString(R.plurals.bridges_reports, recent, recent), getString(R.string.bridges_sub)))
    }

    private fun addRelay() {
        val e = TextInputEditText(this).apply { setText("wss://") }
        MaterialAlertDialogBuilder(this).setTitle(R.string.relay_add)
            .setView(vbox { setPadding(dp(24), dp(8), dp(24), 0); addView(OutlinedField(this@ConnectionsActivity).apply { hint = getString(R.string.relay_url); addView(e) }) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val u = e.text.toString().trim().trimEnd('/')
                if (!u.startsWith("wss://") || u.length < 9) { content.snack(getString(R.string.relay_bad)); return@setPositiveButton }
                app.store.relays = (app.store.relays + u).distinct()
                app.relays.setRelays(app.store.relays, app.store.relays)
                render()
            }.show()
    }

    private fun removeRelay(url: String) {
        if (app.store.relays.size <= 1) { content.snack(getString(R.string.relay_last)); return }
        app.store.relays = app.store.relays - url
        app.relays.setRelays(app.store.relays, app.store.relays)
        render()
    }
}

/** The public repeater map used for areas without GPS: age, size, update. */
class RepeaterMapActivity : ScreenActivity() {
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles(getString(R.string.menu_repeaters))
        render()
    }

    private fun render() {
        content.removeAllViews()
        val db = app.repeaters
        val now = System.currentTimeMillis()
        val have = db.downloadedAt > 0
        val c = card(if (have) getString(R.string.rep_downloaded, Format.agoLong(this, db.downloadedAt, now)) else getString(R.string.rep_not_downloaded), R.drawable.ic_rpt) {
            val size = app.filesDir.resolve("repeaters.json").length()
            addView(text(R.style.TextAppearance_LibreTag_BodyMedium,
                if (have) getString(R.string.rep_count, java.text.NumberFormat.getInstance().format(db.all().size), android.text.format.Formatter.formatShortFileSize(this@RepeaterMapActivity, size))
                else getString(R.string.rep_need), R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
            if (busy) addView(LinearProgressIndicator(this@RepeaterMapActivity).apply { isIndeterminate = true }, lp().margins(b = dp(12)))
            addView(hbox { addView(button(getString(if (busy) R.string.rep_downloading else if (have) R.string.rep_update else R.string.rep_download), Btn.FILLED, R.drawable.ic_download) { download() }.apply { isEnabled = !busy }) })
            if (have && now - db.downloadedAt > 30 * 86_400_000L) addView(text(R.style.TextAppearance_LibreTag_Status, getString(R.string.rep_old), R.color.ink), lp().margins(t = dp(8)))
        }
        content.addView(c)
        para(getString(R.string.rep_text1))
        para(getString(R.string.rep_text2))
        para(getString(R.string.rep_text3))
    }

    private fun download() {
        busy = true; render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.repeaters.download() } }
            busy = false
            app.repeatersVersion.value = System.currentTimeMillis()
            app.scope.launch { app.ensureAllEstimates() }
            render()
            r.fold({ content.snack(getString(R.string.rep_done, java.text.NumberFormat.getInstance().format(it))) },
                { content.snack(getString(R.string.rep_failed, it.message ?: "")) })
        }
    }
}

/** About: what LibreTag is, version, licences, support. */
class AboutActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles(getString(R.string.menu_about))
        content.addView(hbox {
            addView(ImageView(this@AboutActivity).apply { setImageDrawable(BlazeDrawable(this@AboutActivity, "yellow", "stripe")) }, LinearLayout.LayoutParams(dp(37), dp(44)))
            addView(vbox {
                addView(text(R.style.TextAppearance_LibreTag_TitleLarge, getString(R.string.app_name)))
                addView(text(R.style.TextAppearance_LibreTag_Status, getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.FIRMWARE_LATEST), R.color.ink3))
            }, lp(WRAP, WRAP).margins(l = dp(14)))
        }, lp().margins(l = dp(16), t = dp(16), r = dp(16), b = dp(8)))
        para(getString(R.string.about_text1))
        para(getString(R.string.about_text2))
        para(getString(R.string.about_text3))
        pad(FlowRow(this).apply {
            addView(button(getString(R.string.menu_support), Btn.TONAL, R.drawable.ic_heart) { SupportAsk.openFromMenu(this@AboutActivity) })
            addView(button(getString(R.string.menu_get_tag), Btn.OUTLINED, R.drawable.ic_chip) { TagSheets.getTag(this@AboutActivity) })
        })
        hr()
        h3(getString(R.string.about_licences))
        para(getString(R.string.about_licences_text))
        pad(button(getString(R.string.about_font_licence), Btn.TEXT) {
            val txt = resources.openRawResource(R.raw.ofl_archivo).bufferedReader().readText()
            MaterialAlertDialogBuilder(this).setTitle(R.string.about_font_licence).setMessage(txt).setPositiveButton(R.string.close, null).show()
        }.let { b -> hbox { gravity = Gravity.START; addView(b) } })
    }
}
