package app.nostrmesh

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * R24: a timed mailbox entry must leave at its `at` even when the phone dozes. [schedule] sets an exact
 * alarm ([AlarmManager.setExactAndAllowWhileIdle]) [LEAD_MS] before the next due entry; the receiver takes a
 * partial wake lock and hands it to [BridgeApp.onTimedAlarm], which holds it until the sends returned.
 * Without the exact-alarm permission (Android 12+ asks the user) the alarm is inexact and set earlier.
 */
class TimedAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nostrmesh:timed").apply {
            setReferenceCounted(false)
            acquire(MAX_HOLD_MS)
        }
        BridgeApp.instance.onTimedAlarm(wl)
    }

    companion object {
        /** The wake lock is held from this long before `at`. */
        const val LEAD_MS = 10_000L
        /** Inexact alarms (no permission) come up to this much earlier. */
        const val INEXACT_LEAD_MS = 60_000L
        /** A wake lock is never held longer than this per alarm. */
        const val MAX_HOLD_MS = 120_000L

        fun schedule(context: Context, atMs: Long?) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(context, 0, Intent(context, TimedAlarm::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (atMs == null) { am.cancel(pi); return }
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            val trigger = maxOf(System.currentTimeMillis() + 1_000, atMs - if (exact) LEAD_MS else INEXACT_LEAD_MS)
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }.onFailure { Log.w(BridgeApp.TAG, "timed alarm: ${it.message}") }
            if (!exact) FieldLog.ev("alarm", "exact" to false, "at" to atMs / 1000)
        }
    }
}
