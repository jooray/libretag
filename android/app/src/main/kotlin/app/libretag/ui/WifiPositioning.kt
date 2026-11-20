package app.libretag.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.Position
import app.libretag.data.Tracker
import app.libretag.data.WifiFixRec
import app.libretag.data.WifiMerge
import app.libretag.locate.Estimate
import app.libretag.wifi.LookupResult
import app.libretag.wifi.WifiAutoLookup
import app.libretag.wifi.WifiBudget
import app.libretag.wifi.WifiCache
import app.libretag.wifi.WifiEstimator
import app.libretag.wifi.WifiHttp
import app.libretag.wifi.WifiHttpException
import app.libretag.wifi.WifiLookupClient
import app.libretag.wifi.WifiObservation
import app.libretag.wifi.WifiService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wi-Fi positioning (docs/design/wifi-positioning.md), app side. Off by default: a lookup tells the chosen
 * service which Wi-Fi networks are near the tag, so it learns roughly where the tag is. On tap, or
 * by itself as AppPrefs.wifiAuto says ([WifiAutoLookup], with the network budget [WifiBudget]).
 * Results are cached on the phone. The state of each report's lookup is in [states], so the
 * detail shows progress and the result in its Where row instead of a snackbar.
 */
object WifiPositioning {
    private val app get() = LibreTagApp.instance
    private val lock = Mutex()
    private var cache: WifiCache? = null
    private var budget: WifiBudget? = null
    /** direct for now; a Tor proxy later goes in here (WifiHttp.client(ProxyConfig.torSocks())) */
    private val http by lazy { WifiHttp.client(null) }

    enum class Outcome {
        /** a position, stored in Tracker.wifiFixes */
        FOUND,
        /** the service has no position for these networks (or too few of them to ask) */
        UNKNOWN,
        /** the service is limiting queries (HTTP 429) */
        BUSY,
        /** no answer: network, timeout, another HTTP error */
        FAILED,
        /** nothing was asked: service off, no networks, or the automatic budget said not now */
        SKIPPED,
    }

    /** One report's lookup: running, or how it ended and with which service. */
    data class State(val running: Boolean, val outcome: Outcome? = null, val service: WifiService = WifiService.OFF)

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    private fun key(tagId: String, n: Long) = "$tagId:$n"

    fun stateOf(tagId: String, n: Long): State? = _states.value[key(tagId, n)]

    private fun setState(k: String, s: State?) {
        _states.update { if (s == null) it - k else it + (k to s) }
    }

    private fun cacheFile() = File(app.filesDir, "wifi-cache.json")
    private fun budgetFile() = File(app.filesDir, WifiBudget.FILE)

    private fun cache(): WifiCache = cache ?: (runCatching {
        WifiCache.fromJson(cacheFile().readText(), { System.currentTimeMillis() })
    }.getOrNull() ?: WifiCache({ System.currentTimeMillis() })).also { cache = it }

    private fun budget(): WifiBudget = budget
        ?: WifiBudget.fromJson(runCatching { budgetFile().readText() }.getOrNull()).also { budget = it }

    private suspend fun saveBudget(b: WifiBudget) {
        budget = b
        withContext(Dispatchers.IO) { runCatching { budgetFile().writeText(b.toJson()) } }
    }

    fun service(): WifiService = runCatching { WifiService.valueOf(app.prefs.wifiService) }.getOrDefault(WifiService.OFF)

    fun serviceName(a: android.content.Context, s: WifiService) = a.getString(when (s) {
        WifiService.OFF -> R.string.wifi_off
        WifiService.BEACONDB -> R.string.wifi_beacondb
        WifiService.GRAPHENEOS_PROXY -> R.string.wifi_grapheneos
        WifiService.APPLE -> R.string.wifi_apple
    })

    /** The service a stored result came from ("BEACONDB"), by its display name. */
    fun serviceName(a: android.content.Context, stored: String): String =
        runCatching { serviceName(a, WifiService.valueOf(stored)) }.getOrDefault(stored)

    /** The report's networks, every full BSSID included (WifiMerge.observation). */
    fun observation(p: Position): WifiObservation? = WifiMerge.observation(p.wifi)

    /**
     * After a report was handled ([isNew]: its counter is above the tag's last): looks it up by
     * itself when AppPrefs.wifiAuto wants it (never a report quiet at one of the tag's places).
     */
    fun autoAfterReport(t: Tracker, p: Position, isNew: Boolean) {
        val now = System.currentTimeMillis()
        if (!WifiAutoLookup.wanted(app.prefs.wifiAuto, service(), t, p, isNew, now)) return
        app.scope.launch { lookup(t, p, auto = true) }
    }

