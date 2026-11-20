package app.libretag

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A small persistent log next to logcat, whose ring buffer on this phone keeps only minutes.
 * Lives in the app's external files dir so it can be pulled after a test drive:
 * `adb pull /sdcard/Android/data/app.libretag/files/libretag.log`. Stays on the phone.
 */
object DiagLog {
    private const val MAX = 2_000_000L
    private var file: File? = null
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun init(ctx: Context) {
        file = ctx.getExternalFilesDir(null)?.let { File(it, "libretag.log") }
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
}
