package app.libretag

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A small persistent log next to logcat, whose ring buffer on this phone keeps only minutes.
 * Lives in the app's external files dir so it can be pulled after a test drive:
 * `adb pull /sdcard/Android/data/app.libretag/files/libretag.log`. Stays on the phone.
 *
 * Field-test log (2026-11-26): [ev] writes one JSON object per line to
 * `files/fieldlog/app-YYYYMMDD.jsonl` (UTC day, kept 30 days), for `libretag fieldeval` on the
 * host. Every line has `t` (unix ms), `src` "app" and `ev`; the rest depends on the event (see
 * docs/design/field-test-logs.md). Positions appear only in `report` lines and stay on the phone:
 * pull with `adb pull /sdcard/Android/data/app.libretag/files/fieldlog`, or Settings > Share log.
 */
object DiagLog {
    private const val MAX = 2_000_000L
    const val FIELD_DIR = "fieldlog"
    private const val KEEP_DAYS = 30L
    private var file: File? = null
    private var fieldDir: File? = null
    private var fieldDay = ""
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val dayFmt = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun init(ctx: Context) {
        val base = ctx.getExternalFilesDir(null)
        file = base?.let { File(it, "libretag.log") }
        fieldDir = base?.let { File(it, FIELD_DIR) }?.also { runCatching { it.mkdirs() } }
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); write("W", tag, msg) }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX) f.renameTo(File(f.parentFile, "libretag.log.1"))
            f.appendText("${LocalDateTime.now().format(fmt)} $level/$tag: $msg\n")
        }
    }

    /** The field log files, newest last (for the share sheet). */
    fun fieldFiles(): List<File> = fieldDir?.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()

    /** One field-log line: `{"t":…,"src":"app","ev":ev, …fields}`; null values are left out. */
    fun ev(ev: String, vararg fields: Pair<String, Any?>) {
        val now = System.currentTimeMillis()
        val m = LinkedHashMap<String, JsonElement>()
        m["t"] = JsonPrimitive(now)
        m["src"] = JsonPrimitive("app")
        m["ev"] = JsonPrimitive(ev)
        for ((k, v) in fields) json(v)?.let { m[k] = it }
        writeField(now, JsonObject(m).toString())
    }

    internal fun json(v: Any?): JsonElement? = when (v) {
        null -> null
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Double -> if (v.isFinite()) JsonPrimitive(v) else null
        is Float -> if (v.isFinite()) JsonPrimitive(v) else null
        is Number -> JsonPrimitive(v)
        is ByteArray -> JsonPrimitive(v.joinToString("") { "%02x".format(it) })
        is Collection<*> -> JsonArray(v.map { json(it) ?: JsonNull })
        is Map<*, *> -> JsonObject(v.entries.mapNotNull { (k, x) -> json(x)?.let { k.toString() to it } }.toMap())
        else -> JsonPrimitive(v.toString())
    }

    @Synchronized
    private fun writeField(now: Long, line: String) {
        val dir = fieldDir ?: return
        runCatching {
            val day = dayFmt.format(Instant.ofEpochMilli(now))
            if (day != fieldDay) {
                fieldDay = day
                val cutoff = dayFmt.format(Instant.ofEpochMilli(now).minusSeconds(KEEP_DAYS * 86_400))
                dir.listFiles()?.filter { it.name.startsWith("app-") && it.name.removePrefix("app-").take(8) < cutoff }
                    ?.forEach { it.delete() }
            }
            File(dir, "app-$day.jsonl").appendText(line + "\n")
        }
    }
}