    /**
     * Looks the report's networks up (cache first) and stores the position for that report.
     * [auto]: an automatic lookup, which the network budget may hold back; a tap is never held back.
     */
    suspend fun lookup(t: Tracker, p: Position, auto: Boolean = false): Outcome {
        val svc = service()
        val obs = observation(p)
        if (svc == WifiService.OFF || obs?.seed == null) return Outcome.SKIPPED
        val k = key(t.id, p.n)
        if (_states.value[k]?.running == true) return Outcome.SKIPPED
        setState(k, State(running = true, service = svc))
        val out = runCatching { lookupLocked(t, p, obs, svc, auto) }
            .onFailure { DiagLog.w("Wifi", "lookup: ${it.message}") }.getOrDefault(Outcome.FAILED)
        // a lookup the budget held back leaves no trace: the button is still there to tap
        setState(k, if (out == Outcome.SKIPPED) null else State(running = false, outcome = out, service = svc))
        return out
    }

    private suspend fun lookupLocked(t: Tracker, p: Position, obs: WifiObservation, svc: WifiService, auto: Boolean): Outcome = lock.withLock {
        val c = cache()
        val seed = obs.seed!!.mac
        val cached = c.get(seed)
        val result: LookupResult = when {
            cached != null -> LookupResult(aps = listOfNotNull(cached) + c.near(cached.lat, cached.lon, 600.0))
            // asked before and the service didn't know it: no new query
            c.isKnownUnknown(seed) -> return@withLock Outcome.UNKNOWN
            else -> {
                val now = System.currentTimeMillis()
                if (auto) {
                    val v = budget().check(t.id, now, WifiAutoLookup.isLost(p))
                    if (v != WifiBudget.Verdict.OK) {
                        DiagLog.i("Wifi", "automatic lookup held back: $v")
                        return@withLock Outcome.SKIPPED
                    }
                    saveBudget(budget().spent(t.id, now))
                }
                val r = try {
                    WifiLookupClient(svc, http).lookup(obs)
                } catch (e: WifiHttpException) {
                    saveBudget(budget().failed(System.currentTimeMillis(), e.rateLimited))
                    return@withLock if (e.rateLimited) Outcome.BUSY else Outcome.FAILED
                } catch (e: Exception) {
                    DiagLog.w("Wifi", "lookup: ${e.message}")
                    saveBudget(budget().failed(System.currentTimeMillis()))
                    return@withLock Outcome.FAILED
                }
                // too few networks for this service to answer
                    ?: return@withLock Outcome.UNKNOWN
                saveBudget(budget().succeeded())
                c.putAll(r, seed)
                withContext(Dispatchers.IO) { runCatching { cacheFile().writeText(c.toJson()) } }
                r
            }
        }
        val w = WifiEstimator.fromObservation(obs, result) ?: return@withLock Outcome.UNKNOWN
        val e = p.est?.takeIf { it.state == "ok" }?.let { Estimate(it.lat, it.lon, it.r68, it.r95, emptyList(), 100.0, emptyList()) }
        val combined = WifiEstimator.combine(w, e)
        val rec = WifiFixRec(combined.lat, combined.lon, combined.r68, combined.r95, System.currentTimeMillis(), svc.name)
        app.store.updateTracker(t.id) { it.copy(wifiFixes = (it.wifiFixes + (p.n to rec)).entries.sortedBy { e2 -> e2.key }.takeLast(200).associate { e2 -> e2.key to e2.value }) }
        Outcome.FOUND
    }

    /** The report carries Wi-Fi networks and has no position from them yet: a lookup can help. */
    fun canLookUp(t: Tracker, p: Position): Boolean = p.wifi != null && !p.hasFix && !t.wifiFixes.containsKey(p.n)

    /**
     * The "Look up Wi-Fi position" tap: runs it at once (choosing the service was the consent),
     * or, with no service chosen, offers Settings. Progress and the result show in the Where row.
     */
    fun tap(a: AppCompatActivity, t: Tracker, p: Position) {
        if (service() == WifiService.OFF) {
            MaterialAlertDialogBuilder(a).setTitle(R.string.wifi_lookup_title).setMessage(R.string.wifi_lookup_off)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.menu_settings) { _, _ -> a.startActivity(Intent(a, SettingsActivity::class.java)) }.show()
            return
        }
        // the app's scope: leaving the screen does not cancel a query that is already out
        app.scope.launch { lookup(t, p) }
    }
}
