package app.libretag.backup

import app.libretag.keys.Secrets
import android.content.Context
import app.libretag.LibreTagApp
import app.libretag.data.AlertPrefs
import app.libretag.data.Marks
import app.libretag.data.Tracker
import app.libretag.data.Zone
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.RelayOnce
import app.libretag.nostr.hex
import app.libretag.nostr.unhex
import kotlinx.coroutines.Job
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

/**
 * Keeps both backup copies (SPEC.md 12.2) up to date: A, sealed with keys derived from the
 * recovery phrase (the phrase itself is never stored, only the derived d, signing key and AES
 * key), and B, encrypted to the owner's npub through their signer. Every save reads the current
 * copies, merges per tag ([BackupMerge]) and writes the result to both.
 *
 * Saves run a few seconds after any change of the tag list. In the background a NIP-55 signer can
 * only answer through its content provider; if it needs the user, copy B waits for the Backup
 * screen ([Problem.SIGNER]).
 */
class BackupSync(private val app: LibreTagApp) {
    private val p = app.getSharedPreferences("backup", Context.MODE_PRIVATE)

    init { Secrets.migrate(listOf("aSk", "aEnc", "book"), { p.getString(it, null) }, { k, v -> p.edit().putString(k, v).apply() }) }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var pendingSave: Job? = null

    enum class Problem { SIGNER, OFFLINE, UNREADABLE, DELETED_ELSEWHERE, FAILED }

    data class CopyState(val on: Boolean, val savedAt: Long, val relays: Int, val pending: Boolean, val problem: Problem?)

    data class State(
        val phrase: CopyState,
        val npub: CopyState,
        val busy: Boolean = false,
        /** live tags in the backup that this phone doesn't have (from another device) */
        val notHere: List<BackupTag> = emptyList(),
    )

    private val _state = MutableStateFlow(snapshot(false, emptyList()))
    val state: StateFlow<State> = _state

    private fun snapshot(busy: Boolean, notHere: List<BackupTag>) = State(
        phrase = CopyState(phraseKeys() != null, p.getLong("aAt", 0), p.getInt("aRelays", 0),
            rev() > p.getLong("aRev", 0), p.getString("aProblem", null)?.let { Problem.valueOf(it) }),
        npub = CopyState(app.signers.session != null, p.getLong("bAt", 0), p.getInt("bRelays", 0),
            rev() > p.getLong("bRev", 0), p.getString("bProblem", null)?.let { Problem.valueOf(it) }),
        busy = busy,
        notHere = notHere,
    )

    private fun emit(busy: Boolean = false, notHere: List<BackupTag>? = null) {
        _state.value = snapshot(busy, notHere ?: _state.value.notHere)
    }

    // ------------------------------------------------------------------ local state

    @Serializable
    private data class BookJson(val seen: Map<String, List<String>> = emptyMap(), val removed: Map<String, Long> = emptyMap())

    private var book: BackupBook = loadBook()

    private fun loadBook(): BackupBook = runCatching {
        val b = json.decodeFromString(BookJson.serializer(), p.getString("book", null)?.let { Secrets.unwrap(it) } ?: return BackupBook())
        BackupBook(b.seen.mapValues { (_, v) -> v[0] to v[1].toLong() }, b.removed)
    }.getOrDefault(BackupBook())

    private fun saveBook(b: BackupBook) {
        if (b == book) return
        book = b
        val s = json.encodeToString(BookJson.serializer(), BookJson(b.seen.mapValues { (_, v) -> listOf(v.first, v.second.toString()) }, b.removed))
        p.edit().putString("book", Secrets.wrap(s)).putLong("rev", rev() + 1).apply()
    }

    private fun rev() = p.getLong("rev", 0)

    private fun owned(): List<Tracker> = app.store.trackers.value.filter { !it.isShared && it.keyHex.isNotEmpty() }

    private fun now() = System.currentTimeMillis() / 1000

    fun start() {
        // changes left unsaved last time (offline, signer): one try shortly after start
        if (_state.value.phrase.pending || _state.value.npub.pending) schedule(30_000)
        app.scope.launch {
            app.store.trackers.collect { list ->
                val tags = list.filter { !it.isShared && it.keyHex.isNotEmpty() }.map { toBackup(it) }
                val created = list.associate { it.keyHex.lowercase() to it.createdAt / 1000 }
                val before = rev()
                saveBook(book.track(tags, now()) { created[it.k0Hex.lowercase()] })
                // positions change the list all the time; only a change of what the backup holds saves
                if (rev() != before) {
                    emit()
                    schedule()
                }
            }
        }
    }

