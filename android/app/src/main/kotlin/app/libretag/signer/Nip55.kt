package app.libretag.signer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import app.libretag.backup.OwnerSigner
import app.libretag.backup.SignerException
import app.libretag.backup.SignerException.Reason
import app.libretag.nostr.Bech32
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.isLowerHex
import app.libretag.nostr.unhex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * NIP-55: a signer app on this phone (Amber and others). Ported from the shared
 * nostr-signin-android module (Lievik, Emanator) and its rules, each one a bug somebody hit:
 *
 * - `get_public_key` goes through an intent, with `setPackage` and the permissions we will need
 *   (`nip44_encrypt`, `nip44_decrypt`, `sign_event` 30078 and 5), so later calls can run silently
 *   through the content provider and Amber doesn't prompt once per event.
 * - Every later call tries the content provider first (`content://<pkg>.NIP44_DECRYPT` …). A null
 *   cursor means "ask the user"; a `rejected` column can be a stale remembered choice. With a
 *   screen open, both fall back to the intent; in the background they fail with NEEDS_USER.
 * - An intent is launched only when our activity is RESUMED, and a cancel that comes back within
 *   a second without data is retried once: signer activities are singleTask, and an intent that
 *   reaches a signer window that is still closing is answered with RESULT_CANCELED at once.
 * - No SINGLE_TOP | CLEAR_TOP flags. The `package` extra of the get_public_key answer names the
 *   signer for every later request. Results come as `result`, `signature`, `event` or a batched
 *   `results` array; a public key may be npub or hex. A signed event is accepted only if it is
 *   the one we asked for and its signature verifies.
 */
object Nip55 {
    data class SignerApp(val packageName: String, val label: String)

    const val PERMISSIONS =
        """[{"type":"nip44_encrypt"},{"type":"nip44_decrypt"},{"type":"sign_event","kind":30078},{"type":"sign_event","kind":5}]"""

