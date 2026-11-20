package app.libretag.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.Diagnosis
import app.libretag.data.Status
import app.libretag.data.Tracker
import app.libretag.proto.BleCommand
import app.libretag.proto.Command
import app.libretag.proto.LibreTag
import app.libretag.ui.snack
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * While a command waits for a tag that takes commands over Bluetooth (firmware 0.7.7), this phone
 * advertises it (PROTOCOL.md 3.1): one legacy, non-connectable advertising set per tag, 250 ms
 * apart, manufacturer data 0xFFFF with the 21 B payload sealed for the tag's next wake-up that
 * scans ([BleBeaconPlan]). The mesh mailbox runs as before; whichever reaches the tag first is
 * executed, the other is a repeat of the same cmd_seq and only acknowledged again.
 *
 * It stops when the command is acknowledged (CMD_ACK clears it), cancelled, replaced or expired,
 * when Bluetooth is off, or without the Nearby devices permission (Android 12+). The process stays
 * alive in [app.libretag.TrackerService]; the switch to the next check is woken by an alarm, so
 * Doze cannot leave an old sealing on air.
 */
@SuppressLint("MissingPermission") // every call is behind canAdvertise()
class BleCommandBeacon(private val app: LibreTagApp) {

    private class Running(val trackerId: String, val payloadHex: String, val callback: AdvertisingSetCallback) {
        @Volatile var set: AdvertisingSet? = null
    }

    private val running = HashMap<String, Running>()
    private val poke = Channel<Unit>(Channel.CONFLATED)
    /** how many sets this phone runs at once; learned when a start fails with "too many" */
    @Volatile private var capacity = MAX_SETS
    private var rotation = 0

    fun poke() { poke.trySend(Unit) }

    /** From the alarm, on the main thread, while the system holds the device awake for the receiver. */
    fun syncNow() { sync(System.currentTimeMillis()) }

    suspend fun run() {
        app.scope.launch { app.store.trackers.collect { poke() } }
        while (true) {
            val wakeAt = withContext(Dispatchers.Main) { sync(System.currentTimeMillis()) }
            val wait = (wakeAt - System.currentTimeMillis()).coerceIn(1_000L, IDLE_POLL_MS)
            withTimeoutOrNull(wait) { poke.receive() }
        }
    }

    /** One pass: what should be on air, start and stop sets to match. Returns when to look again. */
    private fun sync(now: Long): Long {
        var wakeAt = now + IDLE_POLL_MS
        val wanted = sortedMapOf<String, ByteArray>()
        var anyLate = false
        for (t in app.store.trackers.value) {
            val target = BleBeaconPlan.target(t, Status.checkPlan(t), now) ?: continue
            val payload = payloadFor(t, target) ?: continue
            wanted[t.id] = payload
            target.untilMs?.let { wakeAt = minOf(wakeAt, it) }
            // 0.12: a late tag may have sent n + 1 that we never got: seal for it too
            if (Diagnosis.late(t, now)) {
                anyLate = true
                val ahead = BleBeaconPlan.ahead(t, Status.nextAt(t, now), now) { n1 -> offsetsFor(t, n1) }
                ahead.forEachIndexed { i, a ->
                    payloadFor(t, a)?.let { wanted["${t.id}#${i + 1}"] = it }
                    a.untilMs?.let { wakeAt = minOf(wakeAt, it) }
                }
                wakeAt = minOf(wakeAt, now + LATE_POLL_MS)
            }
        }
        if (wanted.isEmpty() || !canAdvertise(app)) {
            stopAll()
            if (wanted.isNotEmpty()) wakeAt = minOf(wakeAt, now + NO_RADIO_POLL_MS)
            cancelAlarm()
            return wakeAt
        }
        // more tags than advertising sets: take turns
        val ids = wanted.keys.toList()
        val chosen = if (ids.size <= capacity) ids.toSet() else {
            rotation = (rotation + 1) % ids.size
            wakeAt = minOf(wakeAt, now + if (anyLate) ROTATE_LATE_MS else ROTATE_MS)
            (0 until capacity).map { ids[(rotation + it) % ids.size] }.toSet()
        }
        for (id in running.keys.toList()) {
            val r = running[id] ?: continue
            if (id !in chosen || r.payloadHex != LibreTag.bytesToHex(wanted[id]!!)) stop(id)
        }
        for (id in chosen) if (id !in running) start(id, wanted[id]!!)
        scheduleAlarm(wakeAt)
        return wakeAt
    }

