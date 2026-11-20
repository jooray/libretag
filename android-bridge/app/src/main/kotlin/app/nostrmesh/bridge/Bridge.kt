package app.nostrmesh.bridge

import app.nostrmesh.frames.FrameException
import app.nostrmesh.frames.Frames
import app.nostrmesh.frames.Fragments
import app.nostrmesh.frames.Road
import app.nostrmesh.libretag.Claims
import app.nostrmesh.libretag.Election
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Mailbox
import app.nostrmesh.libretag.Market
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
    /** 0.7 checks: mailbox entries with `at` and `via` go out at `at` along a stored report path. */
    val timedCommands: Boolean = true,
    val reportPathTtlMs: Long = 48 * 3600_000L,
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
    /** A locked key's entry for a report heard at most this long ago goes out at once (late entry). */
    val lateMaxMs: Long = 60_000,
    /** Distinct keys stored per report tag y (anchors and untimed entries; first seen stay). */
    val keysPerY: Int = 8,
    // PROTOCOL.md 4.1 (same names and defaults as [libretag] in host/src/nostrmesh/config.py)
    /** Bridge pubkeys (hex or npub) whose claims always count. */
    val peers: List<String> = emptyList(),
    /** Networks served (announcement `d`; entries with another `r` are not stored). */
    val networks: List<String> = emptyList(),
    /** Publish kind 4245 "heard" receipts (operator opt-in; needs the bridge key). */
    val receipts: Boolean = false,
    val receiptsPerHour: Int = 120,
    /** Publish kind 38244 announcements (needs the bridge key). */
    val announce: Boolean = true,
    val announceIntervalMs: Long = 24 * 3600_000L,
    /** Store entries that name no bridge (no `p`). */
    val acceptOpen: Boolean = true,
    /** Route `direct`: reversed path of a report heard this many hops away. */
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
    /** MeshCore flood scope name (announcement `region`); [region] says whether one is set. */
    val regionName: String = "",
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
    /** Nothing from the radio this long: the app reconnects the Local API client (0 = off). */
    val rxSilenceMs: Long = Watchdog.SILENCE_MS,
    /** Heartbeat: log line and status.json. */
    val heartbeatMs: Long = 3600_000L,
) {
    internal fun limits() = listOf(rxPerMinute, perPubkeyPerMinute, perPubkeyPerHour, dmPerHour, dmPerDevicePerHour,
        ltRxPerMinute, ltRxPerFirstHopPerMinute, receiptsPerHour)

    companion object {
        val DEFAULT_UPLINK = setOf("road.report", "road.vote", "libretag", "dm4.ref", "dm4.full")
        val ALL_UPLINK = listOf("road.report", "road.vote", "libretag", "dm4.ref", "dm4.full", "note", "generic")
    }
}

/** What the UI shows: one line per frame the bridge handled. */
data class Activity(val atMs: Long, val direction: Direction, val profile: String, val text: String) {
    enum class Direction { UP, DOWN, DROP, INFO }
}

