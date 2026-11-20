package app.libretag.usb.dfu

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.libretag.BuildConfig
import app.libretag.usb.Via
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.usb.UsbFind
import app.libretag.usb.UsbSerial
import app.libretag.usb.UsbState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The firmware package bundled in the APK, pinned by its SHA-256 at build time (no downloads). */
object BundledFirmware {
    const val ASSET = "firmware/tracker-dfu.zip"

    val available: Boolean get() = BuildConfig.FIRMWARE_DFU_SHA256.isNotEmpty()
    val version: String? get() = BuildConfig.FIRMWARE_DFU_VERSION.ifEmpty { null }

    fun load(ctx: Context): DfuPackage {
        if (!available) throw DfuPackageException("this build carries no firmware")
        val zip = ctx.assets.open(ASSET).use { it.readBytes() }
        if (!DfuPackage.sha256Hex(zip).equals(BuildConfig.FIRMWARE_DFU_SHA256, ignoreCase = true))
            throw DfuPackageException("bundled firmware does not match its pinned SHA-256")
        val p = DfuPackage.parse(zip)
        if (p.version != BuildConfig.FIRMWARE_DFU_VERSION) throw DfuPackageException("bundled firmware version mismatch")
        return p
    }
}

enum class DfuFail {
    /** no package in this build, or it failed its checks */
    NO_FIRMWARE,
    /** no tag of ours on the cable */
    NOT_CONNECTED,
    /** the console would not unlock or refused `dfu` */
    LOCKED,
    /** the tag did not come back as its bootloader */
    NO_BOOTLOADER,
    /** the USB permission was refused or not answered */
    PERMISSION,
    /** the transfer stopped; the tag stays in its bootloader */
    TRANSFER,
    /** after the transfer the firmware did not come back on the cable */
    NO_RETURN,
    /** it came back, but with another version than the one sent */
    WRONG_VERSION,
}

sealed interface DfuStep {
    data object Idle : DfuStep
    data object Rebooting : DfuStep
    data object WaitingBootloader : DfuStep
    data class Permission(val bootloader: Boolean) : DfuStep
    data object Erasing : DfuStep
    data class Sending(val sent: Int, val total: Int) : DfuStep
    data object Finishing : DfuStep
    data object Restarting : DfuStep
    data object Reading : DfuStep
    /** [paired]: the tag unlocked with our key again (its keys survived); null when it was not paired */
    data class Done(val version: String, val paired: Boolean?) : DfuStep
    data class Failed(val why: DfuFail, val detail: String? = null) : DfuStep
}

/**
 * Firmware update over the USB cable: unlock → `dfu serial` → the bootloader enumerates (USB
 * permission again) → serial DFU ([SerialDfu]) → the tag restarts → read `version`, unlock.
 * Runs in the app's scope so that it survives the screen; one at a time.
 */
object FirmwareUpdate {
    private const val TAG = "FirmwareUpdate"
    private const val ACTION_PERMISSION = "app.libretag.DFU_USB_PERMISSION"
    private const val BOOTLOADER_WAIT_MS = 20_000L
    private const val RETURN_WAIT_MS = 45_000L
    private const val PERMISSION_WAIT_MS = 120_000L

    private val _state = MutableStateFlow<DfuStep>(DfuStep.Idle)
    val state: StateFlow<DfuStep> = _state
    private var job: Job? = null

    /** While true, the screens' USB helpers leave the cable alone. */
    @Volatile var active = false
        private set

    private val app get() = LibreTagApp.instance

    fun reset() { if (!active) _state.value = DfuStep.Idle }

    /** A tag in its bootloader on the cable now (double-tap reset, or an update that stopped). */
    fun bootloaderOnCable(ctx: Context): UsbDevice? =
        ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { isBootloader(it) }

    fun isBootloader(d: UsbDevice): Boolean =
        DfuDevices.isBootloader(d.vendorId, d.productId, runCatching { d.productName }.getOrNull())

    /** [fromBootloader]: the tag is already in its bootloader, skip the console part. */
    fun start(ctx: Context, fromBootloader: Boolean) {
        if (active) return
        active = true
        val c = ctx.applicationContext
        job = app.scope.launch {
            try {
                run(c, fromBootloader)
            } catch (e: CancellationException) {
                _state.value = DfuStep.Idle
            } catch (e: Exception) {
                DiagLog.w(TAG, "update: ${e.message}")
                _state.value = DfuStep.Failed(DfuFail.TRANSFER, e.message)
            } finally {
                active = false
            }
        }
    }

    /** Only before the transfer starts: the screen offers it while waiting. */
    fun cancel() { job?.cancel() }

    private class Stop(val step: DfuStep.Failed) : Exception(step.detail)

    private fun fail(why: DfuFail, detail: String? = null): Nothing = throw Stop(DfuStep.Failed(why, detail))

    private suspend fun run(ctx: Context, fromBootloader: Boolean) {
        try {
            flow(ctx, fromBootloader)
        } catch (s: Stop) {
            DiagLog.w(TAG, "update failed: ${s.step.why} ${s.step.detail ?: ""}")
            _state.value = s.step
        }
    }