    fun signers(context: Context): List<SignerApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos.map { SignerApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }.distinctBy { it.packageName }
    }

    fun getPublicKeyIntent(pkg: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
        setPackage(pkg)
        putExtra("type", "get_public_key")
        putExtra("permissions", PERMISSIONS)
    }

    fun nip44Intent(pkg: String, type: String, text: String, peerHex: String, userHex: String, id: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$text")).apply {
            setPackage(pkg)
            putExtra("type", type)
            putExtra("pubkey", peerHex)
            putExtra("current_user", Bech32.npub(userHex.unhex()!!))
            putExtra("id", id)
        }

    fun signEventIntent(pkg: String, ev: NostrEvent): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:" + unsignedJson(ev))).apply {
            setPackage(pkg)
            putExtra("type", "sign_event")
            putExtra("id", ev.id)
            putExtra("current_user", Bech32.npub(ev.pubkey.unhex()!!))
        }

    /** The event as a signer expects it: id, pubkey, created_at, kind, tags, content (no sig). */
    fun unsignedJson(ev: NostrEvent): String = ev.toJson().let { o -> JsonObject(o.filterKeys { it != "sig" }) }.toString()

    /** get_public_key answer: (pubkey hex, signer package if given). */
    fun parsePublicKey(extra: (String) -> String?): Pair<String, String?>? {
        val raw = extra("result")?.trim() ?: return null
        val pk = Bech32.pubkey(raw) ?: return null
        val hex = pk.joinToString("") { "%02x".format(it) }
        return hex to extra("package")?.takeIf { it.isNotBlank() }
    }

    /** A NIP-44 answer: the text in `result` (older signers: `signature`). */
    fun parseText(extra: (String) -> String?): String? =
        (extra("result") ?: extra("signature"))?.takeIf { it.isNotEmpty() }

    private val json = Json { ignoreUnknownKeys = true }

    /** A sign_event answer for [expected], rebuilt from our own fields plus the signature. */
    fun parseSigned(expected: NostrEvent, extra: (String) -> String?): NostrEvent? {
        fun accept(sig: String?): NostrEvent? = sig?.trim()?.lowercase()?.takeIf { isLowerHex(it, 64) }
            ?.let { expected.copy(sig = it) }?.takeIf { it.verify() }
        extra("event")?.let { raw -> fromEvent(raw, expected)?.let { return it } }
        for (k in listOf("result", "signature")) accept(extra(k))?.let { return it }
        extra("results")?.let { raw ->
            val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return@let
            for (item in arr) {
                val o = item as? JsonObject ?: continue
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull
                if (id != null && id != expected.id) continue
                (o["event"] as? JsonPrimitive)?.contentOrNull?.let { e -> fromEvent(e, expected)?.let { return it } }
                accept((o["result"] as? JsonPrimitive)?.contentOrNull)?.let { return it }
            }
        }
        return null
    }

    private fun fromEvent(raw: String, expected: NostrEvent): NostrEvent? {
        val o = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        if (str("pubkey")?.lowercase() != expected.pubkey) return null
        if ((o["kind"] as? JsonPrimitive)?.longOrNull != expected.kind.toLong()) return null
        if ((o["created_at"] as? JsonPrimitive)?.longOrNull != expected.createdAt) return null
        str("id")?.let { if (it.lowercase() != expected.id) return null }
        return str("sig")?.lowercase()?.takeIf { isLowerHex(it, 64) }?.let { expected.copy(sig = it) }?.takeIf { it.verify() }
    }

    /** The content provider's answer: a value, REJECTED, or null (ask the user). Blocking. */
    sealed class Silent {
        data class Value(val read: (String) -> String?) : Silent()
        object Rejected : Silent()
    }

    fun query(context: Context, pkg: String, method: String, projection: Array<String>): Silent? {
        val cursor: Cursor = try {
            context.contentResolver.query(Uri.parse("content://$pkg.$method"), projection, null, null, null)
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        return cursor.use { c ->
            if (c.getColumnIndex("rejected") >= 0) return@use Silent.Rejected
            if (!c.moveToFirst()) return@use null
            val values = c.columnNames.associateWith { k -> c.getColumnIndex(k).let { i -> if (i >= 0 && !c.isNull(i)) c.getString(i) else null } }
            Silent.Value { values[it] }
        }
    }
}

/**
 * Launches signer activities from whichever of our screens is in front. A screen calls [attach]
 * in onCreate. One loop on the main thread takes requests one at a time and launches each from
 * the topmost RESUMED screen, so a request never lands on a signer window that is still closing,
 * and two attached screens never compete for the same request. A result that arrives after a
 * screen was recreated (rotation) reaches the new instance's launcher and completes the request.
 * Requests made while no screen is at least STARTED fail at once with NEEDS_USER (background save).
 */
object SignerHost {
    private class Request(val intent: Intent, val result: CompletableDeferred<ActivityResult>)
    private class Host(val activity: ComponentActivity, val launcher: ActivityResultLauncher<Intent>)

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val hosts = java.util.concurrent.CopyOnWriteArrayList<Host>()
    @Volatile private var inFlight: Request? = null
    private val loop by lazy {
        kotlinx.coroutines.MainScope().launch {
            for (req in requests) {
                var h: Host?
                while (true) {
                    h = hosts.lastOrNull { it.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    if (h != null) break
                    if (hosts.none { it.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) }) break
                    delay(100)
                }
                val host = h
                if (host == null) {
                    req.result.completeExceptionally(SignerException(Reason.NEEDS_USER))
                    continue
                }
                inFlight = req
                try {
                    host.launcher.launch(req.intent)
                } catch (e: ActivityNotFoundException) {
                    inFlight = null
                    req.result.completeExceptionally(SignerException(Reason.UNAVAILABLE, "signer app not found"))
                    continue
                }
                runCatching { req.result.await() }
            }
        }
    }

    /** Whether a screen is visible that could show a signer prompt now. */
    val attached: Boolean get() = hosts.any { it.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }

