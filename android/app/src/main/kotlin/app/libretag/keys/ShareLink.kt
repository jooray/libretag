package app.libretag.keys

import app.libretag.data.Marks
import app.libretag.proto.LibreTag
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The share link `libretag://share?v=1&name&type&mark&e&ve[&ce[&uk&id]]` (PROTOCOL.md 1, sharing).
 * Three levels: view (`V_e`), view and control (`V_e`, `C_e`) and full access (also the tag's
 * `usb_key` and `id`, which open its console over USB and Bluetooth like the owner's phone).
 * Never K0, so the past before epoch e stays closed. Pure Kotlin, so it runs in unit tests.
 */
object ShareLink {
    data class Shared(
        val name: String, val type: String, val color: String, val symbol: String, val epoch: Long,
        val view: String, val cmd: String?,
        /** usb_key and id (hex) of a full-access share, else null */
        val usb: String? = null, val id: String? = null,
    ) {
        val admin: Boolean get() = usb != null
        val control: Boolean get() = cmd != null
    }

    fun build(name: String, type: String, mark: String, epoch: Long, viewE: ByteArray, cmdE: ByteArray?,
              usbKey: ByteArray? = null, id: ByteArray? = null): String {
        require(usbKey == null || (cmdE != null && id != null)) { "full access includes control and the id" }
        val p = mutableListOf("v" to "1", "name" to name, "type" to type, "mark" to mark,
            "e" to epoch.toString(), "ve" to LibreTag.bytesToHex(viewE))
        cmdE?.let { p += "ce" to LibreTag.bytesToHex(it) }
        if (usbKey != null) {
            p += "uk" to LibreTag.bytesToHex(usbKey)
            p += "id" to LibreTag.bytesToHex(id!!)
        }
        return "libretag://share?" + p.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** Null for anything that isn't a whole share link (a full-access link without ce or id included). */
    fun parse(s: String): Shared? = runCatching {
        val t = s.trim()
        val prefix = "libretag://share?"
        if (!t.startsWith(prefix, ignoreCase = true)) return null
        val q = t.substring(prefix.length).substringBefore('#').split('&').filter { it.isNotEmpty() }
            .associate { kv -> URLDecoder.decode(kv.substringBefore('='), "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }
        fun key(k: String, size: Int): String? = q[k]?.takeIf { LibreTag.hexToBytes(it)?.size == size }?.lowercase()
        val ve = key("ve", 32) ?: return null
        val ce = key("ce", 32)
        if (q["ce"] != null && ce == null) return null
        val uk = key("uk", 32)
        val id = key("id", 8)
        if (q["uk"] != null && (uk == null || id == null || ce == null)) return null
        val mark = q["mark"]?.split('-')
        Shared(q["name"]?.ifBlank { null } ?: "Tag", q["type"]?.ifBlank { null } ?: "thing",
            mark?.getOrNull(0)?.takeIf { it in Marks.COLORS } ?: "", mark?.getOrNull(1)?.takeIf { it in Marks.SYMBOLS } ?: "",
            q["e"]?.toLongOrNull()?.takeIf { it >= 0 } ?: 0, ve, ce, uk, if (uk != null) id else null)
    }.getOrNull()

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
