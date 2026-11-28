package app.nostrmesh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Field-test log, the same lines as the Python bridge's (host/src/nostrmesh/fieldlog.py, documented in
 * docs/design/field-test-logs.md): `{"t": unix ms, "src": "bridge", "bridge": "android", "ev": …}` per
 * line in `files/fieldlog/bridge-YYYYMMDD.jsonl` (UTC day, kept 30 days). Fields the Local API server
 * does not give (path, route, RSSI without `rx_path` / `rx_meta`) are left out. Pull with
 * `adb pull /sdcard/Android/data/app.nostrmesh/files/fieldlog` (external files dir).
 */
object FieldLog {
    const val DIR = "fieldlog"
    private const val KEEP_DAYS = 30L
    private var dir: File? = null
    private var day = ""
    private val dayFmt = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun init(filesDir: File) { dir = File(filesDir, DIR).also { runCatching { it.mkdirs() } } }

    fun files(): List<File> = dir?.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()

    fun ev(ev: String, vararg fields: Pair<String, Any?>) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val m = LinkedHashMap<String, JsonElement>()
        m["t"] = JsonPrimitive(now)
        m["src"] = JsonPrimitive("bridge")
        m["bridge"] = JsonPrimitive("android")
        m["ev"] = JsonPrimitive(ev)
        for ((k, v) in fields) json(v)?.let { m[k] = it }
        write(d, now, JsonObject(m).toString())
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun json(v: Any?): JsonElement? = when (v) {
        null -> null
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Double -> if (v.isFinite()) JsonPrimitive(v) else null
        is Number -> JsonPrimitive(v)
        is ByteArray -> JsonPrimitive(hex(v))
        is Collection<*> -> JsonArray(v.map { json(it) ?: JsonNull })
        else -> JsonPrimitive(v.toString())
    }

    @Synchronized
    private fun write(d: File, now: Long, line: String) {
        runCatching {
            val today = dayFmt.format(Instant.ofEpochMilli(now))
            if (today != day) {
                day = today
                val cutoff = dayFmt.format(Instant.ofEpochMilli(now).minusSeconds(KEEP_DAYS * 86_400))
                d.listFiles()?.filter { it.name.startsWith("bridge-") && it.name.removePrefix("bridge-").take(8) < cutoff }
                    ?.forEach { it.delete() }
            }
            File(d, "bridge-$today.jsonl").appendText(line + "\n")
        }
    }

    /** A received path as hex hashes, first hop first. */
    fun pathList(path: ByteArray?, hashSize: Int?): List<String>? {
        if (path == null) return null
        val hs = (hashSize ?: 1).coerceIn(1, 3)
        return path.toList().chunked(hs).map { hex(it.toByteArray()) }
    }
}