    private suspend fun flow(ctx: Context, fromBootloader: Boolean) {
        val usb = ctx.getSystemService(UsbManager::class.java) ?: fail(DfuFail.NOT_CONNECTED)
        val pkg = try { withContext(Dispatchers.IO) { BundledFirmware.load(ctx) } } catch (e: Exception) { fail(DfuFail.NO_FIRMWARE, e.message) }
        DiagLog.i(TAG, "update to ${pkg.version} (${pkg.image.size} B, sha256 ${pkg.imageSha256.take(16)}…), from bootloader: $fromBootloader")
        val tagId = app.usb.connected?.tagId
        // 0.12: the diagnosis links a silent tag to an update that did not finish (UPDATE_UNFINISHED)
        val started = System.currentTimeMillis()
        (tagId ?: app.cableTagId())?.let { id -> app.store.updateTracker(id) { it.copy(dfuStartedAt = started, dfuNotified = false) } }

        if (!fromBootloader) {
            val tag = app.usb.connected?.takeIf { it.via == Via.USB } ?: fail(DfuFail.NOT_CONNECTED) // never over Bluetooth
            if (tag.find != UsbFind.MINE && tag.find != UsbFind.NEW) fail(DfuFail.LOCKED, tag.find.name)
            _state.value = DfuStep.Rebooting
            val keys = tag.tagId?.let { app.store.get(it) }?.let { app.keysFor(it) }
            val reply = app.usb.session { c ->
                // the unlock lasts a while only: unlock again right before the command
                if (c.status().locked && (keys == null || !c.unlock(keys.usbKey))) return@session listOf("ERR locked")
                c.port().write("dfu serial\r".toByteArray())
                var r = c.collect(2500, 300) { it.startsWith("dfu:") || it.startsWith("ERR") }
                if (r.any { it.startsWith("ERR unknown") || it.startsWith("usage") }) {
                    // older firmware: plain `dfu` (UF2 mode, which takes serial DFU too)
                    c.port().write("dfu\r".toByteArray())
                    r = c.collect(2500, 300) { it.startsWith("dfu:") || it.startsWith("ERR") }
                }
                r
            } ?: emptyList()
            DiagLog.i(TAG, "dfu: ${reply.filter { it.startsWith("dfu") || it.startsWith("ERR") }}")
            reply.firstOrNull { it.startsWith("ERR") }?.let { fail(DfuFail.LOCKED, it) }
            app.usb.disconnected()
        }

        _state.value = DfuStep.WaitingBootloader
        val boot = waitFor(usb, BOOTLOADER_WAIT_MS) { isBootloader(it) } ?: fail(DfuFail.NO_BOOTLOADER)
        DiagLog.i(TAG, "bootloader %04x:%04x %s".format(boot.vendorId, boot.productId, runCatching { boot.productName }.getOrNull() ?: ""))
        _state.value = DfuStep.Permission(bootloader = true)
        if (!permission(ctx, usb, boot)) fail(DfuFail.PERMISSION)

        _state.value = DfuStep.Erasing
        withContext(Dispatchers.IO) {
            val port = try { UsbSerial.open(usb, boot) } catch (e: Exception) { fail(DfuFail.TRANSFER, e.message) }
            try {
                val link = object : DfuLink {
                    override fun write(bytes: ByteArray) = port.write(bytes)
                    override fun read(timeoutMs: Int) = port.read(timeoutMs)
                }
                val dfu = SerialDfu(link)
                try {
                    dfu.run(pkg.image, pkg.initPacket,
                        onPhase = { p ->
                            when (p) {
                                DfuPhase.ERASING, DfuPhase.START, DfuPhase.INIT -> _state.value = DfuStep.Erasing
                                DfuPhase.FINISHING -> _state.value = DfuStep.Finishing
                                else -> {}
                            }
                        },
                        onProgress = { sent, total ->
                            // one update per percent is plenty for the screen
                            val cur = _state.value
                            if (cur !is DfuStep.Sending || 100L * cur.sent / total != 100L * sent / total) _state.value = DfuStep.Sending(sent, total)
                        })
                } catch (e: DfuTransferException) {
                    fail(DfuFail.TRANSFER, "${e.phase}: ${e.message}")
                }
                DiagLog.i(TAG, "transfer done, ${dfu.resends} resends")
            } finally {
                runCatching { port.close() }
            }
        }

        _state.value = DfuStep.Restarting
        val back = waitFor(usb, RETURN_WAIT_MS) { DfuDevices.isTracker(it.vendorId, it.productId) } ?: fail(DfuFail.NO_RETURN)
        _state.value = DfuStep.Permission(bootloader = false)
        if (!permission(ctx, usb, back)) fail(DfuFail.PERMISSION, "tracker")
        _state.value = DfuStep.Reading
        val st = app.usb.connect(usb, back)
        val tag = (st as? UsbState.Connected)?.tag ?: fail(DfuFail.NO_RETURN, (st as? UsbState.Failed)?.message)
        val v = tag.version ?: fail(DfuFail.WRONG_VERSION, "no version")
        if (v != pkg.version) fail(DfuFail.WRONG_VERSION, v)
        val paired = when {
            tagId == null && tag.tagId == null -> null
            else -> tag.find == UsbFind.MINE
        }
        DiagLog.i(TAG, "tag back on $v, find ${tag.find}")
        (tag.tagId ?: tagId)?.let { id -> app.store.updateTracker(id) { it.copy(dfuStartedAt = null, dfuNotified = false) } }
        _state.value = DfuStep.Done(v, paired)
    }

    private suspend fun waitFor(usb: UsbManager, ms: Long, match: (UsbDevice) -> Boolean): UsbDevice? = withTimeoutOrNull(ms) {
        var d: UsbDevice? = null
        while (d == null) {
            d = usb.deviceList.values.firstOrNull(match)
            if (d == null) delay(250)
        }
        d
    }

    private suspend fun permission(ctx: Context, usb: UsbManager, d: UsbDevice): Boolean {
        if (usb.hasPermission(d)) return true
        return withTimeoutOrNull(PERMISSION_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val r = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                ContextCompat.registerReceiver(ctx, r, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(r) } }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 7, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags))
            }
        } ?: false
    }
}
