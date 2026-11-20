package app.libretag.backup

import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import app.libretag.proto.Keys
import app.libretag.nostr.isLowerHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.math.BigInteger
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One tracker in the backup. [type] is "person" | "thing" | "vehicle", [mark] is "<color>-<symbol>".
 * v1.1 (SPEC.md 12.2): [updated] (unix s) for the merge between devices; a removed tag stays as a
 * tombstone `{"k0":…,"removed":<unix s>}` for 90 days, without name, type or mark.
 */
data class BackupTag(
    val name: String,
    val k0Hex: String,
    val type: String,
    val mark: String,
    val updated: Long? = null,
    val removed: Long? = null,
    /** per-tag settings the web version shows (v1.1, covered by [updated]); null = not in the backup */
    val settings: TagSettings? = null,
) {
    val isRemoved: Boolean get() = removed != null

    companion object {
        fun tombstone(k0Hex: String, removed: Long) = BackupTag("", k0Hex, "", "", removed = removed)
    }
}

/**
 * A tag's settings in the backup, for the web version (SPEC.md 12.1): report interval, listening
 * at checks, parked quiet ([still] null = off), things/people, firmware, zones and the labels and
 * kinds of Bluetooth anchors (never their addresses or IRKs), alert switches and the guard spot.
 */
data class TagSettings(
    val intervalMin: Int,
    /** 0.7.3: report at every n-th wake-up */
    val reportEvery: Int = 1,
    val checkS: Int,
    val still: Still?,
    val use: String,
    val fw: String?,
    val zones: List<BackupZone>,
    val anchors: List<BackupAnchor>,
    val alerts: Alerts = Alerts(),
    /** the guard spot, or null when the guard is off */
    val guard: Guard? = null,
) {
    data class Still(val after: Int, val hbH: Int)
    /** which alerts are on: tag silent (overdue), low battery, left a zone, moved while guarded */
    data class Alerts(val silent: Boolean = true, val lowBattery: Boolean = true, val leftZone: Boolean = true, val guard: Boolean = true)
    data class Guard(val lat: Double, val lon: Double, val rM: Int, val since: Long?)
}

/** [notifyLeave] / [notifyArrive]: the place's own choice, null = the tag type's default (left out of the JSON) */
data class BackupZone(
    val name: String,
    val lat: Double,
    val lon: Double,
    val rM: Int,
    val notifyLeave: Boolean? = null,
    val notifyArrive: Boolean? = null,
)

data class BackupAnchor(val label: String, val kind: String)

/**
 * The decrypted backup (format v1, SPEC.md 12.1). [owner] is the owner's npub, if set. [d] is the
 * `d` tag of the Nostr copy for the owner's npub (12.2 B), kept here so a reader can check it.
 */
data class BackupPayload(
    val v: Int = 1,
    val created: Long,
    val owner: String?,
    val tags: List<BackupTag>,
    val d: String? = null,
) {
    val live: List<BackupTag> get() = tags.filter { !it.isRemoved }
}

/** Everything derived from the passphrase. [sk] and [encKey] are secrets; keep [Derived] in memory only. */
class Derived(
    /** the `d` tag: 32 lowercase hex characters */
    val locator: String,
    val sk: ByteArray,
    val pubkeyHex: String,
    val encKey: ByteArray,
)

sealed class RestoreResult {
    data class Found(val payload: BackupPayload, val event: NostrEvent) : RestoreResult()

    /** the newest event is a deletion (empty content) */
    object Deleted : RestoreResult()

    /** no valid event for this passphrase */
    object NotFound : RestoreResult()

    /** the newest event is signed by the backup key but does not decrypt (corrupt or a newer format) */
    object Unreadable : RestoreResult()
}

/**
 * Key backup on Nostr, findable with only a passphrase (SPEC.md 12.1, format v1). The passphrase
 * stretches (scrypt) to a secret S; S gives the event's `d` tag, a signing key and an AES-GCM key.
 * Whoever knows the passphrase finds, decrypts and can replace or delete the backup; nobody needs
 * the owner's npub. The backup holds K0, so the ratchet does not protect it: the passphrase is
 * the only protection, and the locator lets anyone test guesses offline. Hence scrypt and [strength].
 */
