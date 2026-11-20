package app.libretag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a phone restart the app would stay asleep until opened, and reports would be missed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TrackerService.start(context)
        }
    }
}
