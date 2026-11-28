package app.libretag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Own-node slot sends (P7, R9): a coroutine delay is not kept while the phone dozes, so an alarm
 * wakes the app 5 s before the earliest pending slot send (a command or a timed ACK through the own
 * node) and pokes the slot loop. Exact when the system allows it, else the inexact Doze alarm.
 */
object SlotAlarm {
    const val AHEAD_MS = 5_000L

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 11, Intent(c, SlotAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context, sendAtMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val at = maxOf(System.currentTimeMillis() + 1_000L, sendAtMs - AHEAD_MS)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
        }.onFailure { DiagLog.w(LibreTagApp.TAG, "slot alarm not set: ${it.message}") }
    }
}

class SlotAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as LibreTagApp).pokeSlots()
    }
}
