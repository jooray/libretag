package app.libretag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.libretag.data.Status
import app.libretag.data.Tracker
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Backup for the "tag is silent" alarm (0.8.0): the app's own 5-minute loop stops when the system
 * kills the process, so AlarmManager wakes the app at the next moment a tag would become silent
 * (or within an hour) and runs the same check. Inexact alarms that may fire in Doze
 * (setAndAllowWhileIdle): no exact-alarm permission needed, a few minutes late at worst.
 */
object SilenceAlarm {
    const val MAX_GAP_MS = 60 * 60_000L
    const val MIN_GAP_MS = 60_000L

    /** When to look next: the earliest moment a tag with the silent alert on becomes silent, at most an hour away. */
    fun nextAt(trackers: List<Tracker>, now: Long): Long {
        val due = trackers.filter { it.alerts.silent && !it.silentAlerted }
            .mapNotNull { Status.silentAt(it, now)?.plus(30_000L) }
            .filter { it > now }.minOrNull()
        return (due ?: (now + MAX_GAP_MS)).coerceIn(now + MIN_GAP_MS, now + MAX_GAP_MS)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 7, Intent(c, SilenceAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, trackers: List<Tracker>) {
        val now = System.currentTimeMillis()
        val at = nextAt(trackers, now)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c)) }
            .onSuccess { LibreTagApp.instance.prefs.silenceAlarmAt = at }
            .onFailure { DiagLog.w(LibreTagApp.TAG, "silence alarm not set: ${it.message}") }
    }
}

class SilenceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LibreTagApp
        val done = goAsync()
        app.scope.launch {
            try {
                // a process the system had killed missed the reports since: fetch them before judging
                withTimeoutOrNull(7_000) { app.quickSync() }
                app.checkSilence()
                DiagLog.i(LibreTagApp.TAG, "silence alarm: checked")
            } finally {
                SilenceAlarm.schedule(app, app.store.trackers.value)
                done.finish()
            }
        }
    }
}
