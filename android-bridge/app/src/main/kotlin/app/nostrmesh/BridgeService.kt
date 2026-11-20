package app.nostrmesh

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.nostrmesh.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the process (MeshCore Open socket, relay sockets) alive while the bridge is on. */
class BridgeService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, notification("Spúšťam…"), type)
        val app = BridgeApp.instance
        lifecycleScope.launch {
            combine(app.mesh.state, app.relays.connected, app.bridge.stats) { m, r, s ->
                "${app.meshText(m)} · relays ${r.first}/${r.second} · ↑${s.published} ↓${s.toRadio}"
            }.collect { text ->
                getSystemService(android.app.NotificationManager::class.java).notify(1, notification(text))
            }
        }
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, BridgeApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.bridge_running))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