    /** Save a little later (changes often come in bursts: rename, then mark, then settings). */
    fun schedule(delayMs: Long = 10_000) {
        if (phraseKeys() == null && app.signers.session == null) return
        pendingSave?.cancel()
        pendingSave = app.scope.launch {
            delay(delayMs)
            save()
        }
    }

    // ------------------------------------------------------------------ tag <-> backup entry

    fun toBackup(t: Tracker) = BackupTag(
        name = t.name, k0Hex = t.keyHex.lowercase(), type = t.type, mark = "${t.markColor}-${t.markSymbol}",
        settings = TagSettings(
            intervalMin = t.intervalMin,
            reportEvery = t.reportEvery,
            checkS = t.checkS,
            still = if (t.stillAfterEff > 0) TagSettings.Still(t.stillAfterEff, t.stillHbH) else null,
            use = t.useKind,
            fw = t.firmware,
            zones = t.zones.map { BackupZone(it.name, it.lat, it.lon, it.radiusM, it.notifyLeave, it.notifyArrive) },
            anchors = t.knownDevices.map { BackupAnchor(it.name, it.kind) },
            alerts = TagSettings.Alerts(t.alerts.silent, t.alerts.lowBattery, t.alerts.leftZone, t.alerts.guard),
            guard = if (t.guardLat != null && t.guardLon != null) TagSettings.Guard(t.guardLat, t.guardLon, LibreTagApp.GUARD_M.toInt(), t.guardSince) else null,
        ),
    )

    /**
     * Adds the backup's live tags that this phone doesn't have. Bluetooth anchors can't come back
     * (the backup has only their labels), so a restored tag counts as a little older than the
     * backup entry it came from: the backup keeps the labels until this phone changes the tag.
     */
    fun import(tags: List<BackupTag>): Int {
        val have = app.store.trackers.value.map { it.keyHex.lowercase() }.toSet()
        val add = tags.filter { !it.isRemoved && it.k0Hex.lowercase() !in have && it.k0Hex.unhex()?.size == 32 }
        if (add.isEmpty()) return 0
        val made = add.map { bt ->
            val used = app.store.trackers.value.map { it.markColor to it.markSymbol }
            val m = bt.mark.split('-')
            val mark = if (m.size == 2 && (m[0] to m[1]) !in used) m[0] to m[1] else Marks.free(used)
            val s = bt.settings
            Tracker(
                name = bt.name, keyHex = bt.k0Hex.lowercase(), type = bt.type.ifEmpty { "thing" },
                markColor = mark.first, markSymbol = mark.second, createdAt = System.currentTimeMillis(),
                intervalMin = s?.intervalMin ?: 60,
                reportEvery = s?.reportEvery ?: 1,
                checkS = s?.checkS ?: Tracker.DEFAULT_CHECK_S,
                stillAfter = s?.let { it.still?.after ?: 0 },
                stillHbH = s?.still?.hbH ?: Tracker.DEFAULT_STILL_HB_H,
                use = s?.use?.takeIf { it == Tracker.USE_THINGS || it == Tracker.USE_PEOPLE } ?: "",
                firmware = s?.fw,
                zones = s?.zones.orEmpty().map { Zone(it.name, it.lat, it.lon, it.rM, it.notifyLeave, it.notifyArrive) },
                alerts = s?.alerts?.let { AlertPrefs(it.silent, it.lowBattery, it.leftZone, it.guard) } ?: AlertPrefs(),
                guardLat = s?.guard?.lat, guardLon = s?.guard?.lon, guardSince = s?.guard?.since,
            ) to (bt.updated ?: now())
        }
        saveBook(book.adopt(made.map { (t, at) -> toBackup(t).copy(updated = maxOf(0, at - 1)) }))
        made.forEach { app.store.add(it.first) }
        return made.size
    }

    // ------------------------------------------------------------------ copy A keys

    /** The keys derived from the phrase; the phrase itself is never stored. */
    fun phraseKeys(): Derived? {
        val d = p.getString("aD", null) ?: return null
        val sk = p.getString("aSk", null)?.let { Secrets.unwrap(it) }?.unhex() ?: return null
        val enc = p.getString("aEnc", null)?.let { Secrets.unwrap(it) }?.unhex() ?: return null
        return Derived(d, sk, NostrEvent.xonly(sk).hex(), enc)
    }

    private fun setPhraseKeys(d: Derived?) {
        val e = p.edit()
        if (d == null) listOf("aD", "aSk", "aEnc", "aAt", "aRelays", "aRev", "aProblem").forEach { e.remove(it) }
        else e.putString("aD", d.locator).putString("aSk", Secrets.wrap(d.sk.hex())).putString("aEnc", Secrets.wrap(d.encKey.hex()))
        e.apply()
    }

