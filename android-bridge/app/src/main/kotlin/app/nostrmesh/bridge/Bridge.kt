package app.nostrmesh.bridge

import app.nostrmesh.FieldLog
import app.nostrmesh.frames.FrameException
import app.nostrmesh.frames.Frames
import app.nostrmesh.frames.Fragments
import app.nostrmesh.frames.Road
import app.nostrmesh.libretag.Claims
import app.nostrmesh.libretag.Election
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Mailbox
import app.nostrmesh.libretag.Market
import app.nostrmesh.libretag.QuietMesh
import app.nostrmesh.nostr.Bech32
import app.nostrmesh.libretag.ReportPaths
import app.nostrmesh.libretag.StoredMailbox
import app.nostrmesh.libretag.UplinkDedup
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.isLowerHex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex
import app.nostrmesh.relay.InboxRelays
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** A device the bridge may hand DMs to (docs/nostr-mesh-bridge.md 5.5). */
data class Device(
    val name: String,
    val pubkey: ByteArray,
    val owner: ByteArray?,
    val allowFrom: List<ByteArray>,
    /** Sleeps between uplinks: DMs wait for its next frame. */
    val sleeps: Boolean = true,
)

data class BridgeConfig(
    val uplinkProfiles: Set<String> = DEFAULT_UPLINK,
    val downlinkDm: Boolean = false,
    /** LT2 command mailbox (kind 4244 -> #libretag). */
    val libretagCommands: Boolean = true,
    val unregisteredDm: Boolean = false,
    /** dm4x (expires in 14 d, e.g. a tracker's low battery) goes up from any key. */
    val unregisteredExpiringDm: Boolean = true,
    /** A DM also goes to the recipient's inbox relays (kind 10050, else 10002 read). */
    val dmInboxRelays: Boolean = true,
    val dmInboxMax: Int = InboxRelays.MAX_EXTRA,
    /** Slots: mailbox entries with `at` and `via` go out at `at` by the plan of PROTOCOL.md 4.3. */
    val timedCommands: Boolean = true,
    /** Path records live this long from their last use (R33). */
    val reportPathTtlMs: Long = QuietMesh.PATH_RECORD_TTL_MS,
    /** The claim for a timed entry goes out this long before `at`; the election is decided at `at`. */
    val timedClaimLeadMs: Long = 5_000,
    /** A timed entry more than this late (on arrival or at sending time) is dropped. */
    val timedMaxLateMs: Long = 60_000,
    // Abuse limits on the mailbox (PROTOCOL.md 4 "Limits"; same defaults as host/bridge.example.toml)
    /** A 4244's created_at at most this far ahead. */
    val futureMaxMs: Long = 60_000,
    /** `at` <= heard_at(via) + this (heartbeat 24 h + 1 h). */
    val timedMaxAfterReportMs: Long = 25 * 3600_000L,
    /** Timed entries kept per `via` (earlier seen stay). */
    val timedPerViaMax: Int = 12,
    /** At most one timed transmission per `via` in this time. */
    val timedViaGapMs: Long = 600_000,
    /** Timed transmissions per rolling hour, all `via` together. */
    val timedPerHour: Int = 20,
    /** New #libretag packets per minute (separate from [rxPerMinute]). */
    val ltRxPerMinute: Int = 30,
    /**
     * Of those, per first repeater of the path (heard directly: one shared key), checked before
     * [ltRxPerMinute] so one noisy repeater cannot use it up. No path from the server: skipped.
     */
    val ltRxPerFirstHopPerMinute: Int = 10,
    /** #libretag downlink airtime per rolling hour, separate from [airtimeFraction]. */
    val ltAirtimeFraction: Double = 0.001,
    /** One source relay fills at most this share of the mailbox. */
    val mailboxRelayShare: Double = 0.34,
    // Signed mailbox (PROTOCOL.md 4.2; same names and defaults as host/src/nostrmesh/config.py): no proof of work
    /** A key locks only if an event of it was here this long before the report. */
    val anchorLeadMs: Long = 60_000,
    /** A locked key's entry for a report heard at most this long ago goes out at once (late entry; the tag listens 60 s). */
    val lateMaxMs: Long = QuietMesh.LATE_MAX_MS,
    /** Distinct keys stored per report tag y (anchors and untimed entries; first seen stay). */
    val keysPerY: Int = 8,
    // PROTOCOL.md 4.1 (same names and defaults as [libretag] in host/src/nostrmesh/config.py)
    /** Bridge pubkeys (hex or npub) whose claims always count. */
    val peers: List<String> = emptyList(),
    /** Networks served (announcement `d`; entries with another `r` are not stored). */
    val networks: List<String> = emptyList(),
    /** Publish kind 4245 receipts for reports heard directly (on by default since 0.10.0; needs the bridge key). */
    val receipts: Boolean = true,
    val receiptsPerHour: Int = 120,
    /** Publish kind 38244 announcements (needs the bridge key): hourly, and within a minute of a radio change. */
    val announce: Boolean = true,
    val announceIntervalMs: Long = 3600_000L,
    /** Live ACK sends per rolling hour (apart from the command budget). */
    val acksPerHour: Int = QuietMesh.ACKS_PER_HOUR,
    /** A zero-hop send with `both`, or a path send: the 0xFF6C flood after this without an answer. */
    val fallbackAfterMs: Long = QuietMesh.FALLBACK_AFTER_MS,
    /** Store entries that name no bridge (no `p`). */
    val acceptOpen: Boolean = true,
    /** Paths of at most this many repeaters (`direct_max_hops`, 2; 0 turns direct routing off). */
    val directMaxHops: Int = Market.DIRECT_MAX_HOPS,
    /** Timed transmissions use at most this share of the #libretag airtime budget. */
    val timedAirtimeShare: Double = 0.5,
    /** Floods of open entries per rolling hour; zero-hop and entries naming this bridge do not count. */
    val openFloodsPerHour: Int = 6,
    /** Mailbox size in tags. */
    val mailboxCap: Int = Mailbox.DEFAULT_CAP,
    /** Shown in the announcement. */
    val bridgeName: String = "",
    val appVersion: String = "",
    /** MeshCore flood scopes this bridge serves, narrow first (`[radio] regions`; announcement `region` tags). */
    val regions: List<String> = emptyList(),
    /** Drop the rest of a command group once the tracker answered (PROTOCOL.md 4 "Command groups"). */
    val groupAck: Boolean = true,
    /** A report this soon after an own timed transmission may be its answer. */
    val ackWindowMs: Long = 180_000,
    val rxPerMinute: Int = 60,
    val perPubkeyPerMinute: Int = 6,
    val perPubkeyPerHour: Int = 60,
    val dmPerHour: Int = 6,
    val dmPerDevicePerHour: Int = 4,
    val holdMinMs: Long = 3_000,
    val holdMaxMs: Long = 15_000,
    val queueTtlMs: Long = 24 * 3600_000L,
    val queuePerDevice: Int = 8,
    /**
     * LT2 report uplink: random hold in [reportHoldMinMs, reportHoldMaxMs] before publishing, then skip
     * if already on this many relays. The floor is above [electionMs], so only a bridge that heard a
     * report can make its claim proof in time (PROTOCOL.md 4.1).
     */
    val reportHoldMinMs: Long = 2_000,
    val reportHoldMaxMs: Long = 5_000,
    val dedupMinRelays: Int = 2,
    /** Bridge election window for an LT2 command. */
    val electionMs: Long = 1_500,
    /** Direct DM downlinks up to this many hops (LT2 commands flood, [lt2CommandRoute]). */
    val maxDirectHops: Int = 3,
    /** A frame heard longer ago than this (replay from the `since` cursor) triggers no downlink. */
    val liveRxMs: Long = 20_000,
    /** DM rendezvous: the device's frame is at most this old (its RX window). */
    val rendezvousFreshS: Long = 60,
    /** Own transmissions: share of airtime per rolling hour (0.001 = 0.1 %). */
    val airtimeFraction: Double = 0.001,
    val preset: LoRaPreset = LoRaPreset.SK,
    val region: Boolean = false,
    /** An event no relay took is retried this long (capped by its own `expiration`). */
    val outboxTtlMs: Long = Outbox.TTL_MS,
    /** Nothing from the radio this long: radio_down, and the app reconnects the Local API client (0 = off). */
    val rxSilenceMs: Long = Watchdog.SILENCE_MS,
    /** Heartbeat: log line and status.json. */
    val heartbeatMs: Long = 3600_000L,
) {
    internal fun limits() = listOf(rxPerMinute, perPubkeyPerMinute, perPubkeyPerHour, dmPerHour, dmPerDevicePerHour,
        ltRxPerMinute, ltRxPerFirstHopPerMinute, receiptsPerHour, acksPerHour)

    companion object {
        val DEFAULT_UPLINK = setOf("road.report", "road.vote", "libretag", "dm4.ref", "dm4.full")
        val ALL_UPLINK = listOf("road.report", "road.vote", "libretag", "dm4.ref", "dm4.full", "note", "generic")
    }
}

/** What the UI shows: one line per frame the bridge handled. */
data class Activity(val atMs: Long, val direction: Direction, val profile: String, val text: String) {
    enum class Direction { UP, DOWN, DROP, INFO }
}

/** What the radio said to one send: [error] is the Local API error code (`not_connected`, `timeout`, ...). */
data class TxResult(val ok: Boolean, val error: String? = null)

interface MeshLink {
    val maxPayload: Int
    /** `hello.capabilities` of the Local API server. */
    val capabilities: Set<String> get() = emptySet()
    /** LoRa preset reported by the server, if any (else [BridgeConfig.preset]). */
    val preset: LoRaPreset? get() = null
    /** [region]: the flood scope (null: the node's default); ignored for zero-hop and direct sends. */
    suspend fun send(channel: String, dataType: Int, payload: ByteArray, route: Route = Route.Flood, region: String? = null): TxResult
}

interface Publisher {
    suspend fun publish(event: JsonObject): Boolean
    fun subscribe(subId: String, filters: List<JsonObject>)
    /** Relays [publish] writes to. */
    val publishUrls: List<String> get() = emptyList()
    /** One-shot query (until EOSE or a timeout) on the subscribe relays. */
    suspend fun query(filters: List<JsonObject>): List<JsonObject> = emptyList()
    /** Publish to relays outside the pool, once each; true when one accepted it. */
    suspend fun publishOnce(event: JsonObject, urls: List<String>): Boolean = false
}

@Serializable
data class LedgerRow(val id: String, val at: Long, val h: Boolean = false, val p: Boolean = false, val s: Boolean = false, val r: Boolean = false)

/** Everything that survives a restart (review point 9). */
@Serializable
data class BridgeState(
    val v: Int = 1,
    val ledger: List<LedgerRow> = emptyList(),
    val mailbox: StoredMailbox = StoredMailbox(),
    val airtime: List<List<Double>> = emptyList(),
    val lastCommandAt: Long = 0,
    /** Paths of caught LT2 reports by x (timed mailbox entries). */
    val paths: List<ReportPaths.Entry> = emptyList(),
    /** #libretag downlink airtime (separate budget). */
    val ltAirtime: List<List<Double>> = emptyList(),
    /** Events no relay accepted yet (0.3.2; older files have none). */
    val outbox: List<OutboxEntry> = emptyList(),
    /** Airtime of timed #libretag transmissions (their share of the budget, PROTOCOL.md 4.1). */
    val ltTimedAirtime: List<List<Double>> = emptyList(),
    /** When open entries went out as a flood (hourly cap). */
    val openFloods: List<Long> = emptyList(),
    /** Radio state (SPEC 3.2 "Radio health"): "up" or "down" and since when. */
    val radio: String = "up",
    val radioSince: Long = 0,
)

/**
 * Port of host/src/nostrmesh/bridge.py for MeshCore Open.
 *
 * Mesh -> relays: signed frames (Roadbeacon, notes, dm4) unchanged; LibreTag LT2 reports on
 * #libretag as kind 4243 under a fresh key (variant B, with dedup). Relays -> mesh: LT2 commands
 * from the kind 4244 mailbox, sent once when the matching report is heard, and (optional, off by
 * default) NIP-04 DMs to registered devices. No downlink ever floods, except an LT2 command
 * marked `route=flood` (LOST).
 */
