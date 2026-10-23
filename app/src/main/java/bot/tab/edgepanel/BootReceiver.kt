package bot.tab.edgepanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val shouldStart = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> Prefs.startOnBoot(context) && Prefs.running(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> Prefs.running(context)
            else -> false
        }
        if (shouldStart && Settings.canDrawOverlays(context)) {
            EdgePanelService.start(context)
        }
    }
}
