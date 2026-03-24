package tech.romashov.whitelistcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ScheduledMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            UtcWindowScheduler.ACTION_SCHEDULED_START -> handleStart(context)
            UtcWindowScheduler.ACTION_SCHEDULED_STOP -> handleStop(context)
        }
    }

    private fun handleStart(context: Context) {
        val prefs = MonitoringPrefs(context)
        UtcWindowScheduler.scheduleNextWindow(context)

        if (!prefs.scheduledUtcWindowEnabled) return

        if (prefs.monitoringEnabled && !prefs.monitoringFromSchedule) return

        prefs.monitoringEnabled = true
        prefs.monitoringFromSchedule = true
        IpCheckService.start(context)
    }

    private fun handleStop(context: Context) {
        val prefs = MonitoringPrefs(context)
        UtcWindowScheduler.scheduleNextWindow(context)

        if (!prefs.monitoringFromSchedule || !prefs.monitoringEnabled) {
            return
        }

        prefs.monitoringEnabled = false
        prefs.monitoringFromSchedule = false
        val i = Intent(context, IpCheckService::class.java).setAction(IpCheckService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            @Suppress("DEPRECATION")
            context.startService(i)
        }
    }
}
