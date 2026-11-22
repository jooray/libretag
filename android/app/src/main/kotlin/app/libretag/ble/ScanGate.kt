package app.libretag.ble

/**
 * Who wants the foreground console scan, and whether it is registered with the Bluetooth stack.
 * Screens call it from the main thread, the Bluetooth reconnect and the wake advert from
 * Dispatchers.Default, so every step holds one lock. [radio] does the platform calls.
 */
class ScanGate(private val radio: Radio) {
    interface Radio {
        /** start the scan; false when it could not be started (nothing is registered then) */
        fun start(): Boolean
        /** stop the scan; safe when none is registered */
        fun stop()
    }

    private var users = 0
    var running = false
        private set

    @Synchronized fun acquire() { users++; start() }

    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** (Re)start when someone wants it and none runs. */
    @Synchronized fun start() {
        if (users == 0 || running) return
        running = radio.start()
    }

    /** Always tells the stack to stop: a scan registered before a failure report must not leak. */
    @Synchronized fun stop() {
        running = false
        radio.stop()
    }

    /**
     * The stack's onScanFailed. SCAN_FAILED_ALREADY_STARTED (1) means our callback is still
     * registered, so the scan runs; any other error means none is.
     */
    @Synchronized fun failed(errorCode: Int) {
        if (errorCode != ALREADY_STARTED) running = false
    }

    @Synchronized fun users(): Int = users

    companion object {
        /** ScanCallback.SCAN_FAILED_ALREADY_STARTED */
        const val ALREADY_STARTED = 1
    }
}
