package app.nostrmesh.data

import android.util.Log
import app.nostrmesh.bridge.BridgeState
import kotlinx.serialization.json.Json
import java.io.File

/** Ledger, mailbox and airtime log in one JSON file in app storage (written atomically). */
class StateStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun load(): BridgeState? = try {
        if (file.exists()) json.decodeFromString(BridgeState.serializer(), file.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "state unreadable, starting empty: ${e.message}")
        null
    }

    @Synchronized
    fun save(state: BridgeState) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(BridgeState.serializer(), state))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "state not saved: ${e.message}")
        }
    }

    companion object {
        const val TAG = "StateStore"
    }
}
