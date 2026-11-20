package app.libretag.nostr

import java.net.URI

/**
 * Where a DM reaches its recipient: the relays the recipient reads from, besides our own.
 * NIP-65 kind 10002 (`r` tags without a marker or marked `read`) and NIP-17 kind 10050
 * (`relay` tags), newest event of each kind. Only public `wss://` relays; nothing on
 * localhost, `.local` or private address ranges.
 */
object InboxRelays {
    const val KIND_RELAY_LIST = 10002
    const val KIND_DM_RELAYS = 10050
    const val MAX_EXTRA = 6
    const val CACHE_MS = 6 * 3600_000L

    fun parse(events: List<NostrEvent>, pubkeyHex: String? = null): List<String> {
        fun newest(kind: Int) = events.filter { it.kind == kind && (pubkeyHex == null || it.pubkey == pubkeyHex) }
            .maxByOrNull { it.createdAt }
        val dm = newest(KIND_DM_RELAYS)?.tags.orEmpty().filter { it.size >= 2 && it[0] == "relay" }.map { it[1] }
        val read = newest(KIND_RELAY_LIST)?.tags.orEmpty()
            .filter { it.size >= 2 && it[0] == "r" && (it.size == 2 || it[2].isBlank() || it[2] == "read") }.map { it[1] }
        return (dm + read).mapNotNull { normalize(it) }.filter { isPublicWss(it) }.distinct()
    }

    /**
     * NIP-17 inbox: only the `relay` tags of the newest kind 10050. Empty means the recipient has
     * not said where they take private DMs (NIP-17: then don't send a gift wrap).
     */
    fun dmRelays(events: List<NostrEvent>, pubkeyHex: String? = null): List<String> =
        events.filter { it.kind == KIND_DM_RELAYS && (pubkeyHex == null || it.pubkey == pubkeyHex) }.maxByOrNull { it.createdAt }
            ?.tags.orEmpty().filter { it.size >= 2 && it[0] == "relay" }.mapNotNull { normalize(it[1]) }
            .filter { isPublicWss(it) }.distinct().take(MAX_EXTRA)

    /** The relays to publish to: ours, then up to [MAX_EXTRA] of the recipient's that we don't have. */
    fun union(configured: List<String>, inbox: List<String>): List<String> {
        val own = configured.mapNotNull { normalize(it) }.toSet()
        return configured + inbox.filter { normalize(it) !in own }.take(MAX_EXTRA)
    }

    /**
     * Where a tag's DM goes: our relays and the recipient's inbox relays (at most [MAX_EXTRA] extra),
     * normalized, without duplicates (`wss://x/` and `wss://x` are one relay).
     */
    fun dmTargets(configured: List<String>, inbox: List<String>): List<String> =
        union(configured.mapNotNull { normalize(it) }.distinct(), inbox).mapNotNull { normalize(it) }.distinct()

    fun normalize(url: String): String? {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("wss://", ignoreCase = true)) "wss://" + u.substring(6) else null
    }

    fun isPublicWss(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
        if (!url.lowercase().startsWith("wss://") || host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")) return false
        val v4 = host.split('.').takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (v4 != null && v4.size == 4) {
            val (a, b) = v4[0] to v4[1]
            return !(a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224)
        }
        if (':' in host) {
            // IPv6 literal: loopback, link-local, unique local
            return !(host == "::1" || host == "::" || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd"))
        }
        return '.' in host
    }
}

/** The tag's DM to a person (NIP-04, kind 4, signed with the tag's dm key). */
object TagDm {
    /** low-battery DMs expire after 14 days (NIP-40): an old battery warning is useless */
    const val LOW_BATTERY_TTL_S = 14 * 86400L

    fun event(sk: ByteArray, toPubkey: ByteArray, text: String, now: Long, expiresAfterS: Long? = null): NostrEvent {
        val tags = listOf(listOf("p", toPubkey.hex())) +
            (expiresAfterS?.let { listOf(listOf("expiration", (now + it).toString())) } ?: emptyList())
        return NostrEvent.sign(sk, now, 4, tags, Nip04.encrypt(sk, toPubkey, text))
    }
}