    fun attach(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            inFlight?.let { inFlight = null; it.result.complete(r) }
        }
        val h = Host(activity, launcher)
        hosts += h
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { hosts.remove(h) }
        })
        loop.start()
    }

    suspend fun launch(intent: Intent): ActivityResult {
        if (!attached) throw SignerException(Reason.NEEDS_USER)
        val r = Request(intent, CompletableDeferred())
        requests.send(r)
        return r.result.await()
    }

    /** Launch, with one retry for an instant cancel without data (see [Nip55]). */
    suspend fun launchWithRetry(make: () -> Intent): ActivityResult {
        var attempt = 0
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val r = launch(make())
            if (r.resultCode == Activity.RESULT_OK && r.data != null) return r
            if (SystemClock.elapsedRealtime() - started < 1_000 && r.data == null && attempt++ == 0) {
                delay(400)
                continue
            }
            return r
        }
    }
}

/** The owner's signer on this phone. [pkg]: the signer app; [pubkeyHex]: the owner. */
class Nip55Signer(private val context: Context, val pkg: String, override val pubkeyHex: String) : OwnerSigner {
    private val npub get() = Bech32.npub(pubkeyHex.unhex()!!)

    override suspend fun nip44Encrypt(plaintext: String, peerHex: String): String = nip44("NIP44_ENCRYPT", "nip44_encrypt", plaintext, peerHex)

    override suspend fun nip44Decrypt(payload: String, peerHex: String): String = nip44("NIP44_DECRYPT", "nip44_decrypt", payload, peerHex)

    private suspend fun nip44(method: String, type: String, text: String, peerHex: String): String {
        val silent = withContext(Dispatchers.IO) { Nip55.query(context, pkg, method, arrayOf(text, peerHex, npub)) }
        when (silent) {
            is Nip55.Silent.Value -> Nip55.parseText(silent.read)?.let { return it }
            Nip55.Silent.Rejected -> if (!SignerHost.attached) throw SignerException(Reason.REJECTED)
            null -> {}
        }
        val id = System.nanoTime().toString(36)
        val r = SignerHost.launchWithRetry { Nip55.nip44Intent(pkg, type, text, peerHex, pubkeyHex, id) }
        if (r.resultCode != Activity.RESULT_OK) throw SignerException(Reason.REJECTED)
        val data = r.data ?: throw SignerException(Reason.BAD_ANSWER)
        return Nip55.parseText { data.getStringExtra(it) } ?: throw SignerException(Reason.BAD_ANSWER)
    }

    override suspend fun signEvent(event: NostrEvent): NostrEvent {
        val silent = withContext(Dispatchers.IO) { Nip55.query(context, pkg, "SIGN_EVENT", arrayOf(Nip55.unsignedJson(event), "", npub)) }
        when (silent) {
            is Nip55.Silent.Value -> Nip55.parseSigned(event, silent.read)?.let { return it }
            Nip55.Silent.Rejected -> if (!SignerHost.attached) throw SignerException(Reason.REJECTED)
            null -> {}
        }
        val r = SignerHost.launchWithRetry { Nip55.signEventIntent(pkg, event) }
        if (r.resultCode != Activity.RESULT_OK) throw SignerException(Reason.REJECTED)
        val data = r.data ?: throw SignerException(Reason.BAD_ANSWER)
        return Nip55.parseSigned(event) { data.getStringExtra(it) } ?: throw SignerException(Reason.BAD_ANSWER)
    }

    companion object {
        /** get_public_key (always an intent): (owner pubkey hex, signer package). */
        suspend fun signIn(pkg: String): Pair<String, String> {
            val r = SignerHost.launchWithRetry { Nip55.getPublicKeyIntent(pkg) }
            if (r.resultCode != Activity.RESULT_OK) throw SignerException(Reason.REJECTED)
            val data = r.data ?: throw SignerException(Reason.BAD_ANSWER)
            val (pk, p) = Nip55.parsePublicKey { data.getStringExtra(it) } ?: throw SignerException(Reason.BAD_ANSWER)
            return pk to (p ?: pkg)
        }
    }
}
