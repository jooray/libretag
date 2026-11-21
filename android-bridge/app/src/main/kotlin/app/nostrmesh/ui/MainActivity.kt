package app.nostrmesh.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.nostrmesh.BridgeApp
import app.nostrmesh.R
import app.nostrmesh.bridge.Activity
import app.nostrmesh.bridge.Bridge
import app.nostrmesh.databinding.ActivityMainBinding
import app.nostrmesh.databinding.HeaderMainBinding
import app.nostrmesh.databinding.ItemActivityBinding
import io.github.jooray.meshcore.localapi.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val app get() = BridgeApp.instance
    private lateinit var header: HeaderMainBinding
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        b.list.layoutManager = LinearLayoutManager(this) // before the header: it takes its layout params
        header = HeaderMainBinding.inflate(layoutInflater, b.list, false)
        b.list.adapter = adapter

        header.statHeard.label.setText(R.string.stat_heard)
        header.statPublished.label.setText(R.string.stat_published)
        header.statToRadio.label.setText(R.string.stat_to_radio)
        header.statDropped.label.setText(R.string.stat_dropped)

        header.enabled.isChecked = app.settings.enabled
        header.enabled.setOnCheckedChangeListener { _, on ->
            if (on) { askNotifications(); askBatteryExemption() }
            app.settings.enabled = on
            renderSwitch()
        }
        renderSwitch()
        header.openMeshCore.setOnClickListener {
            packageManager.getLaunchIntentForPackage("com.meshcore.meshcore_open")?.let { startActivity(it) }
        }
        lifecycleScope.launch {
            val fp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.mesh.fingerprint }
            header.fingerprint.text = "Odtlačok pre MeshCore Open: $fp"
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.mesh.state.collect { renderMesh(it) } }
                launch { app.relays.connected.collect { (c, n) -> renderRelays(c, n) } }
                launch { app.bridge.stats.collect { renderStats(it) } }
                launch { app.bridge.activity.collect { adapter.items = it; header.empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE } }
                launch { app.settings.version.collect { renderModes(); renderMesh(app.mesh.state.value) } }
                launch { while (true) { delay(30_000); adapter.notifyItemRangeChanged(1, adapter.items.size) } }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Doze would cut the relay and MeshCore Open sockets: ask once to be left alone (needs the user's yes). */
    private fun askBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun renderSwitch() {
        header.enabled.setText(if (app.settings.enabled) R.string.bridge_running else R.string.bridge_off)
    }

    private fun dot(view: android.widget.ImageView, color: Int) {
        view.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun renderMesh(s: SessionState) {
        header.meshState.text = app.meshText(s)
        dot(header.meshDot, when (s) {
            is SessionState.Connected -> if (s.unknownRegion == null) R.color.ok else R.color.warn
            SessionState.Connecting, SessionState.WaitingForApproval, SessionState.WaitingForChannel -> R.color.warn
            SessionState.Disconnected -> if (app.settings.enabled) R.color.bad else R.color.idle
            else -> R.color.bad
        })
        header.fingerprint.visibility = if (s == SessionState.WaitingForApproval || s == SessionState.NotApproved) View.VISIBLE else View.GONE
    }

    private fun renderRelays(c: Int, n: Int) {
        header.relayState.text = if (n == 0) "Nostr relays: vypnuté" else "Nostr relays: $c z $n pripojené"
        dot(header.relayDot, when {
            n == 0 -> R.color.idle
            c == n -> R.color.ok
            c > 0 -> R.color.warn
            else -> R.color.bad
        })
    }

    private fun renderStats(s: Bridge.Stats) {
        header.statHeard.value.text = s.heard.toString()
        header.statPublished.value.text = s.published.toString()
        header.statToRadio.value.text = if (s.queued > 0) "${s.toRadio}+${s.queued}" else s.toRadio.toString()
        header.statDropped.value.text = s.dropped.toString()
    }

    private fun renderModes() {
        val st = app.settings
        val devs = st.devices.size
        header.modes.text = buildString {
            val p = st.uplinkProfiles
            val what = listOfNotNull(
                "Roadstr".takeIf { p.any { it.startsWith("road.") } },
                "LibreTag".takeIf { "libretag" in p },
                "správy zariadení".takeIf { p.any { it.startsWith("dm4.") } },
                "poznámky".takeIf { "note" in p },
                "ostatné".takeIf { "generic" in p },
            )
            append("Na Nostr: ")
            append(what.joinToString(", ").ifEmpty { "nič" })
            append("\nDM do zariadení: ")
            append(if (st.downlinkDm) "zapnuté ($devs ${if (devs == 1) "zariadenie" else "zariadení"})" else "vypnuté")
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Activity> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) object : RecyclerView.ViewHolder(header.root) {}
            else Row(ItemActivityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is Row) holder.bind(items[position - 1])
        }
    }

    private inner class Row(val b: ItemActivityBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: Activity) {
            val (sym, color) = when (a.direction) {
                Activity.Direction.UP -> "↑" to R.color.ok
                Activity.Direction.DOWN -> "↓" to R.color.brand
                Activity.Direction.DROP -> "✕" to R.color.bad
                Activity.Direction.INFO -> "•" to R.color.idle
            }
            b.arrow.text = sym
            b.arrow.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, color))
            b.text.text = a.text.ifEmpty { a.profile }
            b.meta.text = "${a.profile} · ${DateUtils.getRelativeTimeSpanString(a.atMs, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)}"
        }
    }
}