    private fun relaysA(): List<String> = app.store.relays

    // ------------------------------------------------------------------ copy B relays

    /** The owner's NIP-65 write relays (cached 6 h) plus the app's relays. */
    private suspend fun relaysB(ownerHex: String): List<String> {
        val cachedAt = p.getLong("bWriteAt", 0)
        var write = p.getString("bWrite", null)?.split(' ')?.filter { it.isNotBlank() }
        if (write == null || System.currentTimeMillis() - cachedAt > 6 * 3600_000L || p.getString("bWriteOf", null) != ownerHex) {
            val f = buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(10002)) })
                put("authors", buildJsonArray { add(JsonPrimitive(ownerHex)) })
            }
            val r = RelayOnce.query(app.store.relays + INDEXERS, listOf(f))
            if (r.answered > 0) {
                write = RelayOnce.writeRelays(r.events, ownerHex)
                p.edit().putString("bWrite", write.joinToString(" ")).putLong("bWriteAt", System.currentTimeMillis()).putString("bWriteOf", ownerHex).apply()
            }
        }
        return (write.orEmpty() + app.store.relays).distinct()
    }

    private fun npubD(): String? = p.getString("bD", null)

    // ------------------------------------------------------------------ save

    private fun payload(tags: List<BackupTag>) =
        BackupPayload(created = now(), owner = app.store.ownerNpub, tags = tags, d = npubD())

    /**
     * Read both copies, merge, write both. Returns when done; problems end up in [state].
     * Copy B is written only if it could be read first (or there is none yet), so a device never
     * overwrites another device's changes it hasn't seen.
     */
    suspend fun save() = mutex.withLock {
        val keysA = phraseKeys()
        val signer = app.signers.signer()
        if (keysA == null && signer == null) return@withLock
        emit(busy = true)
        val revAtStart = rev()
        val local = BackupPayload(created = now(), owner = app.store.ownerNpub,
            tags = book.entries(owned().map { toBackup(it) }), d = npubD())
        val sources = mutableListOf(local)
        var writeA = keysA != null
        var writeB = signer != null
        var problemA: Problem? = null
        var problemB: Problem? = null
        var relaysB: List<String> = emptyList()

        if (keysA != null) {
            val r = RelayOnce.query(relaysA(), listOf(KeyBackup.filter(keysA)))
            if (r.answered == 0) { writeA = false; problemA = Problem.OFFLINE }
            else when (val got = KeyBackup.restore(keysA, r.events)) {
                is RestoreResult.Found -> sources += got.payload
                RestoreResult.Deleted -> { writeA = false; problemA = Problem.DELETED_ELSEWHERE }
                RestoreResult.Unreadable -> { writeA = false; problemA = Problem.UNREADABLE }
                RestoreResult.NotFound -> {}
            }
        }
        if (signer != null) {
            try {
                relaysB = relaysB(signer.pubkeyHex)
                var d = npubD()
                val r = RelayOnce.query(relaysB, listOf(if (d != null) NpubCopy.filter(signer.pubkeyHex, d) else NpubCopy.discoveryFilter(signer.pubkeyHex)))
                if (r.answered == 0) { writeB = false; problemB = Problem.OFFLINE }
                else {
                    val got = if (d != null) NpubCopy.read(signer, d, r.events) else NpubCopy.discover(signer, r.events)
                    when (got) {
                        is NpubCopy.Found.Backup -> { sources += got.payload; if (d == null) { d = got.payload.d; p.edit().putString("bD", d).apply() } }
                        NpubCopy.Found.Deleted -> { writeB = false; problemB = Problem.DELETED_ELSEWHERE }
                        NpubCopy.Found.None -> if (d == null) p.edit().putString("bD", NpubCopy.newD()).apply()
                    }
                }
            } catch (e: SignerException) {
                writeB = false
                problemB = if (e.reason == SignerException.Reason.UNAVAILABLE) Problem.OFFLINE else Problem.SIGNER
            }
        }

        val merged = BackupMerge.merge(sources, now())
        val out = payload(merged)
        val have = owned().map { it.keyHex.lowercase() }.toSet()
        val notHere = merged.filter { !it.isRemoved && it.k0Hex !in have }

        if (writeA && keysA != null) {
            val n = RelayOnce.publish(relaysA(), KeyBackup.seal(keysA, out))
            if (n > 0) p.edit().putLong("aAt", System.currentTimeMillis()).putInt("aRelays", n).putLong("aRev", revAtStart).apply()
            else problemA = Problem.FAILED
        }
        if (writeB && signer != null && out.d != null) {
            try {
                val n = RelayOnce.publish(relaysB, NpubCopy.seal(signer, out))
                if (n > 0) p.edit().putLong("bAt", System.currentTimeMillis()).putInt("bRelays", n).putLong("bRev", revAtStart).apply()
                else problemB = Problem.FAILED
            } catch (e: SignerException) {
                problemB = if (e.reason == SignerException.Reason.UNAVAILABLE) Problem.OFFLINE else Problem.SIGNER
            }
        }
        p.edit().putString("aProblem", problemA?.name).putString("bProblem", problemB?.name).apply()
        emit(busy = false, notHere = notHere)
    }

    // ------------------------------------------------------------------ turning copies on and off

    /** Copy A with keys derived from a new or restored phrase; saves right away. */
    suspend fun usePhrase(d: Derived) {
        setPhraseKeys(d)
        p.edit().putLong("aRev", -1).apply()
        emit()
        save()
    }

    /**
     * Restore with a phrase: the newest backup at the phrase's address. A found backup is imported
     * and the phrase's keys are kept so this phone keeps the copy up to date.
     */
    suspend fun restoreWithPhrase(d: Derived): Pair<RestoreResult, Int> {
        val r = RelayOnce.query(relaysA(), listOf(KeyBackup.filter(d)))
        if (r.answered == 0) return RestoreResult.NotFound to -1
        val got = KeyBackup.restore(d, r.events)
        var added = 0
        if (got is RestoreResult.Found) {
            added = import(got.payload.tags)
            if (app.store.ownerNpub == null) got.payload.owner?.let { app.store.ownerNpub = it }
            setPhraseKeys(d)
            emit()
            schedule(2_000)
        }
        return got to added
    }

    /** After signing in: look for an existing copy for this npub and import it, then save. */
    suspend fun signedIn(): Int {
        val signer = app.signers.signer() ?: return 0
        p.edit().remove("bD").remove("bAt").remove("bRelays").remove("bProblem").putLong("bRev", -1).apply()
        if (app.store.ownerNpub == null) app.store.ownerNpub = app.nostrNpub(signer.pubkeyHex)
        var added = 0
        val relays = relaysB(signer.pubkeyHex)
        val r = RelayOnce.query(relays, listOf(NpubCopy.discoveryFilter(signer.pubkeyHex)))
        val got = NpubCopy.discover(signer, r.events)
        if (got is NpubCopy.Found.Backup) {
            p.edit().putString("bD", got.payload.d).apply()
            added = import(got.payload.tags)
        }
        emit()
        save()
        return added
    }

    /** Deletes copy A from the relays (empty replacement + NIP-09) and forgets the phrase's keys. */
    suspend fun deletePhraseCopy(): Boolean {
        val d = phraseKeys() ?: return false
        var n = 0
        for (ev in KeyBackup.deletion(d)) n += RelayOnce.publish(relaysA(), ev)
        if (n > 0) { setPhraseKeys(null); emit() }
        return n > 0
    }

    /** Signs out; with [delete] the copy for the npub is first replaced by an empty event. */
    suspend fun signOut(delete: Boolean): Boolean {
        val signer = app.signers.signer()
        val d = npubD()
        if (delete && signer != null && d != null) {
            val relays = relaysB(signer.pubkeyHex)
            var n = 0
            for (ev in NpubCopy.deletion(signer, d)) n += RelayOnce.publish(relays, ev)
            if (n == 0) return false
        }
        app.signers.signOut()
        listOf("bD", "bAt", "bRelays", "bRev", "bProblem", "bWrite", "bWriteAt", "bWriteOf").fold(p.edit()) { e, k -> e.remove(k) }.apply()
        emit()
        return true
    }

    /** The 0.7.0 passphrase backup (free-form passphrase): only deleting is left. */
    fun legacy(): Derived? {
        val loc = app.prefs.backupLocator ?: return null
        val sk = app.prefs.backupSkHex?.unhex() ?: return null
        return Derived(loc, sk, NostrEvent.xonly(sk).hex(), ByteArray(32))
    }

    suspend fun deleteLegacy(): Boolean {
        val d = legacy() ?: return false
        var n = 0
        for (ev in KeyBackup.deletion(d)) n += RelayOnce.publish(relaysA(), ev)
        if (n > 0) {
            app.prefs.backupAt = 0; app.prefs.backupRelays = 0; app.prefs.backupLocator = null; app.prefs.backupSkHex = null
        }
        return n > 0
    }

    companion object {
        /** relays that collect NIP-65 lists, only for finding the owner's write relays */
        val INDEXERS = listOf("wss://purplepag.es")
    }
}
