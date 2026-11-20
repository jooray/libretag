package app.libretag

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import app.libretag.ui.MainActivity

/** Keeps the process (and the MeshCore Open connection) alive so reports and alarms arrive in the background. */
class TrackerService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, LibreTagApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
        LibreTagApp.instance.mesh.start()
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackerService::class.java))
        }
    }
}
