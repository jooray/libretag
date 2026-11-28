package app.nostrmesh.data

import android.content.Context
import app.nostrmesh.BuildConfig
import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.Device
import app.nostrmesh.bridge.LoRaPreset
import app.nostrmesh.nostr.Bech32
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.unhex
import fr.acinq.secp256k1.Secp256k1
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredDevice(
    val name: String,
    val pubkey: String,
    val owner: String? = null,
    val allowFrom: List<String> = emptyList(),
    val sleeps: Boolean = true,
) {
    fun toDevice(): Device? {
        val pk = Bech32.pubkey(pubkey) ?: return null
        val own = owner?.let { Bech32.pubkey(it) }
        val allow = allowFrom.mapNotNull { Bech32.pubkey(it) }.ifEmpty { listOfNotNull(own) }
        return Device(name, pk, own, allow, sleeps)
    }
}

/** Everything the app keeps; observed by the service so changes apply at once. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _version = MutableStateFlow(0)
    /** Bumps on every change of bridge settings. */
    val version: StateFlow<Int> = _version
    private fun changed() { _version.value++ }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); changed() }

    var relays: List<String>
        get() = prefs.getString("relays", null)?.let { json.decodeFromString<List<String>>(it) }
            ?.takeIf { it != OLD_DEFAULT_RELAYS } ?: DEFAULT_RELAYS
        set(v) { prefs.edit().putString("relays", json.encodeToString(v)).apply(); changed() }

    var uplinkProfiles: Set<String>
        get() = prefs.getStringSet("uplink", null)?.let { migrateProfiles(it) } ?: BridgeConfig.DEFAULT_UPLINK
        set(v) { prefs.edit().putStringSet("uplink", v).apply(); changed() }

    /** LT2 command mailbox (kind 4244 -> #libretag); gated by the server's capabilities anyway. */
    var libretagCommands: Boolean
        get() = prefs.getBoolean("libretag_commands", true)
        set(v) { prefs.edit().putBoolean("libretag_commands", v).apply(); changed() }

    /** LoRa preset for the airtime budget when the server does not report one ("7/62.5/5"). */
    var loraPreset: LoRaPreset
        get() = prefs.getString("lora_preset", null)?.let { LoRaPreset.parse(it) } ?: LoRaPreset.SK
        set(v) { prefs.edit().putString("lora_preset", v.toString()).apply(); changed() }

    var downlinkDm: Boolean
        get() = prefs.getBoolean("downlink_dm", false)
        set(v) { prefs.edit().putBoolean("downlink_dm", v).apply(); changed() }

    var devices: List<StoredDevice>
        get() = prefs.getString("devices", null)?.let { json.decodeFromString<List<StoredDevice>>(it) } ?: emptyList()
        set(v) { prefs.edit().putString("devices", json.encodeToString(v)).apply(); changed() }

    fun bridgeConfig() = BridgeConfig(
        uplinkProfiles = uplinkProfiles,
        downlinkDm = downlinkDm,
        libretagCommands = libretagCommands,
        preset = loraPreset,
        region = regions.isNotEmpty(),
        regions = regions,
        dedupMinRelays = relays.size.coerceIn(1, 2),
        appVersion = BuildConfig.VERSION_NAME,
    )

    // MeshCore Local API
    val meshUrl: String
        get() = prefs.getString("mesh_url", null) ?: "ws://127.0.0.1:8787/api/v1"
    var meshIdentity: String?
        get() = prefs.getString("mesh_identity", null)
        set(v) { prefs.edit().putString("mesh_identity", v).apply() }
    var meshRegion: String
        get() = prefs.getString("mesh_region", "") ?: ""
        set(v) {
            if (v == meshRegion) return
            prefs.edit().putString("mesh_region", v).apply(); changed()
        }
    /** The flood scopes this bridge serves, narrow first ("sk-ba, sk"): the region field, comma separated. */
    val regions: List<String>
        get() = meshRegion.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    var meshCursor: String?
        get() = prefs.getString("mesh_cursor", null)
        set(v) { prefs.edit().putString("mesh_cursor", v).apply() }

    companion object {
        /** "libretag.public" (variant A, 135-byte frames) is gone; LT2 reports are "libretag". */
        private fun migrateProfiles(p: Set<String>): Set<String> =
            if ("libretag.public" in p) p - "libretag.public" + "libretag" else p

        /** The owner's relay and primal; nos.lol is gone (unreachable here, R23). */
        val DEFAULT_RELAYS = listOf("wss://nostr.cypherpunk.today", "wss://relay.primal.net")
        /** A saved list equal to the old defaults is read as the new defaults. */
        private val OLD_DEFAULT_RELAYS = listOf("wss://nostr.cypherpunk.today", "wss://nos.lol", "wss://relay.primal.net")
    }
}

fun shortKey(pk: ByteArray): String = Bech32.npub(pk).let { it.take(12) + "…" + it.takeLast(6) }
fun ByteArray.npub() = Bech32.npub(this)

/**
 * The bridge's stable Nostr key (PROTOCOL.md 4.1): made once and kept in [file] (app-private storage,
 * no backup), as hex. It signs claims, done claims, receipts and announcements; reports keep a fresh
 * key per event. An unreadable or invalid file is replaced by a new key.
 */
fun loadOrCreateBridgeKey(file: File, newKey: () -> ByteArray = { Bridge.randomKey() }): ByteArray {
    runCatching { file.readText().trim().unhex() }.getOrNull()
        ?.takeIf { it.size == 32 && Secp256k1.secKeyVerify(it) }?.let { return it }
    val k = newKey()
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(k.hex())
    tmp.setReadable(false, false); tmp.setReadable(true, true)
    tmp.setWritable(false, false); tmp.setWritable(true, true)
    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    return k
}