object KeyBackup {
    const val KIND = 30078
    const val DELETION_KIND = 5
    const val VERSION: Byte = 1
    const val ALT = "LibreTag encrypted key backup"
    const val SALT = "LibreTag key backup v1"
    const val NONCE_LEN = 12
    const val GCM_TAG_LEN = 16
    const val MIN_WORDS = 4
    const val MIN_CHARS = 20

    /** scrypt cost; [V1] is the format, smaller ones exist only for tests. */
    internal data class Params(val n: Int, val r: Int, val p: Int) {
        companion object {
            val V1 = Params(1 shl 16, 8, 1)
        }
    }

    enum class Strength { TOO_SHORT, OK }

    /** ECMAScript `\s` (what the PWA's String.trim and /\s+/ use), so both sides normalise alike. */
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val WS_RUN = Regex("[$WS]+")
    private val WS_EDGE = Regex("^[$WS]+|[$WS]+$")

    /** NFKC, whitespace runs to one space, trimmed. Case is kept. */
    fun normalize(p: String): String =
        Normalizer.normalize(p, Normalizer.Form.NFKC).replace(WS_RUN, " ").replace(WS_EDGE, "")

    /** At least [MIN_WORDS] words and [MIN_CHARS] characters (code points) after [normalize]. */
    fun strength(p: String): Strength {
        val n = normalize(p)
        val words = if (n.isEmpty()) 0 else n.split(' ').size
        return if (words < MIN_WORDS || n.codePointCount(0, n.length) < MIN_CHARS) Strength.TOO_SHORT else Strength.OK
    }

    /** scrypt of the normalised passphrase: 64 bytes. */
    internal fun stretch(p: String, params: Params = Params.V1): ByteArray =
        Scrypt.derive(normalize(p).toByteArray(Charsets.UTF_8), SALT.toByteArray(Charsets.UTF_8), params.n, params.r, params.p, 64)

    /** Expensive (scrypt, 64 MiB, ~1 s): call off the main thread. */
    fun derive(p: String): Derived = fromSecret(stretch(p))

    /**
     * 12.2 A: the passphrase is a BIP-39 mnemonic (12 or 24 words, valid checksum), normalised to
     * lowercase words and single spaces. Null for anything else. Expensive, like [derive].
     */
    fun deriveFromPhrase(phrase: String): Derived? =
        (Bip39.check(phrase) as? Bip39.Check.Ok)?.let { derive(it.phrase) }

    internal fun derive(p: String, params: Params): Derived = fromSecret(stretch(p, params))

    internal fun fromSecret(s: ByteArray): Derived {
        val h = Scrypt.Hmac(s)
        val locator = h.mac("LT-backup-d".toByteArray()).hex().substring(0, 32)
        val v = BigInteger(1, h.mac("LT-backup-sk".toByteArray())).mod(Keys.SECP256K1_N)
        check(v.signum() != 0) { "backup signing key is zero" }
        val b = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        val sk = ByteArray(32 - b.size) + b
        return Derived(locator, sk, NostrEvent.xonly(sk).hex(), h.mac("LT-backup-enc".toByteArray()))
    }

    /**
     * UTF-8 JSON, keys in the order v, created, owner, d, tags; per tag name, k0, type, mark, updated,
     * then the settings (interval_min, check_s, still, use, fw, zones, anchors, alerts, guard); tombstones: k0, removed. Fields that are null in v1.1 (d, updated) are left out, so a v1
     * payload encodes exactly as before.
     */
    fun plaintext(payload: BackupPayload): String = buildJsonObject {
        put("v", JsonPrimitive(payload.v))
        put("created", JsonPrimitive(payload.created))
        put("owner", JsonPrimitive(payload.owner))
        payload.d?.let { put("d", JsonPrimitive(it)) }
        put("tags", buildJsonArray {
            for (t in payload.tags) add(buildJsonObject {
                if (t.removed != null) {
                    put("k0", JsonPrimitive(t.k0Hex))
                    put("removed", JsonPrimitive(t.removed))
                } else {
                    put("name", JsonPrimitive(t.name))
                    put("k0", JsonPrimitive(t.k0Hex))
                    put("type", JsonPrimitive(t.type))
                    put("mark", JsonPrimitive(t.mark))
                    t.updated?.let { put("updated", JsonPrimitive(it)) }
                    t.settings?.let { putSettings(this, it) }
                }
            })
        })
    }.toString()

