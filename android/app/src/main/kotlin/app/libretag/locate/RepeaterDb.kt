package app.libretag.locate

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A repeater with a known position (from the public MeshCore map). */
@Serializable
data class Repeater(
    val key: String,      // first 8 bytes of the public key, hex
    val name: String,
    val lat: Double,
    val lon: Double,
    val freq: Double = 0.0,
    val sf: Int = 0,
    val lastAdvert: Long = 0, // unix s
)

/**
 * Local copy of the repeaters on the public map (map.meshcore.io). Downloaded whole and searched
 * on the phone: asking a server "where is repeater a5e1" would tell it where the tracker is.
 */
class RepeaterDb(private val context: Context) {
    private val file = File(context.filesDir, "repeaters.json")
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<Repeater>? = null

    val downloadedAt: Long get() = if (file.exists()) file.lastModified() else 0

    fun all(): List<Repeater> = cache ?: (if (file.exists()) runCatching {
        json.decodeFromString<List<Repeater>>(file.readText())
    }.getOrDefault(emptyList()) else emptyList()).also { cache = it }

    /** Candidates for a 2-byte key prefix (hex), nearest to [near] first when given. */
    fun byPrefix(prefix: String, near: Pair<Double, Double>? = null): List<Repeater> {
        val c = all().filter { it.key.startsWith(prefix, ignoreCase = true) }
        return if (near == null) c.sortedByDescending { it.lastAdvert }
        else c.sortedBy { Geo.distanceM(near.first, near.second, it.lat, it.lon) }
    }

    /** Streams the whole node list (~60 MB) and keeps repeaters with a position. Blocking. */
    fun download(): Int {
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).build()
        val req = Request.Builder().url(URL).header("User-Agent", "LibreTag").build()
        val out = mutableListOf<Repeater>()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            JsonReader(InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) readNode(r)?.let { out += it }
                r.endArray()
            }
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(out))
        tmp.renameTo(file)
        cache = out
        return out.size
    }

    private fun readNode(r: JsonReader): Repeater? {
        var key = ""; var name = ""; var type = 0; var lat = 0.0; var lon = 0.0
        var freq = 0.0; var sf = 0; var last = 0L
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "public_key" -> key = r.nextString()
                "adv_name" -> name = if (r.peek() == JsonToken.NULL) { r.nextNull(); "" } else r.nextString()
                "type" -> type = r.nextInt()
                "adv_lat" -> lat = if (r.peek() == JsonToken.NULL) { r.nextNull(); 0.0 } else r.nextDouble()
                "adv_lon" -> lon = if (r.peek() == JsonToken.NULL) { r.nextNull(); 0.0 } else r.nextDouble()
                "last_advert" -> last = if (r.peek() == JsonToken.NULL) { r.nextNull(); 0L }
                    else runCatching { Instant.parse(r.nextString()).epochSecond }.getOrDefault(0L)
                "params" -> if (r.peek() == JsonToken.NULL) r.nextNull() else {
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "freq" -> freq = r.nextDouble()
                        "sf" -> sf = r.nextInt()
                        else -> r.skipValue()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (type != 2 || key.length < 16 || (lat == 0.0 && lon == 0.0)) return null
        return Repeater(key.take(16).lowercase(), name, lat, lon, freq, sf, last)
    }

    companion object {
        const val URL = "https://map.meshcore.io/api/v1/nodes"
    }
}
