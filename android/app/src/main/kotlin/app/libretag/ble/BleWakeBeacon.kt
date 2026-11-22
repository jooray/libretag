package app.libretag.ble

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.data.Tracker
import app.libretag.proto.BleConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * "Open Bluetooth" for a tag next to the phone, without the report counter (firmware 0.9.5): while
 * a [WakeRequest] waits, this phone advertises the wake advert ([BleConsole.wakePayload]), one
 * legacy, non-connectable advertising set per tag with a fresh salt each time the set starts. The
 * tag scans at every wake-up; seeing the advert it opens its console window and advertises it
 * (type 0x25), which the console scan recognises. Then the set stops, the screen that is open
 * connects ([app.libretag.ui.BleConsoleHelper]), and without one a notification offers it.
 *
 * The n-bound BLE_CONSOLE command ([BleCommandBeacon]) and the mesh routes run alongside; this
 * advert is what still works when the app has missed the tag's reports (a new ratchet epoch).
 * Advertising calls run on the main thread.
 */
@SuppressLint("MissingPermission") // every start is behind BleCommandBeacon.canAdvertise()
class BleWakeBeacon(private val app: LibreTagApp) {
    private val _requests = MutableStateFlow<Map<String, WakeRequest>>(emptyMap())
    val requests: StateFlow<Map<String, WakeRequest>> = _requests
    /** tag id -> the time the phone gave up waiting, for the screen */
    private val _missed = MutableStateFlow<Map<String, Long>>(emptyMap())
    val missed: StateFlow<Map<String, Long>> = _missed

    private val onAir = HashMap<String, AdvertisingSetCallback>()
    private val poke = Channel<Unit>(Channel.CONFLATED)
    private val random = SecureRandom()
    private var scanHeld = false
    /** screens with a [app.libretag.ui.BleConsoleHelper] in front; without one an answer is a notification */
    @Volatile var screens = 0

    fun waiting(id: String): WakeRequest? = _requests.value[id]?.takeIf { it.waiting }
    fun answered(id: String): Boolean = _requests.value[id]?.answeredAt != null
    /** the app asked this tag to open its window: its console advert is no restart */
    fun asked(id: String): Boolean = id in _requests.value

    fun start(t: Tracker) {
        val r = BleWakePlan.request(t, System.currentTimeMillis())
        _requests.update { it + (t.id to r) }
        _missed.update { it - t.id }
        DiagLog.i(TAG, "${t.name}: waiting for its next wake-up, up to ${(r.untilMs - r.startedAt) / 60_000} min")
        poke.trySend(Unit)
    }

    fun cancel(id: String) {
        if (_requests.value[id] == null) return
        _requests.update { it - id }
        DiagLog.i(TAG, "${name(id)}: stopped waiting")
        app.cancelBleListening(id)
        poke.trySend(Unit)
    }

    /** A screen connects on the answer: the request is done. */
    fun consume(id: String) {
        _requests.update { it - id }
        app.cancelBleListening(id)
        poke.trySend(Unit)
    }

    suspend fun run() {
        app.scope.launch { app.ble.seen.collect { poke.trySend(Unit) } }
        while (true) {
            val wakeAt = withContext(Dispatchers.Main) { sync(System.currentTimeMillis()) }
            val wait = (wakeAt - System.currentTimeMillis()).coerceIn(1_000L, POLL_MS)
            withTimeoutOrNull(wait) { poke.receive() }
        }
    }

    private fun sync(now: Long): Long {
        var wakeAt = now + POLL_MS
        val seen = app.ble.seen.value
        for (r in _requests.value.values) {
            when (BleWakePlan.step(r, seen[r.tagId]?.at, now)) {
                BleWakePlan.Step.ANSWERED -> {
                    _requests.update { m -> m[r.tagId]?.let { m + (r.tagId to it.copy(answeredAt = now)) } ?: m }
                    DiagLog.i(TAG, "${name(r.tagId)}: answered after ${(now - r.startedAt) / 1000} s")
                    if (screens == 0) app.store.get(r.tagId)?.let { app.notifyBleListening(it) }
                    wakeAt = minOf(wakeAt, now + BleWakePlan.ANSWER_KEEP_MS + 1_000)
                }
                BleWakePlan.Step.TIMED_OUT -> {
                    _requests.update { it - r.tagId }
                    _missed.update { it + (r.tagId to now) }
                    DiagLog.w(TAG, "${name(r.tagId)}: no answer by ${(now - r.startedAt) / 60_000} min, stopped waiting")
                }
                BleWakePlan.Step.DONE -> { _requests.update { it - r.tagId }; app.cancelBleListening(r.tagId) }
                BleWakePlan.Step.KEEP -> wakeAt = minOf(wakeAt, r.answeredAt?.let { it + BleWakePlan.ANSWER_KEEP_MS + 1_000 } ?: r.untilMs)
            }
        }
        val wanted = _requests.value.values.filter { it.waiting }.map { it.tagId }.toSet()
        for (id in onAir.keys.toList()) if (id !in wanted) stop(id)
        if (wanted.isNotEmpty()) {
            if (BleCommandBeacon.canAdvertise(app)) { for (id in wanted) if (id !in onAir) start(id) }
            else stopAllOnAir()
            // a set that failed to start, Bluetooth off or no permission: look again soon
            if (wanted.any { it !in onAir }) wakeAt = minOf(wakeAt, now + RETRY_MS)
        }
        // the background sighting scan sees the answer; with it switched off, the console scan does
        val needScan = wanted.isNotEmpty() && !app.prefs.bleSightings
        if (needScan != scanHeld) {
            scanHeld = needScan
            if (needScan) app.ble.acquire(app) else app.ble.release()
        }
        return wakeAt
    }

    private fun start(id: String) {
        val t = app.store.get(id) ?: return
        val key = app.keysFor(t)?.usbKey ?: return
        val adv = BleCommandBeacon.adapter(app)?.bluetoothLeAdvertiser ?: return
        val salt = ByteArray(6).also { random.nextBytes(it) }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW) // 100 ms: the tag's scan is short
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BleConsole.COMPANY, BleConsole.wakePayload(key, salt))
            .build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (onAir[id] !== this) { runCatching { adv.stopAdvertisingSet(this) }; return }
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) { DiagLog.i(TAG, "advertising the wake advert for ${name(id)}"); return }
                onAir.remove(id)
                DiagLog.w(TAG, "wake advert for ${name(id)} failed: $status, trying again")
            }
        }
        onAir[id] = cb
        runCatching { adv.startAdvertisingSet(params, data, null, null, null, cb) }
            .onFailure { onAir.remove(id); DiagLog.w(TAG, "startAdvertisingSet: ${it.message}") }
    }

    private fun stop(id: String) {
        val cb = onAir.remove(id) ?: return
        runCatching { BleCommandBeacon.adapter(app)?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
        DiagLog.i(TAG, "stopped the wake advert for ${name(id)}")
    }

    private fun stopAllOnAir() { onAir.keys.toList().forEach { stop(it) } }

    private fun name(id: String) = app.store.get(id)?.name ?: id

    companion object {
        private const val TAG = "BleWake"
        private const val POLL_MS = 60_000L
        /** Bluetooth off or no permission: look again this soon */
        private const val RETRY_MS = 30_000L
    }
}