    private fun putSettings(o: kotlinx.serialization.json.JsonObjectBuilder, s: TagSettings) {
        o.put("interval_min", JsonPrimitive(s.intervalMin))
        o.put("report_every", JsonPrimitive(s.reportEvery))
        o.put("check_s", JsonPrimitive(s.checkS))
        o.put("still", s.still?.let { st -> buildJsonObject { put("after", JsonPrimitive(st.after)); put("hb_h", JsonPrimitive(st.hbH)) } } ?: JsonNull)
        o.put("use", JsonPrimitive(s.use))
        o.put("fw", JsonPrimitive(s.fw))
        o.put("zones", buildJsonArray {
            for (z in s.zones) add(buildJsonObject {
                put("name", JsonPrimitive(z.name)); put("lat", JsonPrimitive(z.lat)); put("lon", JsonPrimitive(z.lon)); put("r_m", JsonPrimitive(z.rM))
                z.notifyLeave?.let { put("notify_leave", JsonPrimitive(it)) }
                z.notifyArrive?.let { put("notify_arrive", JsonPrimitive(it)) }
            })
        })
        o.put("anchors", buildJsonArray {
            for (a in s.anchors) add(buildJsonObject { put("label", JsonPrimitive(a.label)); put("kind", JsonPrimitive(a.kind)) })
        })
        o.put("alerts", buildJsonObject {
            put("silent", JsonPrimitive(s.alerts.silent)); put("low_battery", JsonPrimitive(s.alerts.lowBattery))
            put("left_zone", JsonPrimitive(s.alerts.leftZone)); put("guard", JsonPrimitive(s.alerts.guard))
        })
        o.put("guard", s.guard?.let { g ->
            buildJsonObject { put("lat", JsonPrimitive(g.lat)); put("lon", JsonPrimitive(g.lon)); put("r_m", JsonPrimitive(g.rM)); put("since", JsonPrimitive(g.since)) }
        } ?: JsonNull)
    }

    private fun parseSettings(t: JsonObject): TagSettings? {
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val interval = t.int("interval_min") ?: return null
        val still = (t["still"] as? JsonObject)?.let { o -> TagSettings.Still(o.int("after") ?: 0, o.int("hb_h") ?: 0) }
        fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val alerts = (t["alerts"] as? JsonObject)?.let { o ->
            TagSettings.Alerts(o.bool("silent") ?: true, o.bool("low_battery") ?: true, o.bool("left_zone") ?: true, o.bool("guard") ?: true)
        } ?: TagSettings.Alerts()
        val guard = (t["guard"] as? JsonObject)?.let { o ->
            val lat = o.dbl("lat"); val lon = o.dbl("lon")
            if (lat == null || lon == null) null
            else TagSettings.Guard(lat, lon, o.int("r_m") ?: 0, (o["since"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull)
        }
        return TagSettings(
            intervalMin = interval,
            reportEvery = (t.int("report_every") ?: 1).coerceIn(1, 24),
            checkS = t.int("check_s") ?: 0,
            still = still,
            use = t.str("use") ?: "",
            fw = t.str("fw"),
            zones = (t["zones"] as? JsonArray).orEmpty().mapNotNull { e ->
                val z = e as? JsonObject ?: return@mapNotNull null
                BackupZone(z.str("name") ?: "", z.dbl("lat") ?: return@mapNotNull null, z.dbl("lon") ?: return@mapNotNull null, z.int("r_m") ?: return@mapNotNull null,
                    z.bool("notify_leave"), z.bool("notify_arrive"))
            },
            anchors = (t["anchors"] as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                BackupAnchor(a.str("label") ?: "", a.str("kind") ?: "")
            },
            alerts = alerts,
            guard = guard,
        )
    }

    /**
     * Parses a decrypted plaintext; null unless it is a v1 object with a tags array. Unknown keys
     * are ignored; a tag without a valid k0 is dropped (callers validate k0 again before use).
     */
    fun parse(text: String): BackupPayload? {
        val o = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (o.num("v") != 1L) return null
        val tags = (o["tags"] as? JsonArray) ?: return null
        return BackupPayload(
            v = 1,
            created = o.num("created") ?: 0,
            owner = o.str("owner"),
            d = o.str("d"),
            tags = tags.mapNotNull { e ->
                val t = e as? JsonObject ?: return@mapNotNull null
                val k0 = t.str("k0")?.lowercase()?.takeIf { isLowerHex(it, 32) } ?: return@mapNotNull null
                val removed = t.num("removed")
                if (removed != null) BackupTag.tombstone(k0, removed)
                else BackupTag(t.str("name") ?: "", k0, t.str("type") ?: "thing", t.str("mark") ?: "", t.num("updated"), settings = parseSettings(t))
            },
        )
    }

    /** base64(0x01 || nonce || AES-256-GCM(encKey, nonce, plaintext, aad = locator)). */
    internal fun encrypt(d: Derived, nonce: ByteArray, plaintext: ByteArray): String {
        require(nonce.size == NONCE_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(d.encKey, "AES"), GCMParameterSpec(8 * GCM_TAG_LEN, nonce))
        c.updateAAD(d.locator.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + nonce + c.doFinal(plaintext))
    }

    internal fun decrypt(d: Derived, content: String): ByteArray? {
        val raw = try {
            Base64.getDecoder().decode(content)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (raw.size < 1 + NONCE_LEN + GCM_TAG_LEN || raw[0] != VERSION) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(d.encKey, "AES"), GCMParameterSpec(8 * GCM_TAG_LEN, raw, 1, NONCE_LEN))
            c.updateAAD(d.locator.toByteArray(Charsets.UTF_8))
            c.doFinal(raw, 1 + NONCE_LEN, raw.size - 1 - NONCE_LEN)
        } catch (e: Exception) {
            null
        }
    }

    private fun tags(d: Derived) = listOf(listOf("d", d.locator), listOf("alt", ALT))

    /** The backup event (kind 30078), signed with the passphrase key. */
    fun seal(
        d: Derived,
        payload: BackupPayload,
        random: Random = SecureRandom(),
        now: Long = System.currentTimeMillis() / 1000,
    ): NostrEvent = sealWithNonce(d, payload, ByteArray(NONCE_LEN).also { random.nextBytes(it) }, now)

    internal fun sealWithNonce(d: Derived, payload: BackupPayload, nonce: ByteArray, now: Long): NostrEvent =
        NostrEvent.sign(d.sk, now, KIND, tags(d), encrypt(d, nonce, plaintext(payload).toByteArray(Charsets.UTF_8)))

    /** The event is a backup event of this passphrase with a valid signature (content may be empty). */
    fun belongs(d: Derived, ev: NostrEvent): Boolean =
        ev.kind == KIND && ev.pubkey == d.pubkeyHex &&
            ev.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) == d.locator && ev.verify()

