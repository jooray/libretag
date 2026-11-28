package app.libretag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.libretag.data.Status
import app.libretag.data.Tracker
import kotlinx.coroutines.launch

/**
 * Field test 2026-11-27: while the phone dozed, a relay socket died without a close and a report the
 * bridge published reached the app 12 minutes late, so its ACK never went out and the tag fell back
 * to a flood. An alarm wakes the app [AHEAD_MS] before the next report any tag is expected to send,
 * checks that every relay still answers ([app.libretag.nostr.RelayPool.freshen]) and keeps the CPU
 * awake through the report's ACK window. Exact when the system allows it, else the inexact Doze alarm.
 */
object ReportAlarm {
    const val AHEAD_MS = 20_000L
    /** awake from the alarm until the report and its ACK are through: next_in is the start of the
     *  tag's wake-up, the report follows after its GPS search (up to the 90 s fix timeout) */
    const val AWAKE_MS = 150_000L

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 13, Intent(c, ReportAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** the earliest expected report of any tag, still ahead */
    fun nextAt(trackers: List<Tracker>, now: Long): Long? =
        trackers.mapNotNull { Status.nextAt(it, now) }.map { it - AHEAD_MS }.filter { it > now + 1_000L }.minOrNull()

    fun schedule(c: Context, trackers: List<Tracker>) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val at = nextAt(trackers, System.currentTimeMillis())
        if (at == null) { am.cancel(pending(c)); return }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        }.onFailure { DiagLog.w(LibreTagApp.TAG, "report alarm not set: ${it.message}") }
    }
}

class ReportAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LibreTagApp
        val wl = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "libretag:report")
        runCatching { wl?.acquire(ReportAlarm.AWAKE_MS) }
        val done = goAsync()
        app.scope.launch {
            try {
                app.relays.freshen()
            } finally {
                ReportAlarm.schedule(app, app.store.trackers.value)
                done.finish()
            }
        }
    }
}
