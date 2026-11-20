package app.nostrmesh

import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.MeshLink
import app.nostrmesh.bridge.Publisher
import app.nostrmesh.bridge.Route
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.sha256
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class FakeMesh(var capabilities0: Set<String> = emptySet(), override val maxPayload: Int = 163) : MeshLink {
    data class Sent(val channel: String, val dataType: Int, val payload: ByteArray, val route: Route)
    val sent = mutableListOf<Sent>()
    override val capabilities get() = capabilities0
    override suspend fun send(channel: String, dataType: Int, payload: ByteArray, route: Route): Boolean {
        sent += Sent(channel, dataType, payload, route)
        return true
    }
}

class FakeRelays(override val publishUrls: List<String> = listOf("wss://nostr.example.com")) : Publisher {
    val published = mutableListOf<JsonObject>()
    val subs = mutableMapOf<String, List<JsonObject>>()
    /** What [query] answers (any filter). */
    val stored = mutableListOf<JsonObject>()
    var queries = 0
    val publishedOnce = mutableListOf<Pair<JsonObject, List<String>>>()
    override suspend fun publish(event: JsonObject): Boolean { published += event; return true }
    override suspend fun query(filters: List<JsonObject>): List<JsonObject> { queries++; return stored.toList() }
    override suspend fun publishOnce(event: JsonObject, urls: List<String>): Boolean { publishedOnce += event to urls; return true }
    override fun subscribe(subId: String, filters: List<JsonObject>) { subs[subId] = filters }
    fun ofKind(k: Int) = published.filter { it["kind"]!!.jsonPrimitive.int == k }.map { NostrEvent.fromJson(it)!! }
}

fun testKey(n: Int) = sha256("bridge test key $n".toByteArray())
fun testPub(n: Int) = NostrEvent.xonly(testKey(n))

/** Deterministic distinct "random" keys for the bridge's per-event keys. */
fun keySource(seed: Int = 1000): () -> ByteArray {
    var i = seed
    return { testKey(i++) }
}

/**
 * The tests of other topics (routes, claims, limits, groups) predate the signed mailbox (PROTOCOL.md 4.2):
 * they run with anchor lead 0, their hear() helpers post the owner's anchor just before the report, and
 * their entries are signed by the owner's sk_n ([ownerEntry]). The signed mailbox has its own tests.
 */
fun BridgeConfig.legacyTiming() = copy(anchorLeadMs = 0)

/** The tag of lt2.json (k0 = 00..1f): view and command chains, report tags, mailbox keys sk_n. */
object Owner {
    private val k0 = ByteArray(32) { it.toByte() }
    private val N = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = javax.crypto.Mac.getInstance("HmacSHA256")
        m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    private fun u32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun chain(start: ByteArray, e: Int): ByteArray {
        var k = start
        repeat(e) { k = hmac(k, "LT2-next".toByteArray()) }
        return k
    }
    private val views = HashMap<Int, ByteArray>()
    private val cmds = HashMap<Int, ByteArray>()
    fun view(e: Int) = views.getOrPut(e) { chain(hmac(k0, "LT2-view".toByteArray()), e) }
    fun cmd(e: Int) = cmds.getOrPut(e) { chain(hmac(k0, "LT2-cmd".toByteArray()), e) }

    fun tag(n: Int, j: Int): String = hmac(view(n / 64), "LT2-x".toByteArray(), u32(n), byteArrayOf(j.toByte())).copyOf(4).hex()

    /** sk_n = HMAC(C_e(n), "LT2-mb" || u32(n)) mod N, 0 -> 1. */
    fun sk(n: Int): ByteArray = skFromChain(cmd(n / 64), n)

    /** The same derivation from any chain key (from the view chain: what a view share could at best make). */
    fun skFromChain(chain: ByteArray, n: Int): ByteArray {
        var v = java.math.BigInteger(1, hmac(chain, "LT2-mb".toByteArray(), u32(n))).mod(N)
        if (v.signum() == 0) v = java.math.BigInteger.ONE
        val b = v.toByteArray()
        return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
    }
    fun pk(n: Int): String = NostrEvent.xonly(sk(n)).hex()