    /** Decrypts one backup event; null if it is not ours, deleted, or unreadable. */
    fun open(d: Derived, ev: NostrEvent): BackupPayload? {
        if (!belongs(d, ev) || ev.content.isEmpty()) return null
        val pt = decrypt(d, ev.content) ?: return null
        return parse(String(pt, Charsets.UTF_8))
    }

    /**
     * From whatever the relays returned for [filter]: the newest valid event wins (ties: lowest id,
     * as NIP-01 does for replaceable events). Older events never resurrect a deleted backup.
     */
    fun restore(d: Derived, events: List<NostrEvent>): RestoreResult {
        val newest = events.filter { belongs(d, it) }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            .firstOrNull() ?: return RestoreResult.NotFound
        if (newest.content.isEmpty()) return RestoreResult.Deleted
        return open(d, newest)?.let { RestoreResult.Found(it, newest) } ?: RestoreResult.Unreadable
    }

    /**
     * Deleting: an empty replacement (relays keep only the newest per pubkey + d) and a NIP-09
     * request for the address. Publish both.
     */
    fun deletion(d: Derived, now: Long = System.currentTimeMillis() / 1000): List<NostrEvent> = listOf(
        NostrEvent.sign(d.sk, now, KIND, tags(d), ""),
        NostrEvent.sign(d.sk, now, DELETION_KIND, listOf(listOf("a", address(d)), listOf("k", KIND.toString())), ""),
    )

    fun address(d: Derived): String = "$KIND:${d.pubkeyHex}:${d.locator}"

    /** REQ filter: {"kinds":[30078],"authors":[pk],"#d":[d]}. */
    fun filter(d: Derived): JsonObject = buildJsonObject {
        put("kinds", buildJsonArray { add(JsonPrimitive(KIND)) })
        put("authors", buildJsonArray { add(JsonPrimitive(d.pubkeyHex)) })
        put("#d", buildJsonArray { add(JsonPrimitive(d.locator)) })
    }
}
