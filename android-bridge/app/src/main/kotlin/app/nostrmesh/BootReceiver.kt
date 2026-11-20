package app.nostrmesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the bridge after a reboot (or an app update) when the user had it on. Creating the
 * Application is enough: [BridgeApp.onCreate] applies the settings, which starts the foreground
 * service. `specialUse` services may be started from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as BridgeApp
        Log.i(BridgeApp.TAG, "${intent.action}: bridge ${if (app.settings.enabled) "starting" else "off"}")
        if (app.settings.enabled) app.apply()
    }
}