    private val nOf: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (n in 0 until 1300) for (j in 0..1) m[tag(n, j)] = n
        m
    }

    /** Counter of a report tag of this tag, or null. */
    fun nOf(tag: String): Int? = nOf[tag]

    /** The owner's anchor for report n (PROTOCOL.md 4.2). */
    fun anchor(n: Int, createdS: Long, sk: ByteArray? = null, extra: List<List<String>> = emptyList()): NostrEvent =
        NostrEvent.sign(sk ?: sk(n), createdS, 4244,
            listOf(listOf("v", "2"), listOf("y", tag(n, 0)), listOf("y", tag(n, 1)),
                listOf("expiration", (createdS + 26 * 3600).toString())) + extra, "")
}

/**
 * A mailbox entry as the owner's app signs it: ["v", "2"] added, route "near" read as "flood" (format 1
 * ignored the route; binding routes are tested in MarketTest), signed with sk_n of its `via` or first `y`
 * unless [sk] is given (a stranger).
 */
fun ownerEntry(createdS: Long, tags0: List<List<String>>, content: String, sk: ByteArray? = null): NostrEvent {
    val legacy = tags0.none { it.size >= 2 && it[0] == "v" }
    val tags = if (!legacy) tags0 else listOf(listOf("v", "2")) +
        tags0.map { if (it.size >= 2 && it[0] == "route" && it[1] == "near") listOf("route", "flood") else it }
    val ref = tags.firstOrNull { it.size >= 2 && it[0] == "via" }?.get(1) ?: tags.first { it.size >= 2 && it[0] == "y" }[1]
    val key = sk ?: Owner.nOf(ref)?.let { Owner.sk(it) } ?: Synth.skOf(ref) ?: testKey(50)
    return NostrEvent.sign(key, createdS, 4244, tags, content)
}

/** Before hearing a report of [Owner]: its anchor reaches the bridge (the tests with [legacyTiming]). */
suspend fun app.nostrmesh.bridge.Bridge.ownerAnchor(p: ByteArray, nowS: Long) {
    if (p.size != 45) return
    val y = p.copyOf(4).hex()
    val n = Owner.nOf(y)
    val ev = when {
        n != null -> Owner.anchor(n, nowS - 10)
        Synth.skOf(y) != null -> NostrEvent.sign(Synth.skOf(y)!!, nowS - 10, 4244,
            listOf(listOf("v", "2"), listOf("y", y), listOf("expiration", (nowS + 26 * 3600).toString())), "")
        else -> return
    }
    onRelayEvent(ev.toJson(), "wss://anchor")
}

/** The 4-byte commitment check's other side: c = SHA-256("LT2-mbc" || pk || P[0:4] || P[7:])[0:3]. */
fun commitOf(pk: ByteArray, p: ByteArray): ByteArray =
    sha256("LT2-mbc".toByteArray() + pk + p.copyOfRange(0, 4) + p.copyOfRange(7, p.size)).copyOf(3)

/**
 * Synthetic 45 B reports whose commitment fits a test key of their own (as if a tag had sealed them), for
 * tests that need many reports: [report] registers the key, [ownerEntry] and [ownerAnchor] sign with it.
 */
object Synth {
    private val keys = HashMap<String, ByteArray>()
    fun sk(i: Int) = testKey(7000 + i)
    fun pk(i: Int): String = NostrEvent.xonly(sk(i)).hex()
    fun skOf(tag: String): ByteArray? = synchronized(keys) { keys[tag] }

    fun report(i: Int): ByteArray {
        val p = ByteArray(45) { (it * 13 + i).toByte() }.also { it[0] = i.toByte(); it[1] = (i shr 8).toByte() }
        commitOf(NostrEvent.xonly(sk(i)), p).copyInto(p, 4)
        synchronized(keys) { keys[p.copyOf(4).hex()] = sk(i) }
        return p
    }
}