class Bridge(
    private val scope: CoroutineScope,
    private val mesh: MeshLink,
    private val relays: Publisher,
    config: BridgeConfig,
    devices: List<Device>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val newKey: () -> ByteArray = ::randomKey,
    /**
     * Stable bridge key (PROTOCOL.md 4.1): signs claims, done claims, receipts and announcements. Without
     * one the bridge works as before, but its claims count nowhere and it publishes no receipts or
     * announcements.
     */
    private val bridgeKey: ByteArray? = null,
) {
    var config: BridgeConfig = config
        set(value) {
            val old = field
            field = value
            if (old.limits() != value.limits()) buildLimits()
            budget.fraction = value.airtimeFraction
            ltBudget.fraction = value.ltAirtimeFraction
            ltTimedBudget.fraction = value.ltAirtimeFraction * value.timedAirtimeShare
            if (old.peers != value.peers) peerKeys = parsePeers(value.peers)
            radio.silenceMs = value.rxSilenceMs
        }

    /** Hex pubkey of [bridgeKey], or null. */
    val bridgePubkey: String? = bridgeKey?.let { NostrEvent.xonly(it).hex() }
    @Volatile private var peerKeys: Set<String> = parsePeers(config.peers)

    var devices: List<Device> = devices

    private val ledger = object : LinkedHashMap<String, Entry>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > LEDGER_CAP
    }
    private class Entry(
        var heard: Boolean = false, var published: Boolean = false, var sent: Boolean = false,
        val relay: Boolean = false, val atMs: Long = 0,
    )

    private val reassembler = Fragments.Reassembler()
    private val queues = mutableMapOf<String, MutableList<Outgoing>>()
    /** Device -> (when, how) its last fresh frame was heard: the way back for an awake device. */
    private val lastHeard = mutableMapOf<String, Pair<Long, RxMeta>>()
    private val txLock = Mutex()
    private val lock = Any()

    val mailbox = Mailbox(config.mailboxCap)
    val reportPaths = ReportPaths(config.reportPathTtlMs)
    private val inboxCache = InboxRelays.Cache()
    private val claims = Claims()
    private val dedup = UplinkDedup()
    private val ownClaimKeys = LinkedHashSet<String>().also { s -> bridgePubkey?.let { s.add(it) } }
    private val budget = AirtimeBudget(config.airtimeFraction)
    private val ltBudget = AirtimeBudget(config.ltAirtimeFraction)
    /** Timed #libretag transmissions: their share of [ltBudget] (PROTOCOL.md 4.1 "Airtime order"). */
    private val ltTimedBudget = AirtimeBudget(config.ltAirtimeFraction * config.timedAirtimeShare)
    /** When open entries went out as a flood (rolling hour). */
    private val openFloods = ArrayDeque<Long>()
    /** Announced bridges (kind 38244, signature checked) by pubkey -> expiration (unix s). */
    private val announced = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 5_000
    }
    /** Report tags x heard on the radio -> when: only the first hearing triggers a command (Q5). */
    private val heardX = object : LinkedHashMap<String, Long>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > LEDGER_CAP
    }
    @Volatile private var lastAnnounceMs: Long? = null
    @Volatile private var lastCommandAt = 0L
    private val changes = AtomicInteger(0)
    /** Grows on every change worth persisting. */
    val changeCount: Int get() = changes.get()
    /** Called after every mailbox change (the app saves the state at once, not a minute later). */
    @Volatile var onMailboxChange: (() -> Unit)? = null
    /** y of verified kind 4243 seen on relays -> created_at (evidence for "done" claims). */
    private val relayReports = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 5_000
    }
    /** The radio's own state (R37): detection from MeshCore Open's state, failed sends and silence. */
    val radio = RadioHealth(config.rxSilenceMs, clock())

    private fun mailboxChanged() {
        changes.incrementAndGet()
        stat { it.copy(mailbox = mailbox.size) }
        onMailboxChange?.invoke()
    }

    private lateinit var rx: RateLimit
    private lateinit var pkMin: RateLimit
    private lateinit var pkHour: RateLimit
    private lateinit var dmHour: RateLimit
    private lateinit var dmDevice: RateLimit
    private lateinit var ltRx: RateLimit
    private lateinit var ltRxFirstHop: RateLimit

    private val outbox = Outbox(config.outboxTtlMs)
    /** Counters with the host bridge's names (status.json `counters`). */
    private val counters = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun count(key: String, n: Long = 1) { counters.merge(key, n, Long::plus) }
    /** Last datagram from the radio, any channel (watchdog). */
    @Volatile var lastRxMs: Long = 0L
        private set
    private val startedAt = clock()
    @Volatile private var heartbeatBase: Map<String, Double> = emptyMap()
    @Volatile private var lastHeartbeat = startedAt

    init { buildLimits() }

    private fun buildLimits() {
        val c = config
        rx = RateLimit(c.rxPerMinute, 60_000)
        pkMin = RateLimit(c.perPubkeyPerMinute, 60_000)
        pkHour = RateLimit(c.perPubkeyPerHour, 3600_000)
        dmHour = RateLimit(c.dmPerHour, 3600_000)
        dmDevice = RateLimit(c.dmPerDevicePerHour, 3600_000)
        ltRx = RateLimit(c.ltRxPerMinute, 60_000)
        ltRxFirstHop = RateLimit(c.ltRxPerFirstHopPerMinute, 60_000)
        receiptsHour = RateLimit(c.receiptsPerHour, 3600_000)
        acksHour = RateLimit(c.acksPerHour, 3600_000)
    }

    private lateinit var receiptsHour: RateLimit
    private lateinit var acksHour: RateLimit

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats
    private val _activity = MutableStateFlow<List<Activity>>(emptyList())
    val activity: StateFlow<List<Activity>> = _activity

    data class Stats(
        val heard: Int = 0, val published: Int = 0, val dropped: Int = 0, val toRadio: Int = 0,
        val queued: Int = 0, val recognised: Int = 0, val mailbox: Int = 0, val airtimeMs: Int = 0,
        /** Events waiting in the outbox for a relay. */
        val outbox: Int = 0,
    )

    private class Outgoing(val eventId: String, val payload: ByteArray, val device: String, val expires: Long, val label: String)

    private fun stat(f: (Stats) -> Stats) { _stats.value = f(_stats.value) }

    private fun log(dir: Activity.Direction, profile: String, text: String) {
        _activity.value = (listOf(Activity(clock(), dir, profile, text)) + _activity.value).take(200)
    }

    private fun drop(profile: String, why: String) {
        stat { it.copy(dropped = it.dropped + 1) }
        log(Activity.Direction.DROP, profile, why)
    }

    private fun deviceOf(pk: ByteArray) = devices.firstOrNull { it.pubkey.contentEquals(pk) }

    private fun resolve(ref: ByteArray): List<ByteArray> {
        val keys = devices.flatMap { listOfNotNull(it.pubkey, it.owner) + it.allowFrom }
        return keys.filter { it.copyOf(4).contentEquals(ref) }.distinctBy { it.hex() }
    }

    private fun knows(pk: ByteArray) = resolve(pk.copyOf(4)).any { it.contentEquals(pk) }

    /** Records [id]; true when it was already in the ledger. */
    private fun seenBefore(id: String, now: Long, relay: Boolean = false, mark: (Entry) -> Unit = { it.heard = true }): Boolean {
        val dup = synchronized(lock) {
            val e = ledger[id]
            if (e != null) { mark(e); true } else { ledger[id] = Entry(relay = relay, atMs = now).also(mark); false }
        }
        changes.incrementAndGet()
        return dup
    }

    // ------------------------------------------------------------------ mesh -> relays

    /** Legacy entry point (hop count only). */
    suspend fun onDatagram(channel: String, dataType: Int, payload: ByteArray, hops: Int?) =
        onDatagram(channel, dataType, payload, RxMeta(hops = hops))

    suspend fun onDatagram(channel: String, dataType: Int, payload: ByteArray, meta: RxMeta) {
        val now = clock()
        lastRxMs = now
        radioEvent(radio.onRadio(now), now)
        if (channel == Frames.CHANNEL_LIBRETAG && (dataType == Frames.DATA_TYPE_LIBRETAG || dataType == QuietMesh.DATA_TYPE_RETRY)) {
            if (!meta.forUs) return // a DIRECT packet with path entries left is for the next repeater
            when {
                dataType == Frames.DATA_TYPE_LIBRETAG && Lt2.isUplinkPacket(payload.size) -> onLt2Report(payload, meta, now)
                payload.size == Lt2.COMMAND_LEN -> mailbox.heardOnAir(payload, now).let {
                    if (dataType == QuietMesh.DATA_TYPE_RETRY) synchronized(lock) { heardFallback[payload.hex()] = now }
                    mailboxChanged()
                    fl("rx", "xc" to payload.copyOf(4), "len" to payload.size, "dtype" to dataType, "route" to meta.route)
                    if (it != null) log(Activity.Direction.INFO, "libretag", "príkaz ${it.ys.first()} už vyslal iný most")
                }
                // an ACK another bridge or the owner's node sent: the same Pa is not sent again
                dataType == Frames.DATA_TYPE_LIBRETAG && (payload.size == QuietMesh.ACK_LEN || payload.size in QuietMesh.ROUTED_ACK_LENS) -> {
                    synchronized(lock) { heardPa[payload.copyOf(QuietMesh.ACK_LEN).hex()] = now }
                    fl("rx", "xa" to payload.copyOf(4), "len" to payload.size, "route" to meta.route)
                }
            }
            return
        }
        val accepted = (channel == Frames.CHANNEL_NOSTR && dataType == Frames.DATA_TYPE_NOSTR) ||
            (channel == Frames.CHANNEL_ROAD && dataType == Frames.DATA_TYPE_ROAD)
        if (!accepted) return
        if (!rx.hit(now, "")) return drop("?", "príliš veľa rámcov za minútu")
        var frame = payload
        if (dataType == Frames.DATA_TYPE_NOSTR && frame.isNotEmpty() && frame[0].toInt() and 0x3F == Frames.FRAG) {
            frame = try {
                reassembler.feed(frame, now)
            } catch (e: FrameException) {
                return drop("frag", e.code)
            } ?: return
            val prof = frame[0].toInt() and 0x3F
            if (prof !in Frames.DM_PROFILES) return drop("frag", "fragmenty len pre DM")
        }
        val d = try {
            Frames.decode(frame, dataType, ::resolve, maxLen = Frames.MAX_REASSEMBLED)
        } catch (e: FrameException) {
            return drop("?", e.code)
        }
        val ev = d.event
        val name = d.profileName
        if (!fresh(ev, d.policyName, now / 1000)) return drop(name, "starý alebo z budúcnosti")
        stat { it.copy(heard = it.heard + 1) }

        // Duplicate check first: a replayed frame must not open the DM queue (review point 5).
        if (seenBefore(ev.id, now)) return

        // rendezvous: the device just spoke and listens for a moment
        val heardAt = meta.receivedAtMs?.coerceAtMost(now) ?: now
        val pk = ev.pubkey.unhex()!!
        if (deviceOf(pk) != null && now / 1000 - ev.createdAt <= config.rendezvousFreshS && now - heardAt <= config.liveRxMs) {
            synchronized(lock) { lastHeard[ev.pubkey] = now to meta }
            flush(ev.pubkey, meta)
        }
        if (ev.kind == Frames.INBOX_POLL_KIND) {
            log(Activity.Direction.INFO, "poll", "zariadenie počúva (${ev.pubkey.take(8)})")
            return
        }
        if (!uplinkAllowed(d, now)) return
        scope.launch {
            val json = ev.toJson()
            val inbox = if (d.profile in Frames.DM_PROFILES && config.dmInboxRelays) {
                scope.async { publishToInbox(json, ev.tags[0][1], name) }
            } else null
            val ok = publishKept(json, ev.id, name, "published")
            if (ok or (inbox?.await() == true)) {
                log(Activity.Direction.UP, name, describe(d))
            } else {
                log(Activity.Direction.INFO, name, "no relay took it yet, kept for a retry")
            }
        }
    }

    private fun describe(d: Frames.Decoded): String {
        val e = d.event
        return when (d.profile) {
            Frames.ROAD_REPORT -> "${e.tags.firstOrNull { it[0] == "t" }?.get(1)} ${e.content}".trim()
            Frames.ROAD_VOTE -> e.tags.firstOrNull { it[0] == "status" }?.get(1) ?: ""
            in Frames.DM_PROFILES -> "DM ${devices.firstOrNull { it.pubkey.hex() == e.pubkey }?.name ?: e.pubkey.take(8)} → ${e.tags[0][1].take(8)}"
            Frames.NOTE -> e.content.take(40)
            else -> "kind ${e.kind}"
        }
    }

    private fun fresh(ev: NostrEvent, profile: String, nowS: Long): Boolean {
        if (ev.createdAt > nowS + 300) return false
        if (profile == "road.report") {
            val t = ev.tags.firstOrNull { it[0] == "t" }?.get(1) ?: "other"
            val exp = ev.tags.firstOrNull { it[0] == "expiration" }?.get(1)?.toLongOrNull() ?: return false
            return nowS <= minOf(ev.createdAt + (Road.LIFETIME_S[t] ?: 4 * 3600L), exp)
        }
        val maxAge = when (profile) {
            "road.vote" -> 30 * 86400L
            "dm4.ref", "dm4.full" -> 7 * 86400L
            else -> 86400L
        }
        return nowS - ev.createdAt <= maxAge
    }

    private fun uplinkAllowed(d: Frames.Decoded, now: Long): Boolean {
        val name = d.profileName
        if (d.policyName !in config.uplinkProfiles) { drop(name, "profil vypnutý v nastaveniach"); return false }
        val pk = d.event.pubkey.unhex()!!
        if (d.profile in Frames.DM_PROFILES) {
            val rcpt = d.event.tags[0][1].unhex()!!
            val expiring = (d.profile == Frames.DM4X_FULL || d.profile == Frames.DM4X_REF) && config.unregisteredExpiringDm
            if (!(knows(pk) || knows(rcpt) || config.unregisteredDm || expiring)) { drop(name, "DM mimo registra"); return false }
        }
        if (!(pkMin.allow(now, d.event.pubkey) && pkHour.allow(now, d.event.pubkey))) {
            drop(name, "limit na kľúč"); return false
        }
        pkMin.hit(now, d.event.pubkey); pkHour.hit(now, d.event.pubkey)
        return true
    }

    /** The recipient's inbox relays (kind 10050 / 10002 read), cached; empty if unknown. */
    suspend fun inboxRelays(pubkey: String): List<String> {
        val now = clock()
        inboxCache.get(pubkey, now)?.let { return it }
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { InboxRelays.KINDS.forEach { add(JsonPrimitive(it)) } })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
            put("limit", 4)
        }
        val evs = runCatching { relays.query(listOf(filter)) }.getOrDefault(emptyList())
            .mapNotNull { NostrEvent.fromJson(it) }.filter { it.pubkey == pubkey && it.kind in InboxRelays.KINDS && it.verify() }
        val urls = InboxRelays.parse(evs, pubkey)
        inboxCache.put(pubkey, urls, now)
        return urls
    }

    private suspend fun publishToInbox(json: JsonObject, rcpt: String, profile: String): Boolean {
        val extra = InboxRelays.extra(inboxRelays(rcpt), relays.publishUrls, config.dmInboxMax)
        if (extra.isEmpty()) return false
        val ok = relays.publishOnce(json, extra)
        if (ok) log(Activity.Direction.INFO, profile, "DM ide aj na inbox relaye príjemcu (${extra.size})")
        return ok
    }

    // ------------------------------------------------------------------ LibreTag LT2 (quiet mesh, PROTOCOL.md 4.3)

    private fun fl(ev: String, vararg fields: Pair<String, Any?>) = FieldLog.ev(ev, *fields)

    private val caps get() = mesh.capabilities
    private val canZeroHop get() = Caps.TX_PATH_DATA in caps
    private val canPath get() = Caps.TX_PATH_DATA in caps && Caps.RX_PATH in caps

    /** Configured regions, narrow first (`[radio] regions`). */
    private fun regions() = config.regions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The scope a plan names: the report's (from its record), the narrowest region, or the country (widest). */
    private fun floodRegion(scope: String?, rec: ReportPaths.Entry?): String? = when (scope) {
        "narrowest" -> regions().firstOrNull()
        "country" -> regions().lastOrNull()
        else -> when {
            rec == null -> regions().firstOrNull()
            rec.scope != null -> rec.scope
            rec.route == "tflood" -> regions().firstOrNull() // scoped, but the node did not say which: the narrowest
            rec.isDirect -> regions().firstOrNull()
            else -> null // an unscoped report gets an unscoped reply
        }
    }

    /** B6: hop class of a hearing (0, 1, 2, 3+, unknown). */
    private fun hopClass(h: Int?) = when (h) { null -> "unknown"; 0 -> "0"; 1 -> "1"; 2 -> "2"; else -> "3+" }

    /** Earliest time of a queued frame: the last time the node's queue was seen empty (a live frame), else now. */
    @Volatile private var lastLiveRxMs = 0L

    /** Pa heard on air -> when (a near ACK is skipped if another bridge sent the same one). */
    private val heardPa = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2_048
    }
    /** y -> when an "ack sent" note of another bridge for it arrived. */
    private val ackNotes = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2_048
    }

    /** A command sent zero-hop with `both`, or along a path: the 0xFF6C flood follows unless an answer comes. */
    private class Fallback(
        val cmd: Mailbox.Command, val region: String?, val armedAt: Long, val via: String?,
        @Volatile var job: kotlinx.coroutines.Job? = null,
    )
    private val fallbacks = LinkedHashMap<String, Fallback>()
    /** Pc of a 0xFF6C fallback another bridge sent (heard on air): our own fallback for it is not sent again. */
    private val heardFallback = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2_048
    }
    /** Group -> (x a send of the group was for -> when that report was heard): what a later report answers (R34). */
    private val sendRefs = object : LinkedHashMap<String, MutableMap<String, Long>>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, Long>>?) = size > 2_048
    }
    /**
     * Group -> (x -> when heard) of live first hearings of the group's untimed y: a timed entry that falls due
     * later checks them against its own via (R34), also when nothing was there to answer at the hearing.
     */
    private val groupHeard = object : LinkedHashMap<String, MutableMap<String, Long>>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, Long>>?) = size > 2_048
    }
    /** ACKs on their way, by report window (y, or `at`:key for a slot): a command for the same window waits. */
    private val ackInFlight = HashMap<String, kotlinx.coroutines.Job>()
    /** When the ACK of a report window was done (sent or skipped): the command keeps 300 ms after it. */
    private val ackDoneAt = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 2_048
    }

    /** Timed sends and fallbacks in progress: the app holds a wake lock while this is above 0 (R24). */
    private val busyCount = AtomicInteger(0)
    val busy: Boolean get() = busyCount.get() > 0 || synchronized(lock) { fallbacks.isNotEmpty() }

    private fun persist() {
        changes.incrementAndGet()
        onMailboxChange?.invoke()
    }

    private suspend fun onLt2Report(p: ByteArray, meta: RxMeta, now: Long) {
        val y = Lt2.tag(p)
        val heardAt = meta.receivedAtMs?.coerceAtMost(now) ?: now
        val key = "lt:" + sha256(p).hex()
        val c = caps
        val hops = meta.hopCount(c)
        val direct = meta.heardDirect(c)
        // R22: a frame more than 20 s old or marked queued by the node has an approximate time
        val live = now - heardAt <= config.liveRxMs && meta.queued != true
        val newPacket = synchronized(lock) { ledger[key] } == null
        // own limit for new packets, so a #libretag flood does not use up the other channels' share
        if (newPacket) {
            // one first hop (where the packet entered the mesh) cannot use up the whole #libretag limit
            val fh = firstHopKey(meta, c)
            if (fh != null && !ltRxFirstHop.allow(now, fh)) {
                count("lt_rx_first_hop_limited")
                return drop("libretag", "too many LibreTag packets from one repeater")
            }
            if (!ltRx.hit(now, "")) {
                count("lt_rx_rate_limited")
                return drop("libretag", "príliš veľa LibreTag paketov za minútu")
            }
            if (fh != null) ltRxFirstHop.hit(now, fh)
        }
        val dup = seenBefore(key, now)
        // Q5: only the first hearing of P triggers; a later flood copy does not, so an entry published after the
        // report reached the relays cannot ride on it. A variant of P with another c is a new hearing: it may
        // lock another key (PROTOCOL.md 4.2), never remove one.
        val firstX = synchronized(lock) { if (key in heardX) false else { heardX[key] = now; true } }
        val first = !dup && firstX
        if (live) lastLiveRxMs = now
        if (!dup) {
            stat { it.copy(heard = it.heard + 1) }
            count("lt_rx_route_" + meta.route)
            count("lt_rx_hops_" + hopClass(hops))
        }
        fl("rx", "x" to y, "len" to p.size, "route" to meta.route, "hops" to hops,
            "path" to FieldLog.pathList(meta.path, meta.pathHashSize), "phs" to meta.pathHashSize,
            "scope" to meta.scope, "transport" to meta.transportCode, "snr" to meta.snrDb, "rssi" to meta.rssiDbm,
            "rx_time" to meta.receivedAtMs?.let { it / 1000.0 }, "stale" to (now - heardAt > config.liveRxMs).takeIf { it },
            "queued" to meta.queued, "first" to first, "heard_direct" to direct)
        if (config.libretagCommands) {
            val scope = if (meta.route == "tflood") QuietMesh.replyScope(meta.scope, meta.transportCode, Frames.DATA_TYPE_LIBRETAG, p, regions()) else null
            reportPaths.put(y, hops, if (meta.isDirect) null else meta.path, meta.pathHashSize ?: 1, heardAt, meta.route,
                if (p.size == Lt2.REPORT_LEN && live && first) p else null, scope, live)
            persist()
        }
        val waiting = mailbox.hasUntimed(y) // before the downlink takes it: decides the publish delay
        if (config.libretagCommands && live && first && p.size == Lt2.REPORT_LEN) lockReport(p, heardAt, now)
        if (config.libretagCommands && live && !dup && p.size == Lt2.REPORT_LEN) answers(y, heardAt, now)
        // Downlink first: the tracker listens for a short while only.
        if (config.libretagCommands && live && first) command(y, now)
        if (dup) return
        val receipt = live && first && direct && p.size == Lt2.REPORT_LEN && config.receipts && bridgeKey != null
        val uplink = "libretag" in config.uplinkProfiles
        if (!receipt && !uplink) return
        val content = Base64.getEncoder().encodeToString(p)
        // fast publish (0.3 to 0.8 s) for a report heard directly or over a path, when no untimed command waits
        val pathOk = hops != null && hops in 1..2 && meta.floodPath(c) != null
        val fast = live && (direct || pathOk) && !waiting
        val (lo, hi) = if (fast) QuietMesh.FAST_PUBLISH_MIN_MS to QuietMesh.FAST_PUBLISH_MAX_MS
            else config.reportHoldMinMs to maxOf(config.reportHoldMinMs, config.reportHoldMaxMs)
        val delayMs = random.nextLong(lo, hi + 1)
        // created_at = when the radio heard it (apps anchor the schedule on it); a stale frame gets the range
        val ev = if (live) Lt2.reportEvent(p, heardAt / 1000, newKey())
            else Lt2.reportEvent(p, now / 1000, newKey(), minOf(heardAt, lastLiveRxMs.takeIf { it > 0 } ?: now) / 1000)
        val json = ev.toJson()
        // R21: into the outbox at hearing time, with a not-before time, so a crash in the delay loses nothing
        if (uplink) {
            outbox.put(ev.id, json.toString(), key, "libretag", now, Outbox.expirationMs(json), firstTryMs = now + delayMs)
            outboxChanged()
            persist()
        }
        scope.launch {
            delay(delayMs)
            if (receipt) scope.launch { publishReceipt(y, meta.isDirect, heardAt) }
            if (!uplink) return@launch
            val already = dedup.relays(y, content, clock())
            if (already >= config.dedupMinRelays) {
                outbox.done(ev.id)
                outboxChanged()
                log(Activity.Direction.INFO, "libretag", "report $y už je na $already relays")
                fl("report_pub", "x" to y, "id" to ev.id, "skipped" to "on_relays", "relays" to already)
                return@launch
            }
            val t1 = clock()
            val ok = relays.publish(json)
            if (ok) {
                outbox.done(ev.id)
                published(key, "lt_published")
                log(Activity.Direction.UP, "libretag", if (p.size == Lt2.BLE_LEN) "BLE paket $y" else "report $y")
            } else {
                outbox.deferred(ev.id, clock())
                count("publish_deferred")
                log(Activity.Direction.INFO, "libretag", "report $y: no relay took it yet, kept for a retry")
            }
            outboxChanged()
            fl("report_pub", "x" to y, "id" to ev.id, "created_at" to ev.createdAt, "delay_s" to (t1 - now) / 1000.0,
                "approx" to !live, "ok" to ok, "ms" to clock() - t1, "receipt" to receipt)
        }
    }

    private suspend fun publishReceipt(x: String, direct: Boolean, heardAtMs: Long) {
        val sk = bridgeKey ?: return
        if (!receiptsHour.hit(clock(), "")) {
            count("lt_receipts_limited")
            return
        }
        val ev = NostrEvent.sign(sk, heardAtMs / 1000, Market.RECEIPT_KIND, Market.receiptTags(x, direct, heardAtMs / 1000), "")
        if (relays.publish(ev.toJson())) count("lt_receipts")
    }

    private fun pruneOpenFloods(now: Long) {
        while (openFloods.isNotEmpty() && now - openFloods.first() >= 3600_000L) openFloods.removeFirst()
    }

    /** Floods of open entries are capped per rolling hour; zero-hop and entries naming this bridge do not count. */
    private fun openFloodBlocked(cmd: Mailbox.Command, route: Route, now: Long): Boolean {
        if (route != Route.Flood || !cmd.open) return false
        return synchronized(lock) { pruneOpenFloods(now); openFloods.size >= config.openFloodsPerHour }
    }

    /** The time report [x] counts from: when the radio heard it, or earlier when its kind 4243 reached us first. */
    private fun reportTime(x: String, heardAt: Long): Long =
        minOf(heardAt, synchronized(lock) { relayReports[x] }?.times(1000) ?: heardAt)

    /**
     * PROTOCOL.md 4.2: lock every key that named x at least [BridgeConfig.anchorLeadMs] before the report
     * and fits its commitment c; drop entries for x from keys that can never lock.
     */
    private fun lockReport(p: ByteArray, heardAt: Long, now: Long) {
        val x = Lt2.tag(p)
        val t = reportTime(x, heardAt)
        val cands = mailbox.lockCandidates(x, t - config.anchorLeadMs, now)
        for (pk in cands) if (Lt2.commitMatches(pk, p) && mailbox.lock(x, pk, now)) {
            log(Activity.Direction.INFO, "libretag", "report $x: key ${pk.take(12)} locked")
        }
        val keys = mailbox.locked(x)
        fl("lock", "x" to x, "keys" to keys.size, "lead_s" to mailbox.keyLeadMs(x, keys, t)?.let { it / 1000.0 })
        if (keys.isEmpty()) {
            count("lt_lock_none")
            return
        }
        count("lt_lock")
        persist()
        val dropped = mailbox.dropUnlocked(x, keys + cands)
        if (dropped > 0) {
            count("lt_mailbox_unsigned_dropped", dropped.toLong())
            mailboxChanged()
        }
    }

    /**
     * Can an event from [pubkey] still go out for report [x]? Yes before x is heard; afterwards only for a
     * locked key or one that was here anchor_lead before the report (a variant of P may lock it later).
     */
    private fun mayLock(x: String, pubkey: String, now: Long): Boolean {
        val rp = reportPaths.get(x, now) ?: return true
        if (pubkey in mailbox.locked(x)) return true
        return pubkey in mailbox.lockCandidates(x, reportTime(x, rp.heardAtMs) - config.anchorLeadMs, now)
    }

    /** The stored path (tag to bridge order) of the first `via` of [cmd] that has one, with its record. */
    private fun viaPath(cmd: Mailbox.Command, now: Long): Pair<ReportPaths.Entry, List<String>>? =
        cmd.vias.firstNotNullOfOrNull { v ->
            reportPaths.get(v, now)?.let { r -> r.hashes?.takeIf { it.isNotEmpty() }?.let { r to it } }
        }

    /**
     * A plan and the hash size of the path it uses (a DIRECT arrival's path comes from its `via` record).
     * [pathX]: for a path send, the x of the path record whose path it uses (the "ack sent" note's `via`).
     */
    private class Planned(val plan: QuietMesh.Plan, val hashSize: Int, val pathX: String? = null) {
        val send get() = plan.send
    }

    private fun planFor(cmd: Mailbox.Command, rec: ReportPaths.Entry?, now: Long, blind: Boolean = false): Planned {
        // a DIRECT arrival has no path of its own: the one stored for `via` (or under its own x) is used
        val stored = if (rec?.isDirect == true) viaPath(cmd, now) ?: rec.hashes?.takeIf { it.isNotEmpty() }?.let { rec to it } else null
        val plan = QuietMesh.plan(
            if (cmd.ack) "ack" else "cmd", rec?.heard, cmd.route, viaPath = stored?.second, forms = cmd.ackForms ?: "near",
            fb = cmd.fb, maxHops = config.directMaxHops, canZeroHop = canZeroHop, canPath = canPath, blind = blind,
        )
        val pathX = if (plan.send == "path") (stored?.first ?: rec)?.x else null
        return Planned(plan, stored?.first?.hashSize ?: rec?.hashSize ?: 1, pathX)
    }

    private fun routeOf(p: Planned): Route? = when (p.plan.send) {
        "zero-hop" -> Route.ZeroHop
        "flood" -> Route.Flood
        "path" -> Route.Direct(QuietMesh.pathBytes(p.plan.path), p.hashSize)
        else -> null
    }

    /** On the first live hearing of report [y]: its ACK entries first, then its commands 300 ms later (R25: from the stores). */
    private fun command(y: String, now: Long) {
        val rec = reportPaths.get(y, now) ?: return
        val keys = mailbox.locked(y)
        if (keys.isEmpty()) {
            if (mailbox.hasUntimed(y)) {
                count("lt_cmd_unsigned")
                log(Activity.Direction.INFO, "libretag", "command for $y: no key locked, not sending")
            }
            return
        }
        for (a in mailbox.match(y, rec.heardAtMs, now, keys, ack = true)) sendAck(a, y, rec, now)
        // an entry its route does not allow is not taken: its y stays for a nearer bridge
        val cmds = mailbox.match(y, rec.heardAtMs, now, keys) { planFor(it, rec, now).send != "none" }
        if (cmds.isEmpty()) {
            if (mailbox.hasUntimed(y)) log(Activity.Direction.INFO, "libretag", "command for $y asks for a nearer bridge or is not signed, not sending")
            return
        }
        for (cmd in cmds) deliver(cmd, y, rec, now)
    }

    /**
     * A command for the same report and window goes [QuietMesh.ACK_BEFORE_CMD_MS] after its ACK (the app set
     * MORE): waits for an ACK still on its way under [key], then the gap. An ACK already done costs nothing.
     */
    private suspend fun afterAck(key: String) {
        synchronized(lock) { ackInFlight[key] }?.join()
        val done = synchronized(lock) { ackDoneAt[key] } ?: return
        val wait = done + QuietMesh.ACK_BEFORE_CMD_MS - clock()
        if (wait > 0) {
            delay(wait)
            count("lt_cmd_after_ack")
        }
    }

    private fun ackJob(key: String, job: kotlinx.coroutines.Job) {
        synchronized(lock) { ackInFlight[key] = job }
        job.invokeOnCompletion {
            synchronized(lock) {
                ackDoneAt[key] = clock()
                if (ackInFlight[key] === job) ackInFlight.remove(key)
            }
        }
    }

    /**
     * A late entry (PROTOCOL.md 4.2): an untimed entry (command or ACK) from a key report [y] locked, arriving
     * after the report, goes out at once while the report is at most [BridgeConfig.lateMaxMs] old. It uses
     * the persisted path record and lock only, so it works across a restart (R25).
     */
    private fun late(y: String, pubkey: String, now: Long) {
        if (pubkey !in mailbox.locked(y)) return
        val rec = reportPaths.get(y, now)
        if (rec == null || !rec.live || now - rec.heardAtMs > config.lateMaxMs) {
            count("lt_cmd_late_too_late")
            return
        }
        for (a in mailbox.match(y, now + 1, now, setOf(pubkey), ack = true)) sendAck(a, y, rec, now)
        val cmd = mailbox.match(y, now + 1, now, setOf(pubkey)) { planFor(it, rec, now).send != "none" }.firstOrNull() ?: return
        count("lt_cmd_late")
        deliver(cmd, y, rec, now)
    }

    // ------------------------------------------------------------------ ACK entries (PROTOCOL.md 4.3)

    /** The ACK budget (120 an hour) has room; it is consumed in [ackSend] when an ACK actually went out. */
    private fun ackAllowed(now: Long): Boolean {
        if (acksHour.allow(now, "")) return true
        count("lt_ack_limited")
        return false
    }

    /**
     * A live ACK entry for report [y]: the near form zero-hop after a random 100 to 600 ms (skipped if the
     * same Pa was heard on air), or the routed form along the reversed path (a claim round of 2 s before the
     * first routed ACK of a flood, skipped on another bridge's "ack sent" note). Never a flood.
     */
    private fun sendAck(a: Mailbox.Command, y: String, rec: ReportPaths.Entry, now: Long): Boolean {
        val plan = planFor(a, rec, now)
        if (plan.send == "none") {
            mailbox.discard(a)
            mailboxChanged()
            fl("tx_skip", "xa" to a.payload.copyOf(4), "what" to "ack", "reason" to if (rec.isDirect) "not_direct" else "no_path")
            return false
        }
        if (!ackAllowed(now)) {
            fl("tx_skip", "xa" to a.payload.copyOf(4), "what" to "ack", "reason" to "hourly_cap")
            return false
        }
        mailbox.take(a, now) // one live ACK per locked key and report
        mailboxChanged()
        busyCount.incrementAndGet()
        ackJob(y, scope.launch {
            try { ackSend(a, y, rec, plan, null) } finally { busyCount.decrementAndGet() }
        })
        return true
    }

    /** The bytes on air for an ACK entry and its plan (near form, or routed form with the path appended). */
    private fun ackDatagram(a: Mailbox.Command, plan: QuietMesh.Plan, hashSize: Int): ByteArray? {
        val near = a.payload.copyOf(QuietMesh.ACK_LEN)
        return when (plan.form) {
            "near" -> near
            "routed" -> {
                val pa = if (a.payload.size == QuietMesh.ACK_BOTH_LEN) a.payload.copyOfRange(13, 26) else near
                val hashes = plan.tagPath?.map { it.unhex() ?: return null } ?: return null
                runCatching { QuietMesh.ackRouted(pa, hashSize, hashes) }.getOrNull()
            }
            else -> null
        }
    }

    /**
     * Sends ACK entry [a] for [y] by [planned]; [atMs] for a timed ACK at a slot (no jitter, no claim, and a
     * radio error leaves it in flight for the retry). [Tx.CANCELLED]: skipped (heard on air, claim lost).
     */
    private suspend fun ackSend(a: Mailbox.Command, y: String, rec: ReportPaths.Entry, planned: Planned, atMs: Long?): Tx {
        val plan = planned.plan
        val hs = planned.hashSize
        val dg = ackDatagram(a, plan, hs) ?: run { mailbox.release(a); return Tx.CANCELLED }
        val pa = dg.copyOf(QuietMesh.ACK_LEN).hex()
        val xa = dg.copyOf(4)
        if (plan.form == "near") {
            if (atMs == null) delay(random.nextLong(QuietMesh.ACK_JITTER_MIN_MS, QuietMesh.ACK_JITTER_MAX_MS + 1))
            if (synchronized(lock) { pa in heardPa } || mailbox.payloadSent(dg)) {
                mailbox.markSent(a, clock())
                mailboxChanged()
                fl("tx_skip", "xa" to xa, "what" to "ack_near", "reason" to "dup_on_air")
                return Tx.CANCELLED
            }
        } else if (atMs == null && !rec.isDirect) {
            // the first routed ACK of a flood: bridges that heard it over hops claim (fewest hops wins)
            val t0 = clock()
            val won = claimAndWait(y, rec.hops ?: 255, QuietMesh.CLAIM_ROUTED_ACK_MS, a, rec.p?.unhex())
            val noted = synchronized(lock) { ackNotes[y] }?.let { it >= t0 - 1_000 } == true
            if (!won || noted) {
                mailbox.release(a)
                fl("tx_skip", "xa" to xa, "what" to "ack_routed", "reason" to if (noted) "ack_note" else "lost_claim")
                return Tx.CANCELLED
            }
        }
        val route = routeOf(planned) ?: return Tx.CANCELLED
        val what = if (plan.form == "near") "ack_near" else "ack_routed"
        val r = transmit(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, dg, route, "libretag", ack = true,
            what = what, idKey = "xa", id = xa, atMs = atMs)
        if (r != Tx.OK) {
            if (atMs == null || r != Tx.RADIO) mailbox.release(a)
            return r
        }
        val t = clock()
        acksHour.hit(t, "") // the budget counts ACKs on air, not ones skipped as heard or lost in a claim
        mailbox.markPayloadSent(dg, t)
        mailbox.markSent(a, t)
        // R33: a use of a path restarts the 48 h of the record and of its lock; a routed ACK for a DIRECT
        // arrival (copy 11) keeps the path under the new x, so the next entry naming it as via finds it
        if (plan.send == "path") {
            val tagPath = plan.tagPath.orEmpty()
            if (rec.isDirect) reportPaths.storePath(rec.x, tagPath, hs, t) else reportPaths.touch(rec.x, t)
            mailbox.touchLocks(rec.x, t)
            a.vias.forEach { v -> reportPaths.touch(v, t); mailbox.touchLocks(v, t) }
            fl("path_use", "x" to rec.x, "via" to a.via, "hops" to tagPath.size, "what" to what)
        }
        mailboxChanged()
        count(if (plan.form == "near") "lt_ack_near" else "lt_ack_routed")
        log(Activity.Direction.DOWN, "libretag", "ACK pre $y (${routeText(route)})")
        ackSentNote(y, if (plan.form == "near") 1 else 2, a.id, a.at, planned.pathX.takeIf { plan.form == "routed" })
        return Tx.OK
    }

    /** "Ack sent" note (PROTOCOL.md 4.3): ephemeral 24244 with the bridge key; nothing waits for it. */
    private fun ackSentNote(y: String, form: Int, entryId: String, at: Long?, via: String? = null) {
        val sk = bridgeKey ?: return
        // a routed one names the path record it used: the tag's next routed copy names it as via
        val tags = listOf(listOf("y", y), listOf("ack", form.toString()), listOf("e", entryId)) +
            listOfNotNull(via?.let { listOf("via", it) }) + listOfNotNull(at?.let { listOf("at", it.toString()) })
        val ev = NostrEvent.sign(sk, clock() / 1000, Lt2.KIND_CLAIM, tags, "")
        scope.launch { relays.publish(ev.toJson()) }
    }

    /** "Miss" note: a timed entry fell due and was not sent (radio, blocked, late, no_path). */
    private fun missNote(cmd: Mailbox.Command, reason: String) {
        count("lt_miss_$reason")
        fl("tx_skip", (if (cmd.ack) "xa" else "xc") to cmd.payload.copyOf(4), "what" to if (cmd.ack) "ack" else "cmd",
            "at" to cmd.at, "reason" to reason, "miss" to true)
        val sk = bridgeKey ?: return
        val tags = listOf(listOf("e", cmd.id)) + listOfNotNull(cmd.group?.let { listOf("g", it) }) +
            listOfNotNull(cmd.at?.let { listOf("at", it.toString()) }) + listOf(listOf("miss", reason))
        val ev = NostrEvent.sign(sk, clock() / 1000, Lt2.KIND_CLAIM, tags, "")
        scope.launch { relays.publish(ev.toJson()) }
    }

    // ------------------------------------------------------------------ commands

    private fun planHops(p: Planned, rec: ReportPaths.Entry?) = when (p.plan.send) {
        "zero-hop" -> 0
        "path" -> p.plan.path.size
        else -> rec?.hops ?: 255
    }

    private fun deliver(cmd: Mailbox.Command, y: String, rec: ReportPaths.Entry, now: Long) {
        val plan = planFor(cmd, rec, now)
        val route = routeOf(plan) ?: return
        stat { it.copy(recognised = it.recognised + 1) }
        if (openFloodBlocked(cmd, route, now)) {
            count("lt_open_floods_limited")
            return drop("libretag", "command for $y: open flood limit per hour")
        }
        mailbox.take(cmd, now) // once per x and key, whatever the election says
        mailboxChanged()
        val region = if (plan.send == "flood") floodRegion(plan.plan.scope, rec) else null
        scope.launch {
            // the claim round before every untimed send (1.5 s, PROTOCOL.md 4.1), zero-hop included
            if (!claimAndWait(y, planHops(plan, rec), config.electionMs, cmd, rec.p?.unhex())) {
                mailbox.release(cmd)
                log(Activity.Direction.INFO, "libretag", "príkaz pre $y vyšle iný most")
                fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "reason" to "lost_claim")
                return@launch
            }
            afterAck(y) // the ACK for this report goes first, the command 300 ms after it
            if (send(cmd, route, region, null) == Tx.OK) {
                log(Activity.Direction.DOWN, "libretag", "príkaz pre $y (${routeText(route)})")
                sendRef(cmd, y, rec.heardAtMs)
                afterCommandSend(cmd, plan, rec, null)
            }
        }
    }

    /** After a command went out: path use (R33) and the fallback (PROTOCOL.md 4.3). */
    private fun afterCommandSend(cmd: Mailbox.Command, planned: Planned, rec: ReportPaths.Entry?, via: String?) {
        val t = clock()
        val plan = planned.plan
        if (plan.send == "path" && rec != null) {
            reportPaths.touch(rec.x, t)
            mailbox.touchLocks(rec.x, t)
            cmd.vias.forEach { v -> reportPaths.touch(v, t); mailbox.touchLocks(v, t) }
            persist()
        }
        if (plan.fallback) armFallback(cmd, floodRegion(plan.scope, rec), via)
    }

    private fun sendRef(cmd: Mailbox.Command, x: String, heardAtMs: Long) {
        val g = cmd.group ?: return
        synchronized(lock) { sendRefs.getOrPut(g) { HashMap() }[x] = heardAtMs }
    }

    private fun armFallback(cmd: Mailbox.Command, region: String?, via: String?) {
        if (cmd.group == null) {
            count("lt_fallback_no_group") // nothing could tell its answer (PROTOCOL.md 4.3): no fallback
            return
        }
        val f = Fallback(cmd, region, clock(), via)
        synchronized(lock) { fallbacks[cmd.id] = f }
        fl("fallback", "xc" to cmd.payload.copyOf(4), "armed" to f.armedAt)
        f.job = scope.launch {
            delay(config.fallbackAfterMs)
            if (synchronized(lock) { fallbacks.remove(cmd.id) } == null) return@launch
            if (synchronized(lock) { cmd.payload.hex() in heardFallback }) {
                count("lt_fallback_dup")
                fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "fallback", "reason" to "dup_on_air")
                return@launch
            }
            if (openFloodBlocked(cmd, Route.Flood, clock())) {
                count("lt_open_floods_limited")
                fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "fallback", "reason" to "hourly_cap")
                return@launch
            }
            val r = transmit(Frames.CHANNEL_LIBRETAG, QuietMesh.DATA_TYPE_RETRY, cmd.payload, Route.Flood, "libretag",
                timed = cmd.timed, region = region, what = "fallback", idKey = "xc", id = cmd.payload.copyOf(4))
            fl("fallback", "xc" to cmd.payload.copyOf(4), "fired" to (r == Tx.OK), "ms" to clock() - f.armedAt)
            fl("path_use", "xc" to cmd.payload.copyOf(4), "via" to (via ?: cmd.via), "confirmed" to false)
            if (r == Tx.OK) {
                count("lt_fallback_fired")
                if (cmd.open) synchronized(lock) { openFloods.addLast(clock()) }
                log(Activity.Direction.DOWN, "libretag", "príkaz ${cmd.ys.first()}: záložný flood (0xFF6C)")
            }
        }
    }

    /**
     * Is a live first hearing of [x] at [heardAt] the tag's answer for group [g] (R34)? x is a y of an untimed
     * entry of g other than [sentId], and not a sibling copy of [via] (a y of the same entry as via counts only
     * 120 s or more after via).
     */
    private fun isAnswer(g: String, x: String, heardAt: Long, sentId: String?, vias: List<String>, now: Long): Boolean {
        val e = mailbox.groupEntryOf(g, x) ?: return false
        if (sentId != null && e == sentId) return false
        return vias.none { v ->
            mailbox.groupEntryOf(g, v) == e && reportPaths.get(v, now)?.let { heardAt < it.heardAtMs + QuietMesh.SIBLING_GUARD_MS } != false
        }
    }

    /**
     * A report of the tag heard live (any route, any hop count): it cancels a fallback it answers, and closes
     * the groups whose timed entries it makes useless (PROTOCOL.md 4 "Command groups", 4.3 "Group done").
     */
    private fun answers(x: String, heardAt: Long, now: Long) {
        if (!config.groupAck) return
        val groups = mailbox.groupsNaming(x)
        if (groups.isEmpty()) return
        synchronized(lock) { for (g in groups) groupHeard.getOrPut(g) { HashMap() }[x] = heardAt }
        val armed = synchronized(lock) { fallbacks.values.toList() }
        for (f in armed) {
            val g = f.cmd.group ?: continue
            if (g !in groups || !isAnswer(g, x, heardAt, f.cmd.id, listOfNotNull(f.via) + f.cmd.vias, now)) continue
            if (synchronized(lock) { fallbacks.remove(f.cmd.id) } == null) continue
            f.job?.cancel()
            count("lt_fallback_cancelled")
            fl("fallback", "xc" to f.cmd.payload.copyOf(4), "cancelled" to x, "ms" to now - f.armedAt)
            fl("answer_seen", "xc" to f.cmd.payload.copyOf(4), "x" to x, "ms" to now - f.armedAt)
            fl("path_use", "xc" to f.cmd.payload.copyOf(4), "via" to (f.via ?: f.cmd.via), "confirmed" to true)
        }
        for (g in groups) {
            if (mailbox.groupDone(g)) continue
            if (closesGroup(g, x, heardAt, now)) closeGroup(g, x, null, now, "ďalšie hlásenie trackera")
        }
    }

    /**
     * R34 for "group done": [x] answers group [g] when it is a y of an untimed entry of g and every report a
     * send of g was for, or a timed entry of g names as `via`, that is a y of the same entry was heard 120 s
     * or more before (a sibling copy of the same report is no answer; an unheard via has no time). Without
     * any such report x is the one an untimed entry waits for, not the next report: nothing to close yet,
     * and closing would refuse the timed entries the app publishes on x's grid afterwards.
     */
    /**
     * A report of the group heard after [rec] (the via of a timed entry of [g]) that answers it (R34): a y of
     * another untimed entry than the via's, or a sibling copy 120 s or more after it. Its x, or null.
     */
    private fun answeredSince(g: String, rec: ReportPaths.Entry, now: Long): String? {
        val heard = synchronized(lock) { groupHeard[g]?.toMap() }.orEmpty()
        val viaEntry = mailbox.groupEntryOf(g, rec.x)
        return heard.entries.firstOrNull { (x, t) ->
            x != rec.x && t > rec.heardAtMs && now - t <= config.timedMaxAfterReportMs &&
                (viaEntry == null || mailbox.groupEntryOf(g, x) != viaEntry || t >= rec.heardAtMs + QuietMesh.SIBLING_GUARD_MS)
        }?.key
    }

    private fun closesGroup(g: String, x: String, heardAt: Long, now: Long): Boolean {
        val e = mailbox.groupEntryOf(g, x) ?: return false
        val refs = HashMap<String, Long?>(synchronized(lock) { sendRefs[g]?.toMap() }.orEmpty())
        for (v in mailbox.groupTimed(g).flatMap { it.vias }) if (refs[v] == null) refs[v] = reportPaths.get(v, now)?.heardAtMs
        if (refs.isEmpty()) return false
        return refs.none { (r, rh) ->
            mailbox.groupEntryOf(g, r) == e && (r == x || rh == null || heardAt < rh + QuietMesh.SIBLING_GUARD_MS)
        }
    }

    /**
     * Publishes a kind 24244 claim for [y] (signed with the bridge key; untimed with the proof over the
     * report [p] heard) without waiting for any relay (R23), waits [waitMs]; true if this bridge won among
     * the claims that count ([counts]), ranked by (index in the entry's p, hops, hash).
     */
    private suspend fun claimAndWait(y: String, hops: Int, waitMs: Long, cmd: Mailbox.Command, p: ByteArray?): Boolean {
        val sk = bridgeKey ?: newKey()
        val pub = NostrEvent.xonly(sk).hex()
        val proof = if (bridgeKey != null && !cmd.timed && p != null) Market.claimProof(p, pub) else null
        val mine = Election.Claim(pub, hops, proof)
        rememberOwnKey(pub)
        val ev = Lt2.claimEvent(y, hops, clock() / 1000, sk, proof)
        scope.launch { relays.publish(ev.toJson()) }
        val t0 = clock()
        delay(waitMs)
        val now = clock()
        val rivals = claims.of(y, now).filter { counts(it, cmd, p, now) }
        val index = { pk: String -> if (cmd.open) 0 else cmd.p.indexOf(pk).takeIf { it >= 0 } ?: cmd.p.size }
        val won = Election.wins(mine, rivals, y, index)
        fl("claim", "y" to y, "own" to true, "hops" to hops, "won" to won, "wait_ms" to now - t0, "rivals" to rivals.size)
        return won
    }

    private fun rememberOwnKey(pub: String) = synchronized(lock) {
        ownClaimKeys.add(pub)
        while (ownClaimKeys.size > 256) ownClaimKeys.remove(ownClaimKeys.first { it != bridgePubkey })
    }

    /**
     * A rival claim counts (PROTOCOL.md 4.1 "Claims") when it comes from a configured peer, or, for an
     * untimed entry, when its key has a valid announcement or is named in the entry's p and its proof
     * matches the report P this bridge heard. Anything else (random keys of older bridges, strangers
     * on timed entries) is ignored.
     */
    private fun counts(c: Election.Claim, cmd: Mailbox.Command, p: ByteArray?, now: Long): Boolean {
        if (c.pubkey in peerKeys) return true
        if (cmd.timed || p == null || c.proof == null) return false
        if (!(isAnnounced(c.pubkey, now) || c.pubkey in cmd.p)) return false
        return c.proof == Market.claimProof(p, c.pubkey)
    }

    private fun isAnnounced(pubkey: String, now: Long) =
        synchronized(lock) { announced[pubkey] }?.let { it > now / 1000 } == true

    /**
     * Why a timed entry may not go out now (per-via gap, hourly cap), or null. ACKs are apart from the
     * command budget and the 10-minute gap (R3); a multi-grid send names another `via` per grid.
     */
    private fun timedBlocked(cmd: Mailbox.Command, now: Long): String? {
        if (cmd.ack) return null
        val via = cmd.via ?: return null
        val last = mailbox.lastTimedTx(via)
        if (last != null && now - last < config.timedViaGapMs) return "via_gap"
        if (mailbox.timedTxSince(now - 3600_000) >= config.timedPerHour) return "hourly_cap"
        return null
    }

    enum class Tx { OK, RADIO, BUDGET, CANCELLED }

    /** Transmits a taken mailbox entry unless it was cancelled or heard on air meanwhile. */
    private suspend fun send(cmd: Mailbox.Command, route: Route, region: String?, atMs: Long?): Tx {
        if (!mailbox.pending(cmd.id)) {
            if (!mailbox.wasSent(cmd.id)) log(Activity.Direction.INFO, "libretag", "príkaz ${cmd.ys.first()} zrušený, nevysielam")
            fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "reason" to "cancelled")
            return Tx.CANCELLED
        }
        val now = clock()
        if (mailbox.payloadSent(cmd.payload)) {
            mailbox.markSent(cmd, now)
            mailboxChanged()
            log(Activity.Direction.INFO, "libretag", "príkaz ${cmd.ys.first()}: rovnaký obsah už išiel, nevysielam")
            fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "reason" to "dup_on_air")
            return Tx.CANCELLED
        }
        if (cmd.group != null && cmd.timed && mailbox.groupDone(cmd.group)) {
            mailbox.release(cmd)
            fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "reason" to "group_done")
            return Tx.CANCELLED
        }
        if (openFloodBlocked(cmd, route, now)) {
            mailbox.release(cmd)
            count("lt_open_floods_limited")
            drop("libretag", "command ${cmd.ys.first()}: open flood limit per hour")
            fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "reason" to "hourly_cap")
            return Tx.BUDGET
        }
        val r = transmit(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, cmd.payload, route, "libretag", timed = cmd.timed,
            region = region, what = "cmd", idKey = "xc", id = cmd.payload.copyOf(4), atMs = atMs)
        if (r != Tx.OK) {
            if (r == Tx.BUDGET || !cmd.timed) mailbox.release(cmd)
            return r
        }
        val t = clock()
        if (route == Route.Flood && cmd.open) synchronized(lock) { openFloods.addLast(t); pruneOpenFloods(t) }
        mailbox.markPayloadSent(cmd.payload, t)
        if (cmd.timed) mailbox.addTimedTx(cmd.via!!, t)
        mailbox.markSent(cmd, t)
        mailboxChanged()
        return Tx.OK
    }

    // ------------------------------------------------------------------ command groups (PROTOCOL.md 4)

    private fun closeGroup(g: String, x: String, at: Long?, now: Long, why: String) {
        val n = mailbox.closeGroup(g, now)
        mailboxChanged()
        log(Activity.Direction.INFO, "libretag", "skupina $g vybavená ($why), zrušených $n časovaných príkazov")
        val tags = listOf(listOf("g", g), listOf("done", x)) + listOfNotNull(at?.let { listOf("at", it.toString()) })
        val ev = NostrEvent.sign(bridgeKey ?: newKey(), now / 1000, Lt2.KIND_CLAIM, tags, "")
        rememberOwnKey(ev.pubkey)
        scope.launch { relays.publish(ev.toJson()) }
    }

    /** "Done" claim from another bridge: accepted only with this bridge's own evidence of report x. */
    private fun onDoneClaim(ev: NostrEvent, now: Long) {
        if (!config.groupAck) return
        val t = ev.tags.filter { it.size >= 2 }.associate { it[0] to it[1] }
        val g = t["g"]?.takeIf { isLowerHex(it, Mailbox.GROUP_BYTES) } ?: return
        val x = t["done"]?.takeIf { isLowerHex(it, Lt2.TAG_LEN) } ?: return
        if (kotlin.math.abs(now / 1000 - ev.createdAt) > 30 || mailbox.groupDone(g)) return
        val timed = mailbox.groupTimed(g)
        if (timed.isEmpty() || !ev.verify()) return
        val seenS = listOfNotNull(reportPaths.get(x, now)?.heardAtMs?.div(1000), synchronized(lock) { relayReports[x] })
        val at = t["at"]?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { c -> c in '0'..'9' } }?.toLong()
        val evidence = if (mailbox.groupHasUntimedY(g, x)) {
            seenS.any { it <= now / 1000 + 5 }
        } else {
            at != null && at <= now / 1000 && seenS.any { it in (at - 5)..(at + config.ackWindowMs / 1000) } &&
                timed.any { it.at!! <= at + 5 }
        }
        if (!evidence) {
            log(Activity.Direction.INFO, "libretag", "claim „vybavené“ pre skupinu $g bez dôkazu, ignorujem")
            return
        }
        val n = mailbox.closeGroup(g, now)
        mailboxChanged()
        log(Activity.Direction.INFO, "libretag", "skupina $g vybavená podľa iného mostu, zrušených $n časovaných príkazov")
    }

    // ------------------------------------------------------------------ timed entries (slots, PROTOCOL.md 2.6, 4.3)

    /** The earliest `at` (ms) of a waiting timed entry, for the app's exact alarm (R24); null when none waits. */
    fun nextDueMs(now: Long = clock()): Long? = mailbox.nextTimedAtMs(now)

    /**
     * Timed entries: starts every entry whose time (its `at` minus the claim lead) has come. A command goes
     * out at `at` by the plan for its `via` (zero-hop, along the path, or a flood in the report's scope), only
     * from a bridge that heard `via` and from the key `via` locked; a blind LOST entry floods in the country
     * scope. It counts as sent only once the radio took the frame: a radio error is retried every 2 s until
     * `at + 20 s` (R20); a miss, a block or a lateness is published as a miss note. Call about twice a
     * second, and from the exact alarm.
     */
    fun timedTick() {
        val now = clock()
        reportPaths.prune(now)
        if (!config.libretagCommands || !config.timedCommands) return
        // ACKs first: a command for the same slot waits for its ACK (PROTOCOL.md 4.3)
        val due = mailbox.dueTimed(now + config.timedClaimLeadMs, now).sortedWith(compareBy({ it.at }, { if (it.ack) 0 else 1 }))
        for (cmd in due) {
            val y = cmd.ys.first()
            val atMs = cmd.at!! * 1000
            // the via this bridge heard whose lock holds the entry's key (one via per copy of the anchor)
            val recs = cmd.vias.mapNotNull { reportPaths.get(it, now) }
            val rec = recs.firstOrNull { cmd.pubkey in mailbox.locked(it.x) } ?: recs.firstOrNull()
            if (cmd.blind) {
                startBlind(cmd, now)
                continue
            }
            if (rec == null) {
                mailbox.discard(cmd) // not ours: no sign that this bridge is near the tracker
                mailboxChanged()
                log(Activity.Direction.INFO, "libretag", "časovaný príkaz pre $y: report ${cmd.via} som nepočul")
                continue
            }
            if (cmd.pubkey !in mailbox.locked(rec.x)) {
                mailbox.discard(cmd)
                mailboxChanged()
                count("lt_cmd_unsigned")
                drop("libretag", "timed command for $y: not signed by the key report ${rec.x} committed to")
                continue
            }
            if (!atPlausible(atMs, rec)) {
                mailbox.discard(cmd)
                mailboxChanged()
                drop("libretag", "časovaný príkaz pre $y: čas mimo okna hlásenia ${rec.x}")
                continue
            }
            val plan = planFor(cmd, rec, now)
            if (plan.send == "none") {
                mailbox.discard(cmd) // format 2: the route binds; not ours to send, the y stays open
                mailboxChanged()
                if (bridgePubkey != null && bridgePubkey in cmd.p) missNote(cmd, "no_path")
                log(Activity.Direction.INFO, "libretag", "timed entry for $y asks for a nearer bridge, not sending")
                continue
            }
            mailbox.take(cmd, now) // one delivery per event and per tag
            mailboxChanged()
            if (now > atMs + QuietMesh.TIMED_RETRY_UNTIL_MS) {
                mailbox.release(cmd)
                missNote(cmd, "late") // the bridge was not running at `at`
                continue
            }
            if (!cmd.ack && mailbox.payloadSent(cmd.payload)) {
                mailbox.markSent(cmd, now)
                mailboxChanged()
                continue
            }
            if (cmd.group != null && config.groupAck && !mailbox.groupDone(cmd.group)) {
                // the tracker reported again after this entry's anchor: the slot is useless (R34)
                answeredSince(cmd.group, rec, now)?.let { x -> closeGroup(cmd.group, x, null, now, "ďalšie hlásenie trackera pred slotom") }
            }
            if (cmd.group != null && mailbox.groupDone(cmd.group)) {
                mailbox.release(cmd)
                fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "at" to cmd.at, "reason" to "group_done")
                continue
            }
            val blocked = timedBlocked(cmd, now)
            if (blocked != null) {
                mailbox.release(cmd)
                drop("libretag", "časovaný príkaz pre $y: $blocked") // no claim either: we would not send
                fl("tx_skip", "xc" to cmd.payload.copyOf(4), "what" to "cmd", "at" to cmd.at, "reason" to blocked)
                missNote(cmd, "blocked")
                continue
            }
            val route = routeOf(plan)!!
            if (cmd.ack && !ackAllowed(now)) { // the ACK budget (120 an hour) holds for timed ACKs too
                mailbox.release(cmd)
                missNote(cmd, "blocked")
                continue
            }
            if (!cmd.ack && openFloodBlocked(cmd, route, now)) {
                mailbox.release(cmd)
                count("lt_open_floods_limited")
                missNote(cmd, "blocked")
                continue
            }
            busyCount.incrementAndGet()
            val job = scope.launch {
                try { runTimed(cmd, rec, plan, route, atMs) } finally { busyCount.decrementAndGet() }
            }
            if (cmd.ack) ackJob(slotKey(cmd), job)
        }
    }

    /** The window of a timed entry: its slot, under the anchor key that signs both the ACK and the command. */
    private fun slotKey(cmd: Mailbox.Command) = "${cmd.at}:${cmd.pubkey}"

    private suspend fun runTimed(cmd: Mailbox.Command, rec: ReportPaths.Entry, plan: Planned, route: Route, atMs: Long) {
        val y = cmd.ys.first()
        if (cmd.ack) {
            // an ACK at a slot: no claim, sent at `at` (never more than 2 s early)
            delay(maxOf(atMs - clock(), 0L))
            if (!timedRetry(cmd, atMs) { ackSend(cmd, y, rec, plan, atMs) != Tx.RADIO }) {
                mailbox.release(cmd)
                missNote(cmd, "radio")
            }
            return
        }
        // the claim goes out the lead before `at` (without waiting for a relay, R23); decided at `at`
        delay(maxOf(atMs - config.timedClaimLeadMs - clock(), 0L))
        val wait = maxOf(atMs - clock(), 0L)
        if (!claimAndWait(y, planHops(plan, rec), maxOf(wait, config.electionMs), cmd, null)) {
            mailbox.release(cmd)
            log(Activity.Direction.INFO, "libretag", "príkaz pre $y vyšle iný most")
            return
        }
        val region = if (plan.send == "flood") floodRegion(plan.plan.scope, rec) else null
        afterAck(slotKey(cmd)) // an ACK for the same slot goes first, the command 300 ms after it
        var result = Tx.RADIO
        val ok = timedRetry(cmd, atMs) {
            result = send(cmd, route, region, atMs)
            result == Tx.OK || result == Tx.CANCELLED || result == Tx.BUDGET
        }
        when {
            ok && result == Tx.OK -> {
                log(Activity.Direction.DOWN, "libretag", "časovaný príkaz pre $y (${routeText(route)})")
                sendRef(cmd, rec.x, rec.heardAtMs)
                afterCommandSend(cmd, plan, rec, rec.x)
            }
            result == Tx.BUDGET -> missNote(cmd, "blocked")
            !ok -> {
                mailbox.release(cmd)
                missNote(cmd, "radio")
            }
        }
    }

    /** Runs [attempt] at once and after a radio error every 2 s until `at + 20 s`; true when it settled. */
    private suspend fun timedRetry(cmd: Mailbox.Command, atMs: Long, attempt: suspend () -> Boolean): Boolean {
        var tries = 0
        while (true) {
            tries++
            if (attempt()) return true
            if (clock() + QuietMesh.TIMED_RETRY_MS > atMs + QuietMesh.TIMED_RETRY_UNTIL_MS) return false
            if (!mailbox.pending(cmd.id)) return false
            count("lt_timed_retry")
            delay(QuietMesh.TIMED_RETRY_MS)
        }
    }

    /** Blind LOST (PROTOCOL.md 4.3): flood at `at` in the country scope without `via` heard; 3 per via in 24 h. */
    private fun startBlind(cmd: Mailbox.Command, now: Long) {
        val via = cmd.via ?: return
        if (bridgePubkey == null || bridgePubkey !in cmd.p) { mailbox.discard(cmd); return }
        mailbox.take(cmd, now)
        mailboxChanged()
        val atMs = cmd.at!! * 1000
        if (now > atMs + QuietMesh.TIMED_RETRY_UNTIL_MS) { mailbox.release(cmd); missNote(cmd, "late"); return }
        if (mailbox.blindTxSince(via, now - 86400_000L) >= QuietMesh.BLIND_PER_DAY) {
            mailbox.release(cmd)
            missNote(cmd, "blocked")
            return
        }
        val plan = planFor(cmd, null, now, blind = true)
        busyCount.incrementAndGet()
        scope.launch {
            try {
                delay(maxOf(atMs - clock(), 0L))
                var result = Tx.RADIO
                val ok = timedRetry(cmd, atMs) {
                    result = send(cmd, Route.Flood, floodRegion(plan.plan.scope, null), atMs)
                    result != Tx.RADIO
                }
                if (ok && result == Tx.OK) {
                    mailbox.addBlindTx(via, clock())
                    log(Activity.Direction.DOWN, "libretag", "slepý LOST pre ${cmd.ys.first()} (flood)")
                } else if (result == Tx.BUDGET) missNote(cmd, "blocked") else if (!ok) { mailbox.release(cmd); missNote(cmd, "radio") }
            } finally { busyCount.decrementAndGet() }
        }
    }

    private fun routeText(r: Route) = when (r) {
        Route.Flood -> "flood"
        Route.ZeroHop -> "zero-hop"
        is Route.Direct -> "direct, ${r.hops} hop${if (r.hops == 1) "" else "y"}"
    }

    // ------------------------------------------------------------------ relays -> bridge

    /** Filters the bridge needs on the relays, by subscription id. */
    fun subscriptions(): Map<String, List<JsonObject>> {
        val nowS = clock() / 1000
        val out = linkedMapOf<String, List<JsonObject>>()
        val lt = mutableListOf<JsonObject>()
        if ("libretag" in config.uplinkProfiles) lt += filter(Lt2.KIND_REPORT, nowS - 600)
        if (config.libretagCommands) lt += filter(Lt2.KIND_CLAIM, nowS - 60)
        if (lt.isNotEmpty()) out["nm-lt"] = lt
        if (config.libretagCommands) {
            val since = maxOf(nowS - Lt2.COMMAND_MAX_TTL_MS / 1000, lastCommandAt - 600)
            // every 4244, also with accept_open off (PROTOCOL.md 4.1): anchors carry no tag a filter could select,
            // and without them no key locks and no ACK entry (4.3) goes out; open commands drop on arrival
            out["nm-cmd"] = listOf(filter(Lt2.KIND_COMMAND, since))
            // announced bridges (their untimed claims count with a proof)
            out["nm-ann"] = listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(Market.ANNOUNCE_KIND)) })
                put("#k", buildJsonArray { add(JsonPrimitive(Lt2.KIND_COMMAND.toString())) })
            })
            // NIP-09 deletions of mailbox events: from the start of the window, a cancel may come before its event
            out["nm-del"] = listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(Lt2.KIND_DELETION)) })
                put("#k", buildJsonArray { add(JsonPrimitive(Lt2.KIND_COMMAND.toString())) })
                put("since", nowS - Lt2.COMMAND_MAX_TTL_MS / 1000)
            })
        }
        dmSubscription()?.let { out["nm-dm"] = it }
        return out
    }

    private fun filter(kind: Int, since: Long) = buildJsonObject {
        put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
        put("since", since)
    }

    private fun tagFilter(kind: Int, tag: String, values: List<String>, since: Long) = buildJsonObject {
        put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
        put(tag, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        put("since", since)
    }

    /** Configured networks that are valid names. */
    private fun networks() = config.networks.map { it.trim() }.filter { Market.isNetwork(it) }.distinct()

    // ------------------------------------------------------------------ announcements (PROTOCOL.md 4.1)

    /** Kind 38244 events for each network this bridge serves (d = "" when none), or empty without a key. */
    fun announcements(now: Long = clock()): List<NostrEvent> {
        val sk = bridgeKey ?: return emptyList()
        val c = config
        val caps = mesh.capabilities
        val content = buildJsonObject {
            put("name", c.bridgeName)
            put("version", c.appVersion)
            put("adapter", ADAPTER)
            put("zero_hop", Caps.TX_PATH_DATA in caps)
            put("direct", Caps.TX_PATH_DATA in caps)
        }.toString()
        return networks().ifEmpty { listOf("") }.map { net ->
            val tags = Market.announceTags(net, now / 1000, regions = regions(),
                relays = relays.publishUrls, acceptOpen = c.acceptOpen, receipts = c.receipts,
                anchorLeadS = c.anchorLeadMs / 1000, lateMaxS = c.lateMaxMs / 1000,
                radio = radio.announced())
            NostrEvent.sign(sk, now / 1000, Market.ANNOUNCE_KIND, tags, content)
        }
    }

    @Volatile private var announcedRadio: Pair<String, Long>? = null

    /**
     * Publishes the announcements at start, every [BridgeConfig.announceIntervalMs] (an hour) and after a
     * change of the radio state (the minute tick calls it, so within a minute, R13); true when it did.
     */
    suspend fun announceIfDue(now: Long = clock()): Boolean {
        val c = config
        if (!c.announce || !c.libretagCommands || bridgeKey == null) return false
        val last = lastAnnounceMs
        val radioNow = radio.announced()
        if (last != null && now - last < c.announceIntervalMs && radioNow == announcedRadio) return false
        lastAnnounceMs = now
        announcedRadio = radioNow
        for (ev in announcements(now)) publishKept(ev.toJson(), "ann:" + ev.id, "libretag", "lt_announced")
        log(Activity.Direction.UP, "libretag", "bridge announcement published")
        return true
    }

    private fun onAnnouncement(ev: NostrEvent, now: Long) {
        val a = Market.parseAnnouncement(ev, now / 1000) ?: return
        if (!ev.verify()) return
        synchronized(lock) { announced[a.pubkey] = maxOf(a.expires, announced[a.pubkey] ?: 0L) }
    }

    fun dmSubscription(): List<JsonObject>? {
        if (!config.downlinkDm || devices.isEmpty()) return null
        return listOf(buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(4)) })
            put("#p", buildJsonArray { devices.forEach { add(JsonPrimitive(it.pubkey.hex())) } })
            put("since", clock() / 1000 - 60)
        })
    }

    /** Smallest (receive time - created_at) of ephemeral events this hour: the clock offset against the relays. */
    @Volatile private var clockMinMs: Long? = null
    @Volatile private var clockLoggedAt = 0L

    /** Hourly `clock` line of the field log (9.9): this phone's offset against the relays' event times. */
    fun clockTick(now: Long = clock()) {
        if (now - clockLoggedAt < 3600_000L) return
        clockLoggedAt = now
        clockMinMs?.let { fl("clock", "offset_ms" to it) }
        clockMinMs = null
    }

    suspend fun onRelayEvent(raw: JsonObject, relay: String = "") {
        val ev = NostrEvent.fromJson(raw) ?: return
        val now = clock()
        if (ev.kind == Lt2.KIND_CLAIM && ev.pubkey !in synchronized(lock) { ownClaimKeys.toSet() }) {
            val d = now - ev.createdAt * 1000
            if (kotlin.math.abs(d) < 600_000) clockMinMs = minOf(clockMinMs ?: d, d)
        }
        when (ev.kind) {
            Lt2.KIND_REPORT -> {
                val (y, content) = Lt2.parseReport(ev) ?: return
                if (ev.verify()) {
                    dedup.saw(y, content, relay, now)
                    // an ["approx", …] report counts with its earliest possible time
                    val seen = Lt2.reportTime(ev).first
                    synchronized(lock) { relayReports[y] = maxOf(seen, relayReports[y] ?: 0L) }
                }
            }
            Lt2.KIND_CLAIM -> {
                // "ack sent" note of another bridge: a routed ACK for the same y is skipped (PROTOCOL.md 4.3)
                if (ev.tags.any { it.size >= 2 && it[0] == "ack" }) {
                    if (ev.pubkey in synchronized(lock) { ownClaimKeys.toSet() }) return
                    val y = ev.tags.firstOrNull { it.size >= 2 && it[0] == "y" }?.get(1)?.takeIf { isLowerHex(it, Lt2.TAG_LEN) } ?: return
                    if (now / 1000 - ev.createdAt > 60 || !ev.verify()) return
                    synchronized(lock) { ackNotes[y] = now }
                    return
                }
                if (ev.tags.any { it.size >= 2 && it[0] == "miss" }) return
                if (ev.tags.any { it.size >= 2 && it[0] == "done" }) {
                    if (ev.pubkey !in synchronized(lock) { ownClaimKeys.toSet() }) onDoneClaim(ev, now)
                    return
                }
                val c = Lt2.parseClaim(ev) ?: return
                if (ev.pubkey in synchronized(lock) { ownClaimKeys.toSet() }) return
                if (now / 1000 - ev.createdAt > 60 || !ev.verify()) return
                // stored whatever the key; whether it counts is decided per entry at election time
                claims.add(c.y, Election.Claim(ev.pubkey, c.hops, c.proof), now)
            }
            Market.ANNOUNCE_KIND -> onAnnouncement(ev, now)
            Lt2.KIND_COMMAND -> onCommandEvent(ev, relay, now)
            Lt2.KIND_DELETION -> onDeletion(ev, now)
            4 -> onRelayDm(ev, now)
        }
    }

    private fun onCommandEvent(ev: NostrEvent, relay: String, now: Long) {
        if (!config.libretagCommands) return
        val c = config
        val cmd = Mailbox.parse(ev, now, c.timedCommands, c.timedMaxLateMs, c.futureMaxMs, relay) ?: return
        fl("mailbox", "id" to ev.id, "kind" to entryKind(cmd), "y" to cmd.ys, "via" to cmd.vias.ifEmpty { null }, "at" to cmd.at,
            "route" to cmd.route, "fb" to cmd.fb.takeIf { !it }, "g" to cmd.group, "p" to cmd.p.map { it.take(8) }.ifEmpty { null },
            "created_at" to ev.createdAt, "known_at" to now)
        notForUs(cmd)?.let {
            count("lt_not_for_us")
            return
        }
        // signed mailbox (PROTOCOL.md 4.2): once a report was heard, only a key that locked or still may lock
        var entry = cmd
        if (cmd.timed && !cmd.blind) {
            if (cmd.vias.none { mayLock(it, ev.pubkey, now) }) {
                count("lt_mailbox_unsigned")
                return drop("libretag", "timed command for ${cmd.via}: not from the key the report committed to")
            }
        } else {
            val live = cmd.ys.filter { mayLock(it, ev.pubkey, now) }
            if (live.isEmpty()) {
                count("lt_mailbox_unsigned")
                return drop("libretag", "command for ${cmd.ys.first()}: not from the key the report committed to")
            }
            // at most keysPerY keys per report tag, the first seen stay (the owner's anchor names y first)
            val room = live.filter { y -> mailbox.keysOfY(y).let { ev.pubkey in it || it.size < c.keysPerY } }
            if (room.isEmpty()) {
                count("lt_mailbox_y_full")
                return
            }
            if (room.size != cmd.ys.size) entry = cmd.withYs(room)
        }
        if (cmd.timed) {
            val rp = cmd.vias.firstNotNullOfOrNull { reportPaths.get(it, now) }
            if (rp != null && !atPlausible(cmd.at!! * 1000, rp)) return drop("libretag", "časovaný príkaz mimo okna hlásenia ${rp.x}")
        }
        if (!ev.verify()) return
        lastCommandAt = maxOf(lastCommandAt, ev.createdAt)
        if (mailbox.isCancelled(ev.id, ev.pubkey)) return // the deletion came first
        if (cmd.timed && cmd.group != null && mailbox.groupDone(cmd.group)) return // the tracker already answered
        if (entry.anchor) {
            val added = entry.ys.count { mailbox.addAnchor(it, ev.pubkey, ev.id, now, entry.expiresAtMs) }
            count(if (added > 0) "lt_anchor_added" else "lt_anchor_again")
            if (added > 0) persist() // anchors are saved at once: a kill must not cost the next report its lock
            return
        }
        if (mailbox.add(entry, now, if (entry.timed) c.timedPerViaMax else null, c.mailboxRelayShare)) {
            count(if (entry.timed) "lt_mailbox_added_timed" else "lt_mailbox_added")
            mailboxChanged()
            if (!entry.timed) entry.ys.forEach { late(it, ev.pubkey, now) }
        }
    }

    /**
     * Why this bridge does not store [cmd] (PROTOCOL.md 4.1), or null: an entry naming other bridges
     * only; an open one while open entries are off; an `r` outside the configured networks.
     */
    private fun entryKind(c: Mailbox.Command) = when {
        c.anchor -> "anchor"
        c.ack -> "ack"
        c.timed -> "timed"
        else -> "cmd"
    }

    private fun notForUs(cmd: Mailbox.Command): String? {
        val c = config
        if (cmd.open) {
            // an ACK entry can never cause a flood: it passes a bridge that serves no open entries (PROTOCOL.md 4.3);
            // so does an anchor, which only names keys, or no ACK entry could ever lock
            if (!c.acceptOpen && !cmd.ack && !cmd.anchor) return "open"
        } else if (bridgePubkey == null || bridgePubkey !in cmd.p) {
            return "other bridges"
        }
        val nets = networks()
        if (nets.isNotEmpty() && cmd.r != null && cmd.r !in nets) return "other network"
        return null
    }

    /**
     * A1: a slot comes after report `via` and before its heartbeat (24 h + 1 h) from the last use of the
     * `via` record: a standing tag keeps its anchor while ACKs at slots and path uses renew the record (R33).
     */
    private fun atPlausible(atMs: Long, rec: ReportPaths.Entry) =
        atMs >= rec.heardAtMs - config.timedMaxLateMs && atMs <= rec.lastUseMs + config.timedMaxAfterReportMs

    /** NIP-09 kind 5 for mailbox events: cancels an entry signed by the same key (PROTOCOL.md 4). */
    private fun onDeletion(ev: NostrEvent, now: Long) {
        if (!config.libretagCommands) return
        val ks = ev.tags.filter { it.size >= 2 && it[0] == "k" }.map { it[1] }.toSet()
        if (ks.isNotEmpty() && Lt2.KIND_COMMAND.toString() !in ks) return
        val ids = ev.tags.filter { it.size >= 2 && it[0] == "e" && isLowerHex(it[1], 32) }.map { it[1] }
            .distinct().take(Lt2.MAX_DELETE_IDS)
        if (ids.isEmpty() || ev.createdAt > now / 1000 + 300 || !ev.verify()) return
        for (id in ids) {
            when (mailbox.cancel(id, ev.pubkey, now)) {
                Mailbox.Cancel.CANCELLED -> log(Activity.Direction.INFO, "libretag", "príkaz ${id.take(8)} zrušený")
                Mailbox.Cancel.SENT -> log(Activity.Direction.INFO, "libretag", "príkaz ${id.take(8)} zrušený, ale už bol vyslaný")
                Mailbox.Cancel.OTHER_KEY, Mailbox.Cancel.UNKNOWN -> {}
            }
        }
        mailboxChanged()
    }

    // ------------------------------------------------------------------ relays -> mesh (DMs)

    private fun onRelayDm(ev: NostrEvent, now: Long) {
        if (!config.downlinkDm) return
        if (Frames.dm4Shape(ev) == null) return
        if (seenBefore(ev.id, now, relay = true, mark = {})) return
        if (!ev.verify()) return drop("dm4.ref", "neplatný podpis z relayu")
        val dev = deviceOf(ev.tags[0][1].unhex()!!) ?: return
        val author = ev.pubkey.unhex()!!
        if (dev.allowFrom.none { it.contentEquals(author) }) return drop("dm4.ref", "${dev.name}: autor nemá povolenie")
        if (now / 1000 - ev.createdAt > 7 * 86400 || ev.createdAt > now / 1000 + 300) return
        val frame = try {
            Frames.encodeDm(ev, ref = true)
        } catch (e: FrameException) {
            return drop("dm4.ref", e.code)
        }
        // No fragments downstream: one packet or nothing (review point 6).
        if (frame.size > mesh.maxPayload) return drop("dm4.ref", "správa sa nezmestí do jedného paketu")
        val out = Outgoing(ev.id, frame, dev.pubkey.hex(), now + config.queueTtlMs, "DM pre ${dev.name}")
        val recent = synchronized(lock) { lastHeard[dev.pubkey.hex()] }?.takeIf { now - it.first <= AWAKE_ROUTE_MS }
        val route = recent?.let { downlinkRoute(it.second, mesh.capabilities, false, config.maxDirectHops) }
        if (!dev.sleeps && route != null) {
            scope.launch { transmitDm(out, random.nextLong(config.holdMinMs, config.holdMaxMs + 1), route) }
            return
        }
        synchronized(lock) {
            val q = queues.getOrPut(dev.pubkey.hex()) { mutableListOf() }
            if (q.size >= config.queuePerDevice) q.removeAt(0)
            q.add(out)
        }
        stat { it.copy(queued = synchronized(lock) { queues.values.sumOf { q -> q.size } }) }
        log(Activity.Direction.INFO, "dm4.ref", "${out.label} čaká, kým sa ozve")
    }

    private fun flush(deviceHex: String, meta: RxMeta) {
        val route = downlinkRoute(meta, mesh.capabilities, false, config.maxDirectHops)
        if (route == null) {
            if (synchronized(lock) { queues[deviceHex]?.isNotEmpty() } == true) {
                log(Activity.Direction.INFO, "dm4.ref", "zariadenie je ďaleko alebo server nevie cestu, správy čakajú")
            }
            return
        }
        val q = synchronized(lock) { queues.remove(deviceHex) } ?: return
        stat { it.copy(queued = synchronized(lock) { queues.values.sumOf { it.size } }) }
        val now = clock()
        q.filter { it.expires > now }.forEach { out -> scope.launch { transmitDm(out, random.nextLong(500, 2_001), route) } }
    }

    private suspend fun transmitDm(out: Outgoing, holdMs: Long, route: Route) {
        delay(holdMs)
        val now = clock()
        if (synchronized(lock) { ledger[out.eventId]?.let { it.heard || it.sent } } == true) return
        if (!dmHour.allow(now, "") || !dmDevice.allow(now, out.device)) return drop("dm4.ref", "limit DM za hodinu")
        if (transmit(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, out.payload, route, "dm4.ref") != Tx.OK) return
        dmHour.hit(now, ""); dmDevice.hit(now, out.device)
        synchronized(lock) { ledger[out.eventId]?.sent = true }
        changes.incrementAndGet()
        log(Activity.Direction.DOWN, "dm4.ref", "${out.label} (${routeText(route)})")
    }

    /**
     * One own transmission within the airtime budget (ACKs apart from it: their own hourly cap). Logs `tx`
     * with what it was and how it went, and feeds the radio health ([RadioHealth.onSend]).
     */
    private suspend fun transmit(
        channel: String, dataType: Int, payload: ByteArray, route: Route, profile: String, timed: Boolean = false,
        region: String? = if (route == Route.Flood) regions().firstOrNull() else null, ack: Boolean = false,
        what: String? = null, idKey: String = "xc", id: ByteArray? = null, atMs: Long? = null,
    ): Tx =
        txLock.withLock {
            val now = clock()
            val pathBytes = (route as? Route.Direct)?.path?.size ?: 0
            val preset = mesh.preset ?: config.preset
            val ms = Airtime.ms(Airtime.channelPacketBytes(payload.size, pathBytes, region != null && route == Route.Flood), preset) *
                Airtime.transmissions(route)
            val how = when (route) { Route.Flood -> "flood"; Route.ZeroHop -> "zero-hop"; is Route.Direct -> "path" }
            val path = (route as? Route.Direct)?.let { FieldLog.pathList(it.path, it.hashSize) }
            val skip = { reason: String ->
                fl("tx_skip", idKey to id, "what" to what, "how" to how, "at" to atMs?.div(1000), "reason" to reason)
            }
            // #libretag has its own budget: a flood of LibreTag commands cannot silence DMs or Roadbeacon
            val budget = if (channel == Frames.CHANNEL_LIBRETAG) ltBudget else budget
            if (!ack && !budget.allow(now, ms)) {
                count("tx_airtime_exhausted")
                drop(profile, "rozpočet airtime vyčerpaný (${budget.usedMs(now).toInt()} ms za hodinu)")
                skip("airtime")
                return@withLock Tx.BUDGET
            }
            // timed transmissions use at most their share, the rest stays for commands that answer a report
            val timedBudget = timed && !ack && channel == Frames.CHANNEL_LIBRETAG
            if (timedBudget && !ltTimedBudget.allow(now, ms)) {
                count("tx_airtime_timed_exhausted")
                drop(profile, "airtime share for timed commands used up (${ltTimedBudget.usedMs(now).toInt()} ms per hour)")
                skip("airtime")
                return@withLock Tx.BUDGET
            }
            val r = mesh.send(channel, dataType, payload, route, region)
            val t = clock()
            radioEvent(radio.onSend(r.ok, r.error, t), t)
            if (channel == Frames.CHANNEL_LIBRETAG) {
                fl("tx", idKey to id, "what" to what, "how" to how, "path" to path, "region" to region,
                    "dtype" to dataType, "len" to payload.size, "at" to atMs?.div(1000),
                    "late_ms" to atMs?.let { t - it }, "airtime_s" to ms / 1000, "ok" to r.ok, "error" to r.error)
            }
            if (!r.ok) {
                drop(profile, "rádio odmietlo" + (r.error?.let { " ($it)" } ?: ""))
                return@withLock Tx.RADIO
            }
            if (!ack) budget.spend(now, ms)
            if (timedBudget) ltTimedBudget.spend(now, ms)
            count("tx_airtime_ms", ms.toLong())
            changes.incrementAndGet()
            stat { it.copy(toRadio = it.toRadio + 1, airtimeMs = (this.budget.usedMs(now) + ltBudget.usedMs(now)).toInt()) }
            Tx.OK
        }

    // ------------------------------------------------------------------ radio health (SPEC 3.2, R37)

    /** MeshCore Open says whether its radio is usable (`device.connected`, the session's NoRadio state). */
    fun onRadioApi(connected: Boolean, now: Long = clock()) = radioEvent(radio.onApi(connected, now), now)

    /** An answer to the `radio.stats` probe counts as radio activity. */
    fun onRadioProbe(answered: Boolean, now: Long = clock()) = radioEvent(radio.onProbe(answered, now), now)

    /** The minute tick: silence of the radio itself for [BridgeConfig.rxSilenceMs]. */
    fun radioTick(now: Long = clock()) = radioEvent(radio.tick(now), now)

    private fun radioEvent(t: RadioHealth.Transition?, now: Long) {
        t ?: return
        count(if (t.up) "radio_up" else "radio_down")
        fl(if (t.up) "radio_up" else "radio_down", "reason" to t.reason, "since" to t.since / 1000)
        log(Activity.Direction.INFO, "radio", if (t.up) "rádio funguje" else "rádio nefunguje (${t.reason})")
        changes.incrementAndGet()
        // the announcement says so within a minute (R13): at once here, the minute tick retries
        scope.launch { runCatching { announceIfDue(clock()) } }
    }

    // ------------------------------------------------------------------ durable publishing

    /**
     * Publishes [json]; when no relay takes it, it stays in the outbox (saved with the state) and
     * [retryOutbox] tries again until it expires. Port of `Bridge.publish_durable` (bridge.py).
     */
    suspend fun publishKept(json: JsonObject, ledgerKey: String, profile: String, stat: String): Boolean {
        val id = (json["id"] as? JsonPrimitive)?.content ?: return false
        val now = clock()
        outbox.put(id, json.toString(), ledgerKey, profile, now, Outbox.expirationMs(json))
        outboxChanged()
        val ok = relays.publish(json)
        if (ok) {
            outbox.done(id)
            published(ledgerKey, stat)
        } else {
            outbox.release(id) // first retry [Outbox.BACKOFF_MS][0] after the put, as in ledger.py
            count("publish_deferred")
        }
        outboxChanged()
        return ok
    }

    private fun published(ledgerKey: String, stat: String) {
        synchronized(lock) { ledger[ledgerKey]?.published = true }
        count(stat)
        stat { it.copy(published = it.published + 1) }
    }

    private fun outboxChanged() {
        changes.incrementAndGet()
        stat { it.copy(outbox = outbox.size) }
    }

    /** Once a minute: due outbox entries go to the relays again; expired ones are dropped. */
    suspend fun retryOutbox(): Int {
        val now = clock()
        val (due, expired) = outbox.due(now)
        expired.forEach {
            count("outbox_expired")
            drop(it.profile, "expired before any relay took it")
        }
        var sent = 0
        for (e in due) {
            val json = runCatching { Json.parseToJsonElement(e.event) as? JsonObject }.getOrNull()
            if (json == null) { outbox.done(e.id); continue }
            if (relays.publish(json)) {
                outbox.done(e.id)
                published(e.key, "outbox_published")
                log(Activity.Direction.UP, e.profile, "published from the outbox (retry ${e.tries + 1})")
                sent++
            } else {
                outbox.failed(e.id, clock())
            }
        }
        if (due.isNotEmpty() || expired.isNotEmpty()) outboxChanged()
        return sent
    }

    val outboxSize: Int get() = outbox.size

    // ------------------------------------------------------------------ heartbeat

    /** The watchdog reconnected the Local API client (counted in the status). */
    fun watchdogReconnected() = count("watchdog_reconnect")

    /** Same six numbers as `Bridge.summary` in bridge.py. */
    private fun summary(): Map<String, Double> {
        val s = _stats.value
        return linkedMapOf(
            "heard" to s.heard.toDouble(),
            "published" to s.published.toDouble(),
            "dropped" to s.dropped.toDouble(),
            "spam_rejected" to counters.filterKeys { it.startsWith("lt_spam_") }.values.sum().toDouble(),
            "sent_to_radio" to s.toRadio.toDouble(),
            "airtime_s" to (counters["tx_airtime_ms"] ?: 0L) / 1000.0,
        )
    }

    /** Same keys as the host bridge's status.json (`Bridge.status` in bridge.py). */
    fun status(now: Long = clock(), lastActivity: Long = lastRxMs): JsonObject {
        val total = summary()
        val base = heartbeatBase
        return buildJsonObject {
            put("at", now / 1000)
            put("started_at", startedAt / 1000)
            put("interval_s", config.heartbeatMs / 1000.0)
            put("total", buildJsonObject { total.forEach { (k, v) -> put(k, v) } })
            put("last_hour", buildJsonObject { total.forEach { (k, v) -> put(k, v - (base[k] ?: 0.0)) } })
            put("airtime_window_s", buildJsonObject {
                put("main", budget.usedMs(now) / 1000); put("libretag", ltBudget.usedMs(now) / 1000)
            })
            put("airtime_cap_s", buildJsonObject {
                put("main", budget.budgetMs / 1000); put("libretag", ltBudget.budgetMs / 1000)
            })
            put("radio_silence_s", (now - maxOf(lastActivity, startedAt)) / 1000.0)
            put("radio", if (radio.up) "up" else "down")
            put("radio_since", radio.since / 1000)
            put("mailbox_tags", mailbox.size)
            put("outbox", outbox.size)
            put("counters", buildJsonObject { counters.toSortedMap().forEach { (k, v) -> put(k, v) } })
        }
    }

    /** Due once per [BridgeConfig.heartbeatMs]. */
    fun heartbeatDue(now: Long = clock()) = now - lastHeartbeat >= config.heartbeatMs

    /** The status and its log line; starts a new "last hour". */
    fun heartbeat(now: Long = clock(), lastActivity: Long = lastRxMs): Pair<JsonObject, String> {
        val st = status(now, lastActivity)
        val base = heartbeatBase
        val h = summary().mapValues { (k, v) -> v - (base[k] ?: 0.0) }
        val line = String.format(
            java.util.Locale.ROOT,
            "heartbeat heard=%d published=%d dropped=%d spam_rejected=%d sent=%d airtime=%.1fs " +
                "(window main %.1f/%.1f s, libretag %.1f/%.1f s) mailbox=%d outbox=%d",
            h.getValue("heard").toInt(), h.getValue("published").toInt(), h.getValue("dropped").toInt(),
            h.getValue("spam_rejected").toInt(), h.getValue("sent_to_radio").toInt(), h.getValue("airtime_s"),
            budget.usedMs(now) / 1000, budget.budgetMs / 1000, ltBudget.usedMs(now) / 1000, ltBudget.budgetMs / 1000,
            mailbox.size, outbox.size,
        )
        heartbeatBase = summary()
        lastHeartbeat = now
        return st to line
    }

    // ------------------------------------------------------------------ persistence

    fun exportState(): BridgeState {
        val now = clock()
        val rows = synchronized(lock) {
            ledger.entries.filter { now - it.value.atMs <= LEDGER_TTL_MS }
                .map { (id, e) -> LedgerRow(id, e.atMs, e.heard, e.published, e.sent, e.relay) }
        }
        return BridgeState(
            ledger = rows,
            mailbox = mailbox.export(),
            airtime = budget.export().map { listOf(it.first.toDouble(), it.second) },
            lastCommandAt = lastCommandAt,
            paths = reportPaths.export(),
            ltAirtime = ltBudget.export().map { listOf(it.first.toDouble(), it.second) },
            outbox = outbox.export(),
            ltTimedAirtime = ltTimedBudget.export().map { listOf(it.first.toDouble(), it.second) },
            openFloods = synchronized(lock) { pruneOpenFloods(now); openFloods.toList() },
            radio = if (radio.up) "up" else "down",
            radioSince = radio.since,
        )
    }

    fun importState(s: BridgeState) {
        val now = clock()
        synchronized(lock) {
            ledger.clear()
            s.ledger.filter { now - it.at <= LEDGER_TTL_MS }.sortedBy { it.at }.forEach {
                ledger[it.id] = Entry(it.h, it.p, it.s, it.r, it.at)
            }
        }
        mailbox.restore(s.mailbox, now)
        budget.restore(s.airtime.filter { it.size == 2 }.map { it[0].toLong() to it[1] }, now)
        ltBudget.restore(s.ltAirtime.filter { it.size == 2 }.map { it[0].toLong() to it[1] }, now)
        ltTimedBudget.restore(s.ltTimedAirtime.filter { it.size == 2 }.map { it[0].toLong() to it[1] }, now)
        synchronized(lock) {
            openFloods.clear()
            s.openFloods.filter { it <= now && now - it < 3600_000L }.sorted().forEach { openFloods.addLast(it) }
        }
        lastCommandAt = s.lastCommandAt
        if (s.radioSince > 0) radio.restore(s.radio == "up", s.radioSince)
        reportPaths.restore(s.paths, now)
        outbox.restore(s.outbox, now)
        stat { it.copy(mailbox = mailbox.size, outbox = outbox.size) }
    }

    class RateLimit(private val count: Int, private val windowMs: Long) {
        private val hits = mutableMapOf<String, ArrayDeque<Long>>()

        @Synchronized
        private fun trim(now: Long, key: String): ArrayDeque<Long> {
            val q = hits.getOrPut(key) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
            return q
        }

        @Synchronized fun allow(now: Long, key: String) = trim(now, key).size < count

        @Synchronized fun hit(now: Long, key: String): Boolean {
            val q = trim(now, key)
            if (q.size >= count) return false
            q.addLast(now)
            return true
        }
    }

    companion object {
        const val LEDGER_CAP = 10_000
        const val LEDGER_TTL_MS = 30L * 86400_000
        /** An awake (non-sleeping) device's last route stays usable this long. */
        const val AWAKE_ROUTE_MS = 3600_000L

        /** Announcement `adapter`: this bridge talks to the radio through MeshCore Open's Local API. */
        const val ADAPTER = "meshcore-open"

        private val rng = SecureRandom()

        /** Configured peer keys (hex or npub) as lowercase hex; invalid ones are skipped. */
        fun parsePeers(peers: List<String>): Set<String> =
            peers.mapNotNull { s -> s.trim().lowercase().let { if (isLowerHex(it, 32)) it else Bech32.pubkey(it)?.hex() } }.toSet()

        fun randomKey(): ByteArray {
            while (true) {
                val k = ByteArray(32).also { rng.nextBytes(it) }
                if (Secp256k1.secKeyVerify(k)) return k
            }
        }
    }
}
