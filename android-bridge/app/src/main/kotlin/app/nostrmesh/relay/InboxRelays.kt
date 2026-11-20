package app.nostrmesh.relay

import app.nostrmesh.nostr.NostrEvent
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Inbox relays of a DM recipient (port of host/src/nostrmesh/inbox.py): NIP-17 (kind 10050,
 * `relay` tags) first, then NIP-65 (kind 10002, `r` tags marked read or unmarked). A DM the bridge
 * publishes also goes there, so the recipient's client finds it. Only public wss:// relays: no
 * plain ws, no local names, no private or special addresses.
 */
object InboxRelays {
    val KINDS = listOf(10002, 10050)
    const val MAX_EXTRA = 6
    const val TTL_MS = 6 * 3600_000L
    /** Nothing found (relays offline, no list): ask again after this. */
    const val RETRY_MS = 600_000L
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".internal", ".localhost", ".home.arpa")
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Normalised wss:// URL, or null for anything a bridge must not dial. */
    fun safeUrl(url: String?): String? {
        if (url == null || url.length > 256) return null
        val u = try {
            URI(url.trim())
        } catch (e: Exception) {
            return null
        }
        if (u.scheme?.lowercase() != "wss" || u.rawUserInfo != null) return null
        val rawHost = u.host?.lowercase() ?: return null
        val host = rawHost.removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return null
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return null
        val v6 = ':' in host
        if (!v6 && '.' !in host) return null
        if (v6 || IPV4.matches(host)) {
            // a literal: InetAddress does not resolve it, it only parses
            val ip = try {
                InetAddress.getByName(host)
            } catch (e: Exception) {
                return null
            }
            if (!isGlobal(ip)) return null
        } else if (host.all { it.isDigit() || it == '.' }) {
            return null // "1.2.3" and the like
        }
        val port = if (u.port >= 0) ":${u.port}" else ""
        val h = if (v6) "[$host]" else host
        val path = (u.rawPath ?: "").trimEnd('/')
        return "wss://$h$port$path"
    }

    /** Roughly Python's `ipaddress.is_global`. */
    fun isGlobal(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress ||
            ip.isMulticastAddress
        ) return false
        val b = ip.address.map { it.toInt() and 0xFF }
        return when (ip) {
            is Inet4Address -> !(
                b[0] == 0 || b[0] >= 240 ||
                    (b[0] == 100 && b[1] in 64..127) || // CGNAT
                    (b[0] == 192 && b[1] == 0 && b[2] in listOf(0, 2)) ||
                    (b[0] == 198 && b[1] in 18..19) ||
                    (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                    (b[0] == 203 && b[1] == 0 && b[2] == 113)
                )
            is Inet6Address -> !(
                (b[0] and 0xFE) == 0xFC || // ULA fc00::/7
                    (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8) || // documentation
                    (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] < 0x02) || // 2001::/23 special
                    b.take(10).all { it == 0 } // ::, ::1, v4-mapped / compatible
                )
            else -> false
        }
    }

    /** Inbox relays from the newest kind 10050 and 10002 of [pubkey] (10050 first: it is the DM list). */
    fun parse(events: List<NostrEvent>, pubkey: String): List<String> {
        val newest = mutableMapOf<Int, NostrEvent>()
        for (e in events) {
            if (e.pubkey != pubkey || e.kind !in KINDS) continue
            val have = newest[e.kind]
            if (have == null || e.createdAt > have.createdAt) newest[e.kind] = e
        }
        val out = mutableListOf<String>()
        for (k in listOf(10050, 10002)) {
            for (t in newest[k]?.tags.orEmpty()) {
                if (t.size < 2) continue
                if (k == 10050 && t[0] != "relay") continue
                if (k == 10002 && (t[0] != "r" || (t.size > 2 && t[2] != "read"))) continue
                val u = safeUrl(t[1]) ?: continue
                if (u !in out) out += u
            }
        }
        return out
    }

    /** Inbox relays not already in the bridge's publish set, at most [limit]. */
    fun extra(inbox: List<String>, configured: List<String>, limit: Int = MAX_EXTRA): List<String> {
        val have = configured.map { safeUrl(it) ?: it }.toSet()
        return inbox.filter { it !in have }.take(limit)
    }

    /** Lookups per recipient: 6 h when something was found, 10 min when not. */
    class Cache(private val cap: Int = 1000) {
        private val c = LinkedHashMap<String, Pair<List<String>, Long>>()

        @Synchronized
        fun get(pubkey: String, now: Long): List<String>? {
            val (urls, at) = c[pubkey] ?: return null
            return urls.takeIf { now - at < if (urls.isNotEmpty()) TTL_MS else RETRY_MS }
        }

        @Synchronized
        fun put(pubkey: String, urls: List<String>, now: Long) {
            c.remove(pubkey)
            c[pubkey] = urls to now
            while (c.size > cap) c.remove(c.keys.first())
        }
    }
}
