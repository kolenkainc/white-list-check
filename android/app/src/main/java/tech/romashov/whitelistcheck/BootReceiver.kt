package tech.romashov.whitelistcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        UtcWindowScheduler.scheduleNextWindow(context)
        val prefs = MonitoringPrefs(context)
        val now = Instant.now()

        if (prefs.monitoringFromSchedule && !UtcWindowScheduler.isInsideUtcWindow(now)) {
            prefs.monitoringEnabled = false
            prefs.monitoringFromSchedule = false
        }

        if (!prefs.monitoringEnabled) {
            if (prefs.scheduledUtcWindowEnabled && UtcWindowScheduler.isInsideUtcWindow(now)) {
                prefs.monitoringEnabled = true
                prefs.monitoringFromSchedule = true
                startService(context)
            }
            return
        }

        if (!prefs.monitoringFromSchedule) {
            startService(context)
            return
        }

        if (UtcWindowScheduler.isInsideUtcWindow(now)) {
            startService(context)
        } else {
            prefs.monitoringEnabled = false
            prefs.monitoringFromSchedule = false
        }
    }

    private fun startService(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, IpCheckService::class.java))
        } else {
            @Suppress("DEPRECATION")
            context.startService(Intent(context, IpCheckService::class.java))
        }
    }
}
