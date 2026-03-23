package tech.romashov.whitelistcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = MonitoringPrefs(context)
        if (!prefs.monitoringEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, IpCheckService::class.java))
        } else {
            @Suppress("DEPRECATION")
            context.startService(Intent(context, IpCheckService::class.java))
        }
    }
}
