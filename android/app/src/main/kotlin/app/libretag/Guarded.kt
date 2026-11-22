package app.libretag

import kotlinx.coroutines.CancellationException

/**
 * One step of a loop that must outlive its failures (the report sync): [block]'s result, or null
 * after [warn] when it threw. Cancellation still ends the loop.
 */
suspend fun <T> guarded(warn: (Throwable) -> Unit, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    warn(e)
    null
}