interface MeshLink {
    val maxPayload: Int
    /** `hello.capabilities` of the Local API server. */
    val capabilities: Set<String> get() = emptySet()
    /** LoRa preset reported by the server, if any (else [BridgeConfig.preset]). */
    val preset: LoRaPreset? get() = null
    suspend fun send(channel: String, dataType: Int, payload: ByteArray, route: Route = Route.Flood): Boolean
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
    /** x -> report P of its first live hearing (claim proofs, late entries). */
    private val heardP = object : LinkedHashMap<String, ByteArray>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 2_048
    }
    private val relayReports = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 5_000
    }
    /** Group -> (when, entry) of this bridge's last timed transmission of it. */
    private val timedSent = mutableMapOf<String, Pair<Long, Mailbox.Command>>()

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
    }

    private lateinit var receiptsHour: RateLimit

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
        if (channel == Frames.CHANNEL_LIBRETAG && dataType == Frames.DATA_TYPE_LIBRETAG) {
            when {
                Lt2.isUplinkPacket(payload.size) -> onLt2Report(payload, meta, now)
                payload.size == Lt2.COMMAND_LEN -> mailbox.heardOnAir(payload, now).let {
                    mailboxChanged()
                    if (it != null) log(Activity.Direction.INFO, "libretag", "príkaz ${it.ys.first()} už vyslal iný most")
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

    // ------------------------------------------------------------------ LibreTag LT2

    private suspend fun onLt2Report(p: ByteArray, meta: RxMeta, now: Long) {
        val y = Lt2.tag(p)
        val heardAt = meta.receivedAtMs?.coerceAtMost(now) ?: now
        val key = "lt:" + sha256(p).hex()
        // own limit for new packets, so a #libretag flood does not use up the other channels' share
        if (synchronized(lock) { ledger[key] } == null) {
            // one first hop (where the packet entered the mesh) cannot use up the whole #libretag limit
            val fh = firstHopKey(meta, mesh.capabilities)
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
        if (!dup) stat { it.copy(heard = it.heard + 1) }
        if (config.libretagCommands) { // timed entries and late entries need it
            val caps = mesh.capabilities
            val hops = meta.hopCount(caps)
            val path = if (hops != null && Caps.RX_PATH in caps) meta.path else null
            reportPaths.put(y, hops, path, meta.pathHashSize ?: 1, heardAt)
            changes.incrementAndGet()
        }
        val live = now - heardAt <= config.liveRxMs
        // Q5: only the first hearing of P triggers; a later flood copy or the tag's retransmission does not,
        // so an entry published after the report reached the relays cannot ride on it. A variant of P with
        // another c is a new hearing: it may lock another key (PROTOCOL.md 4.2), never remove one.
        val firstX = synchronized(lock) { if (key in heardX) false else { heardX[key] = now; true } }
        val first = !dup && firstX
        if (config.libretagCommands && live && first && p.size == Lt2.REPORT_LEN) {
            synchronized(lock) { heardP.putIfAbsent(y, p) }
            lockReport(p, heardAt, now)
        }
        if (config.libretagCommands && config.groupAck && live && !dup && p.size == Lt2.REPORT_LEN) groupAck(y, meta, heardAt, now)
        // Downlink first: the tracker listens for a short while only.
        if (config.libretagCommands && live && first) command(y, p, heardAt, meta, now)
        if (dup) return
        val receipt = live && first && p.size == Lt2.REPORT_LEN && config.receipts && bridgeKey != null
        val uplink = "libretag" in config.uplinkProfiles
        if (!receipt && !uplink) return
        val content = Base64.getEncoder().encodeToString(p)
        val hops = meta.hopCount(mesh.capabilities)
        scope.launch {
            // the floor is above the claim wait: P reaches the relays only after the claims are decided
            delay(random.nextLong(config.reportHoldMinMs, maxOf(config.reportHoldMinMs, config.reportHoldMaxMs) + 1))
            if (receipt) publishReceipt(y, hops, heardAt)
            if (!uplink) return@launch
            val already = dedup.relays(y, content, clock())
            if (already >= config.dedupMinRelays) {
                log(Activity.Direction.INFO, "libretag", "report $y už je na $already relays")
                return@launch
            }
            // created_at = when the radio heard it: apps anchor the tracker's check schedule on it (0.7)
            val ev = Lt2.reportEvent(p, heardAt / 1000, newKey())
            if (publishKept(ev.toJson(), "lt:" + sha256(p).hex(), "libretag", "lt_published")) {
                log(Activity.Direction.UP, "libretag", if (p.size == Lt2.BLE_LEN) "BLE paket $y" else "report $y")
            } else {
                log(Activity.Direction.INFO, "libretag", "report $y: no relay took it yet, kept for a retry")
            }
        }
    }

    private suspend fun publishReceipt(x: String, hops: Int?, heardAtMs: Long) {
        val sk = bridgeKey ?: return
        if (!receiptsHour.hit(clock(), "")) {
            count("lt_receipts_limited")
            return
        }
        val ev = NostrEvent.sign(sk, heardAtMs / 1000, Market.RECEIPT_KIND, Market.receiptTags(x, hops, heardAtMs / 1000), "")
        if (relays.publish(ev.toJson())) count("lt_receipts")
    }

    /** The route [cmd] may go out on for a report heard over [hops] (null: its route does not allow it here). */
    private fun entryRoute(cmd: Mailbox.Command, hops: Int?, reverse: Route.Direct?): Route? =
        lt2EntryRoute(cmd.fmt, cmd.route, cmd.timed, hops, reverse, mesh.capabilities, config.directMaxHops)

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
        if (keys.isEmpty()) {
            count("lt_lock_none")
            return
        }
        count("lt_lock")
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

    private fun command(y: String, p: ByteArray, heardAt: Long, meta: RxMeta, now: Long) {
        val caps = mesh.capabilities
        val heardHops = meta.hopCount(caps)
        val reverse = meta.reversePath(caps)
        val keys = mailbox.locked(y)
        if (keys.isEmpty()) {
            if (mailbox.hasUntimed(y)) {
                count("lt_cmd_unsigned")
                log(Activity.Direction.INFO, "libretag", "command for $y: no key locked, not sending")
            }
            return
        }
        // an entry its route does not allow is not taken: its y stays for a nearer bridge
        val cmds = mailbox.match(y, heardAt, now, keys) { entryRoute(it, heardHops, reverse) != null }
        if (cmds.isEmpty()) {
            if (mailbox.hasUntimed(y)) log(Activity.Direction.INFO, "libretag", "command for $y asks for a nearer bridge or is not signed, not sending")
            return
        }
        for (cmd in cmds) deliver(cmd, y, p, entryRoute(cmd, heardHops, reverse)!!, heardHops, now)
    }

    /**
     * A late entry (PROTOCOL.md 4.2): an untimed entry from a key report [y] locked, arriving after the
     * report, goes out at once while the report is at most [BridgeConfig.lateMaxMs] old.
     */
    private fun late(y: String, pubkey: String, now: Long) {
        if (pubkey !in mailbox.locked(y)) return
        val p = synchronized(lock) { heardP[y] }
        val rp = reportPaths.get(y, now)
        if (p == null || rp == null || now - rp.heardAtMs > config.lateMaxMs) {
            count("lt_cmd_late_too_late")
            return
        }
        val meta = RxMeta(hops = rp.hops, path = rp.path?.unhex(), pathHashSize = rp.hashSize)
        val caps = mesh.capabilities
        val hops = if (Caps.RX_PATH in caps && meta.path != null) meta.hopCount(caps) else rp.hops
        val reverse = meta.reversePath(caps)
        val cmd = mailbox.match(y, now + 1, now, setOf(pubkey)) { entryRoute(it, hops, reverse) != null }.firstOrNull() ?: return
        count("lt_cmd_late")
        deliver(cmd, y, p, entryRoute(cmd, hops, reverse)!!, hops, now)
    }

    private fun deliver(cmd: Mailbox.Command, y: String, p: ByteArray, route: Route, heardHops: Int?, now: Long) {
        stat { it.copy(recognised = it.recognised + 1) }
        if (openFloodBlocked(cmd, route, now)) {
            count("lt_open_floods_limited")
            return drop("libretag", "command for $y: open flood limit per hour")
        }
        mailbox.take(cmd, now) // once per x and key, whatever the election says
        mailboxChanged()
        val hops = when (route) {
            Route.ZeroHop -> 0
            is Route.Direct -> route.hops
            Route.Flood -> heardHops ?: 255
        }
        scope.launch {
            if (!claimAndWait(y, hops, config.electionMs, cmd, p)) {
                mailbox.release(cmd)
                log(Activity.Direction.INFO, "libretag", "príkaz pre $y vyšle iný most")
                return@launch
            }
            if (send(cmd, route)) {
                log(Activity.Direction.DOWN, "libretag", "príkaz pre $y (${routeText(route)})")
            }
        }
    }

    /**
     * Publishes a kind 24244 claim for [y] (signed with the bridge key; untimed with the proof over
     * the report [p] heard), waits [waitMs]; true if this bridge won among the claims that count
     * ([counts]), ranked by (index in the entry's p, hops, hash).
     */
    private suspend fun claimAndWait(y: String, hops: Int, waitMs: Long, cmd: Mailbox.Command, p: ByteArray?): Boolean {
        val sk = bridgeKey ?: newKey()
        val pub = NostrEvent.xonly(sk).hex()
        val proof = if (bridgeKey != null && !cmd.timed && p != null) Market.claimProof(p, pub) else null
        val mine = Election.Claim(pub, hops, proof)
        rememberOwnKey(pub)
        relays.publish(Lt2.claimEvent(y, hops, clock() / 1000, sk, proof).toJson())
        delay(waitMs)
        val now = clock()
        val rivals = claims.of(y, now).filter { counts(it, cmd, p, now) }
        val index = { pk: String -> if (cmd.open) 0 else cmd.p.indexOf(pk).takeIf { it >= 0 } ?: cmd.p.size }
        return Election.wins(mine, rivals, y, index)
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

    /** Why a timed entry may not go out now (per-via gap, hourly cap, closed group), or null. */
    private fun timedBlocked(cmd: Mailbox.Command, now: Long): String? {
        if (cmd.group != null && mailbox.groupDone(cmd.group)) return "trackér už odpovedal"
        val last = mailbox.lastTimedTx(cmd.via!!)
        if (last != null && now - last < config.timedViaGapMs) return "pre ${cmd.via} nedávno išiel časovaný príkaz"
        if (mailbox.timedTxSince(now - 3600_000) >= config.timedPerHour) return "limit časovaných príkazov za hodinu"
        return null
    }

    /** Transmits a taken mailbox entry unless it was cancelled or heard on air meanwhile. */
    private suspend fun send(cmd: Mailbox.Command, route: Route): Boolean {
        if (!mailbox.pending(cmd.id)) {
            if (!mailbox.wasSent(cmd.id)) log(Activity.Direction.INFO, "libretag", "príkaz ${cmd.ys.first()} zrušený, nevysielam")
            return false
        }
        val now = clock()
        if (mailbox.payloadSent(cmd.payload)) {
            mailbox.markSent(cmd, now)
            mailboxChanged()
            log(Activity.Direction.INFO, "libretag", "príkaz ${cmd.ys.first()}: rovnaký obsah už išiel, nevysielam")
            return false
        }
        if (cmd.timed) timedBlocked(cmd, now)?.let {
            mailbox.release(cmd)
            drop("libretag", "časovaný príkaz ${cmd.ys.first()}: $it")
            return false
        }
        if (openFloodBlocked(cmd, route, now)) {
            mailbox.release(cmd)
            count("lt_open_floods_limited")
            drop("libretag", "command ${cmd.ys.first()}: open flood limit per hour")
            return false
        }
        if (!transmit(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, cmd.payload, route, "libretag", timed = cmd.timed)) {
            mailbox.release(cmd)
            return false
        }
        val t = clock()
        if (route == Route.Flood && cmd.open) synchronized(lock) { openFloods.addLast(t); pruneOpenFloods(t) }
        mailbox.markPayloadSent(cmd.payload, t)
        if (cmd.timed) {
            mailbox.addTimedTx(cmd.via!!, t)
            cmd.group?.let { g -> synchronized(lock) { timedSent[g] = t to cmd } }
        }
        mailbox.markSent(cmd, t)
        mailboxChanged()
        return true
    }

    // ------------------------------------------------------------------ command groups (PROTOCOL.md 4)

    /** A fresh report that shows the tracker answered (or moved past report `via`): drop the rest of the group. */
    private fun groupAck(x: String, meta: RxMeta, heardAt: Long, now: Long) {
        for (g in mailbox.groupsOfY(x)) { // (1) the tracker's own next report
            if (mailbox.groupDone(g)) continue
            val tooClose = mailbox.groupTimed(g).mapNotNull { it.via }.distinct()
                .mapNotNull { reportPaths.get(it, now) }.any { heardAt < it.heardAtMs + ACK_AFTER_VIA_MS }
            if (!tooClose) closeGroup(g, x, null, now, "ďalšie hlásenie trackera")
        }
        val sent = synchronized(lock) { timedSent.toMap() }
        for ((g, v) in sent) { // (2) after this bridge's own timed transmission, heard the same way as report `via`
            val (tSent, cmd) = v
            if (mailbox.groupDone(g) || heardAt <= tSent || heardAt > tSent + config.ackWindowMs) continue
            val rp = reportPaths.get(cmd.via!!, now) ?: continue
            if (sameWay(rp, meta)) closeGroup(g, x, cmd.at, now, "hlásenie po našom príkaze")
        }
    }

    private fun sameWay(rp: ReportPaths.Entry, meta: RxMeta): Boolean {
        val caps = mesh.capabilities
        val hops = meta.hopCount(caps) ?: return false
        val rpHops = rp.hops ?: return false
        if (rpHops == 0 && hops == 0) return true
        val path = if (Caps.RX_PATH in caps) meta.path else null
        val hs = meta.pathHashSize ?: 1
        val rpPath = rp.path?.unhex() ?: return false
        if (path == null || rpHops != hops || rp.hashSize != hs || path.size < hs || rpPath.size < hs) return false
        return path.copyOf(hs).contentEquals(rpPath.copyOf(hs))
    }

    private fun closeGroup(g: String, x: String, at: Long?, now: Long, why: String) {
        val n = mailbox.closeGroup(g, now)
        synchronized(lock) { timedSent.remove(g) }
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

    /**
     * Timed entries (0.7 checks): starts every entry whose claim time (`at` minus the lead) has
     * come. It goes out once at `at`, zero-hop if this bridge heard its `via` report directly,
     * otherwise flood, and only from a bridge that caught that report (it is near the tracker).
     * Call about twice a second.
     */
    fun timedTick() {
        val now = clock()
        reportPaths.prune(now)
        synchronized(lock) { timedSent.entries.removeAll { now - it.value.first > config.ackWindowMs } }
        if (!config.libretagCommands || !config.timedCommands) return
        for (cmd in mailbox.dueTimed(now + config.timedClaimLeadMs, now)) {
            val y = cmd.ys.first()
            // format 2: the route binds (a timed entry treats direct as near); not ours to send, the y stays open
            val viaPath = reportPaths.get(cmd.via!!, now)
            if (viaPath != null && entryRoute(cmd, viaPath.hops, null) == null) {
                mailbox.discard(cmd)
                mailboxChanged()
                log(Activity.Direction.INFO, "libretag", "timed command for $y asks for a nearer bridge, not sending")
                continue
            }
            mailbox.take(cmd, now) // one delivery per event and per tag
            mailboxChanged()
            val atMs = cmd.at!! * 1000
            if (atMs < now - config.timedMaxLateMs) {
                mailbox.release(cmd)
                log(Activity.Direction.INFO, "libretag", "časovaný príkaz pre $y: čas už prešiel")
                continue
            }
            val rp = reportPaths.get(cmd.via!!, now)
            if (rp == null) {
                mailbox.release(cmd)
                log(Activity.Direction.INFO, "libretag", "časovaný príkaz pre $y: report ${cmd.via} som nepočul")
                continue
            }
            if (cmd.pubkey !in mailbox.locked(cmd.via)) {
                mailbox.release(cmd)
                count("lt_cmd_unsigned")
                drop("libretag", "timed command for $y: not signed by the key report ${cmd.via} committed to")
                continue
            }
            if (!atPlausible(atMs, rp.heardAtMs)) {
                mailbox.release(cmd)
                drop("libretag", "časovaný príkaz pre $y: čas mimo okna hlásenia ${cmd.via}")
                continue
            }
            if (mailbox.payloadSent(cmd.payload)) {
                mailbox.markSent(cmd, now)
                mailboxChanged()
                continue
            }
            val blocked = timedBlocked(cmd, now)
            if (blocked != null) {
                mailbox.release(cmd)
                drop("libretag", "časovaný príkaz pre $y: $blocked") // no claim either: we would not send
                continue
            }
            val route = entryRoute(cmd, rp.hops, null) ?: lt2CommandRoute(rp.hops, mesh.capabilities)
            if (openFloodBlocked(cmd, route, now)) {
                mailbox.release(cmd)
                count("lt_open_floods_limited")
                drop("libretag", "timed command for $y: open flood limit per hour")
                continue
            }
            val hops = when (route) {
                Route.ZeroHop -> 0
                is Route.Direct -> route.hops
                Route.Flood -> rp.hops ?: 255
            }
            scope.launch {
                val wait = maxOf(atMs - clock(), 0L)
                if (!claimAndWait(y, hops, maxOf(wait, config.electionMs), cmd, null)) {
                    mailbox.release(cmd)
                    log(Activity.Direction.INFO, "libretag", "príkaz pre $y vyšle iný most")
                    return@launch
                }
                if (clock() > atMs + config.timedMaxLateMs) {
                    mailbox.release(cmd)
                    log(Activity.Direction.INFO, "libretag", "časovaný príkaz pre $y: čas už prešiel")
                    return@launch
                }
                if (send(cmd, route)) {
                    log(Activity.Direction.DOWN, "libretag", "časovaný príkaz pre $y (${routeText(route)})")
                }
            }
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
            out["nm-cmd"] = if (config.acceptOpen) {
                listOf(filter(Lt2.KIND_COMMAND, since))
            } else {
                // PROTOCOL.md 4.1: a bridge that serves no open entries asks only for its own and its networks'
                listOfNotNull(
                    bridgePubkey?.let { tagFilter(Lt2.KIND_COMMAND, "#p", listOf(it), since) },
                    networks().takeIf { it.isNotEmpty() }?.let { tagFilter(Lt2.KIND_COMMAND, "#r", it, since) },
                )
            }
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
            val tags = Market.announceTags(net, now / 1000, region = c.regionName.takeIf { c.region && it.isNotEmpty() },
                relays = relays.publishUrls, acceptOpen = c.acceptOpen, receipts = c.receipts,
                anchorLeadS = c.anchorLeadMs / 1000, lateMaxS = c.lateMaxMs / 1000)
            NostrEvent.sign(sk, now / 1000, Market.ANNOUNCE_KIND, tags, content)
        }
    }

    /** Publishes the announcements at start and every [BridgeConfig.announceIntervalMs]; true when it did. */
    suspend fun announceIfDue(now: Long = clock()): Boolean {
        val c = config
        if (!c.announce || !c.libretagCommands || bridgeKey == null) return false
        val last = lastAnnounceMs
        if (last != null && now - last < c.announceIntervalMs) return false
        lastAnnounceMs = now
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

    suspend fun onRelayEvent(raw: JsonObject, relay: String = "") {
        val ev = NostrEvent.fromJson(raw) ?: return
        val now = clock()
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
        notForUs(cmd)?.let {
            count("lt_not_for_us")
            return
        }
        // signed mailbox (PROTOCOL.md 4.2): once a report was heard, only a key that locked or still may lock
        var entry = cmd
        if (cmd.timed) {
            if (!mayLock(cmd.via!!, ev.pubkey, now)) {
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
            val rp = reportPaths.get(cmd.via!!, now)
            if (rp != null && !atPlausible(cmd.at!! * 1000, rp.heardAtMs)) return drop("libretag", "časovaný príkaz mimo okna hlásenia ${cmd.via}")
        }
        if (!ev.verify()) return
        lastCommandAt = maxOf(lastCommandAt, ev.createdAt)
        if (mailbox.isCancelled(ev.id, ev.pubkey)) return // the deletion came first
        if (cmd.timed && cmd.group != null && mailbox.groupDone(cmd.group)) return // the tracker already answered
        if (entry.anchor) {
            val added = entry.ys.count { mailbox.addAnchor(it, ev.pubkey, ev.id, now, entry.expiresAtMs) }
            count(if (added > 0) "lt_anchor_added" else "lt_anchor_again")
            if (added > 0) changes.incrementAndGet()
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
    private fun notForUs(cmd: Mailbox.Command): String? {
        val c = config
        if (cmd.open) {
            if (!c.acceptOpen) return "open"
        } else if (bridgePubkey == null || bridgePubkey !in cmd.p) {
            return "other bridges"
        }
        val nets = networks()
        if (nets.isNotEmpty() && cmd.r != null && cmd.r !in nets) return "other network"
        return null
    }

    /** A1: a check comes after report `via` and before its heartbeat (24 h + 1 h). */
    private fun atPlausible(atMs: Long, viaHeardAtMs: Long) =
        atMs >= viaHeardAtMs - config.timedMaxLateMs && atMs <= viaHeardAtMs + config.timedMaxAfterReportMs

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
        if (!transmit(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, out.payload, route, "dm4.ref")) return
        dmHour.hit(now, ""); dmDevice.hit(now, out.device)
        synchronized(lock) { ledger[out.eventId]?.sent = true }
        changes.incrementAndGet()
        log(Activity.Direction.DOWN, "dm4.ref", "${out.label} (${routeText(route)})")
    }

    /** One own transmission within the airtime budget. */
    private suspend fun transmit(
        channel: String, dataType: Int, payload: ByteArray, route: Route, profile: String, timed: Boolean = false,
    ): Boolean =
        txLock.withLock {
            val now = clock()
            val pathBytes = (route as? Route.Direct)?.path?.size ?: 0
            val preset = mesh.preset ?: config.preset
            val ms = Airtime.ms(Airtime.channelPacketBytes(payload.size, pathBytes, config.region), preset) *
                Airtime.transmissions(route)
            // #libretag has its own budget: a flood of LibreTag commands cannot silence DMs or Roadbeacon
            val budget = if (channel == Frames.CHANNEL_LIBRETAG) ltBudget else budget
            if (!budget.allow(now, ms)) {
                count("tx_airtime_exhausted")
                drop(profile, "rozpočet airtime vyčerpaný (${budget.usedMs(now).toInt()} ms za hodinu)")
                return@withLock false
            }
            // timed transmissions use at most their share, the rest stays for commands that answer a report
            val timedBudget = timed && channel == Frames.CHANNEL_LIBRETAG
            if (timedBudget && !ltTimedBudget.allow(now, ms)) {
                count("tx_airtime_timed_exhausted")
                drop(profile, "airtime share for timed commands used up (${ltTimedBudget.usedMs(now).toInt()} ms per hour)")
                return@withLock false
            }
            if (!mesh.send(channel, dataType, payload, route)) {
                drop(profile, "rádio odmietlo")
                return@withLock false
            }
            budget.spend(now, ms)
            if (timedBudget) ltTimedBudget.spend(now, ms)
            count("tx_airtime_ms", ms.toLong())
            changes.incrementAndGet()
            stat { it.copy(toRadio = it.toRadio + 1, airtimeMs = (this.budget.usedMs(now) + ltBudget.usedMs(now)).toInt()) }
            true
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
        /** A report this soon after report `via` may be a copy of it, never its answer. */
        const val ACK_AFTER_VIA_MS = 120_000L

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