    /** the check offsets (s) of report [n] for a quiet stretch near the owner (its heartbeat), null when unknown */
    private fun offsetsFor(t: Tracker, n: Long): List<Long>? {
        val ce = app.chainFor(t)?.cmd(LibreTag.epoch(n)) ?: return null
        return LibreTag.checkOffsets(ce, n, t.intervalMin, t.ownerHbH * 3600L)
    }

    private fun payloadFor(t: Tracker, target: BleTarget): ByteArray? {
        val p = t.pending ?: return null
        val ce = app.chainFor(t)?.cmd(LibreTag.epoch(target.n)) ?: return null
        val cmd = Command.decode(LibreTag.hexToBytes(p.body) ?: return null) ?: return null
        return BleCommand.payload(ce, target.n, cmd, target.k)
    }

    private fun start(trackerId: String, payload: ByteArray) {
        val adv = adapter(app)?.bluetoothLeAdvertiser ?: return
        val hex = LibreTag.bytesToHex(payload)
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM) // 250 ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BleCommand.COMPANY, payload)
            .build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                val r = running[trackerId]
                if (r == null || r.callback !== this) { // stopped meanwhile
                    runCatching { adv.stopAdvertisingSet(this) }
                    return
                }
                if (status == ADVERTISE_SUCCESS) {
                    r.set = set
                    DiagLog.i(TAG, "advertising a command for ${name(trackerId)}: ${hex.take(10)}…")
                    return
                }
                running.remove(trackerId)
                if (status == AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                    capacity = maxOf(1, running.size)
                    DiagLog.w(TAG, "advertising sets: this phone runs $capacity at a time, taking turns")
                    poke()
                } else DiagLog.w(TAG, "advertising a command for ${name(trackerId)} failed: $status")
            }
        }
        running[trackerId] = Running(trackerId, hex, cb)
        runCatching { adv.startAdvertisingSet(params, data, null, null, null, cb) }
            .onFailure { running.remove(trackerId); DiagLog.w(TAG, "startAdvertisingSet: ${it.message}") }
    }

    private fun stop(trackerId: String) {
        val r = running.remove(trackerId) ?: return
        runCatching { adapter(app)?.bluetoothLeAdvertiser?.stopAdvertisingSet(r.callback) }
        DiagLog.i(TAG, "stopped advertising for ${name(trackerId)}")
    }

    private fun stopAll() { running.keys.toList().forEach { stop(it) } }

    private fun name(id: String) = app.store.get(id.substringBefore('#'))?.name?.let { it + id.substringAfter('#', "").let { s -> if (s.isEmpty()) "" else " (n+1 #$s)" } } ?: id

    // ---------------------------------------------------------------- the switch to the next check survives Doze

    private fun alarmIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, ALARM_REQ,
        Intent(c, BleBeaconAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun scheduleAlarm(at: Long) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(app)) }
    }

    private fun cancelAlarm() {
        runCatching { app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app)) }
    }

    companion object {
        private const val TAG = "BleBeacon"
        private const val MAX_SETS = 4
        private const val ROTATE_MS = 8_000L
        /** a late tag's sealings for n + 1 take more sets: turn faster (the tag scans 1.5 s) */
        private const val ROTATE_LATE_MS = 2_000L
        private const val LATE_POLL_MS = 60_000L
        private const val IDLE_POLL_MS = 10 * 60_000L
        private const val NO_RADIO_POLL_MS = 60_000L
        private const val ALARM_REQ = 4244
        private const val PREFS = "ble_beacon"

        fun adapter(c: Context) = c.getSystemService(BluetoothManager::class.java)?.adapter

        fun granted(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

        fun canAdvertise(c: Context): Boolean = granted(c) && adapter(c)?.isEnabled == true

        /**
         * After a command was queued from [activity]: if the tag could take it over Bluetooth but
         * Android 12+ has not granted "Nearby devices", say why once (after [shown], the queued
         * message) and offer the permission. Asked once per install.
         */
        fun offerPermission(activity: AppCompatActivity, root: View, t: Tracker, shown: Snackbar?) {
            if (granted(activity) || !BleBeaconPlan.eligible(t)) return
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("asked", false)) return
            prefs.edit().putBoolean("asked", true).apply()
            val ask = {
                root.snack(activity.getString(R.string.ble_cmd_permission), activity.getString(R.string.ble_cmd_permission_ok), long = true) {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), 4244)
                }
            }
            if (shown == null) { ask(); return }
            shown.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_CONSECUTIVE && !activity.isFinishing) ask()
                }
            })
        }
    }
}

/** Wakes the beacon at the next switch even in Doze (setAndAllowWhileIdle, no exact-alarm permission). */
class BleBeaconAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LibreTagApp.instance.bleBeacon.syncNow()
    }
}
