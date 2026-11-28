package app.libretag.mesh

import app.libretag.BuildConfig
import app.libretag.DiagLog
import android.util.Log
import io.github.jooray.meshcore.localapi.CursorStore
import io.github.jooray.meshcore.localapi.KeyStore
import io.github.jooray.meshcore.localapi.MeshApiSession
import io.github.jooray.meshcore.localapi.MeshEvent
import io.github.jooray.meshcore.localapi.MeshLogger
import io.github.jooray.meshcore.localapi.Route
import io.github.jooray.meshcore.localapi.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Local API link persists between runs. [meshIdentity] is the pairing key in the
 * library's format (`{"private":…,"public":…}`, the same the app wrote before 0.13), so an
 * update keeps the pairing; [meshCursor] is the library's replay cursor.
 */
interface MeshSettings {
    val meshUrl: String
    var meshIdentity: String?
    val meshRegion: String
    var meshCursor: String?
}

/** The pairing key in [MeshSettings.meshIdentity]. */
class SettingsKeyStore(private val settings: MeshSettings) : KeyStore {
    override fun load(): String? = settings.meshIdentity
    override fun save(value: String) { settings.meshIdentity = value }
}

/** The replay cursor in [MeshSettings.meshCursor]; only the library moves it. */
class SettingsCursorStore(private val settings: MeshSettings) : CursorStore {
    override fun load(): String? = settings.meshCursor
    override fun save(cursor: String) { settings.meshCursor = cursor }
}

/** The library's diagnostics into logcat, info and warnings also into the app's diagnostic log. */
object DiagLogger : MeshLogger {
    override fun debug(tag: String, message: String) { Log.d(tag, message) }
    override fun info(tag: String, message: String) = DiagLog.i(tag, message)
    override fun warn(tag: String, message: String, error: Throwable?) =
        DiagLog.w(tag, message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
}

/**
 * LibreTag's link to the MeshCore Local API (https://github.com/jooray/meshcore-local-api):
 * reports and commands on `#libretag`, data type 0xFF4C, through the library's [MeshApiSession].
 */
class MeshLink(
    private val settings: MeshSettings,
    scope: CoroutineScope,
    private val channelName: String = CHANNEL,
    /** Further channels read after the main one is subscribed. */
    extraChannels: Map<String, List<Int>> = emptyMap(),
) {
    val session = MeshApiSession(
        config(settings.meshUrl, channelName, extraChannels),
        keyStore = SettingsKeyStore(settings),
        scope = scope,
        cursorStore = SettingsCursorStore(settings),
        logger = DiagLogger,
    )

    val state: StateFlow<SessionState> get() = session.state
    val connected: StateFlow<Boolean> get() = session.connected
    val datagrams: SharedFlow<MeshEvent.ChannelData> get() = session.datagrams

    fun start() = session.start()
    fun stop() = session.stop()

    /** What MeshCore Open shows in its "Allow …?" dialog for this app, e.g. `AB12 CD34 EF56 7890`. */
    fun fingerprint(): String = session.fingerprint

    /** The connected server sends zero-hop (`tx_path_data`). */
    val canZeroHop: Boolean get() = session.canSendPath

    /**
     * Sends one datagram; false when the API is not connected or refused it. [zeroHop]: only to
     * radios in range of ours, never forwarded; without `tx_path_data` nothing is sent, so a
     * zero-hop send never turns into a flood. A flood carries the region from the settings
     * (empty: the channel's or the radio's own scope).
     */
    suspend fun sendChannelData(payload: ByteArray, zeroHop: Boolean = false): Boolean {
        if (session.state.value !is SessionState.Connected) return false
        if (zeroHop && !canZeroHop) return false
        val region = settings.meshRegion.trim().ifEmpty { null }
        return session.sendData(
            payload, DATA_TYPE_LIBRETAG,
            region = if (zeroHop) null else region,
            route = if (zeroHop) Route.ZeroHop else Route.Flood,
        ).isOk
    }

    /** The result of a zero-hop send: [ok], the server's error code, and when the radio sent it ([sentAtMs], `tx_now`). */
    data class Sent(val ok: Boolean, val error: String? = null, val sentAtMs: Long? = null, val requestMs: Long = 0)

    /**
     * A zero-hop datagram (ACKs and slot commands, SPEC.md 3.2 "Own node"). When the server lists
     * `tx_now` (our MeshCore Open fork), it goes with `"priority": "now"`, skipping the quiet wait, and
     * the answer carries `sentAt`. Never a flood: without `tx_path_data` nothing is sent.
     */
    suspend fun sendZeroHopNow(payload: ByteArray): Sent {
        val t0 = System.currentTimeMillis()
        if (session.state.value !is SessionState.Connected) return Sent(false, "not_connected", requestMs = t0)
        if (!canZeroHop) return Sent(false, "no_tx_path_data", requestMs = t0)
        if (TX_NOW !in session.capabilities) {
            val r = session.sendData(payload, DATA_TYPE_LIBRETAG, route = Route.ZeroHop)
            return Sent(r.isOk, r.errorCode, null, t0)
        }
        val params = kotlinx.serialization.json.JsonObject(
            io.github.jooray.meshcore.localapi.MeshCoreApiClient.sendDataParams(channelName, DATA_TYPE_LIBRETAG,
                payload, null, Route.ZeroHop) + ("priority" to kotlinx.serialization.json.JsonPrimitive("now")))
        val ans = session.request("channel.sendData", params)
        val sentAt = (ans.resultOrNull?.get("sentAt") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.let { v -> v.toLongOrNull() ?: runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrNull() }
        if (!ans.isOk) logger.warn("MeshLink", "send now refused: ${ans.errorCode}", null)
        return Sent(ans.isOk, ans.errorCode ?: if (ans.isOk) null else ans.javaClass.simpleName.lowercase(), sentAt, t0)
    }

    companion object {
        /** our MeshCore Open fork: `"priority": "now"` on channel.sendData for short packets, with `sentAt` */
        const val TX_NOW = io.github.jooray.meshcore.localapi.Capabilities.TX_NOW
        const val RX_QUEUED = io.github.jooray.meshcore.localapi.Capabilities.RX_QUEUED
        private val logger = DiagLogger
        const val CHANNEL = "#libretag"
        /** LibreTag reports and commands on #libretag (MeshCore dev range; allocation pending). */
        const val DATA_TYPE_LIBRETAG = 0xFF4C

        fun config(url: String, channel: String = CHANNEL, extraChannels: Map<String, List<Int>> = emptyMap()) =
            MeshApiSession.Config(
                clientName = "LibreTag",
                clientVersion = BuildConfig.VERSION_NAME,
                channel = channel,
                dataTypes = listOf(DATA_TYPE_LIBRETAG),
                extraChannels = extraChannels,
                url = url,
            )
    }
}
