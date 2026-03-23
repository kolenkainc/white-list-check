package tech.romashov.whitelistcheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IpCheckService : Service() {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MonitoringPrefs(this).monitoringEnabled = false
            monitorJob?.cancel()
            monitorJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))

        if (monitorJob?.isActive == true) {
            return START_STICKY
        }

        monitorJob?.cancel()
        monitorJob = scope.launch {
            val prefs = MonitoringPrefs(this@IpCheckService)
            val intervalMs = prefs.intervalMinutes * 60_000L
            val httpClient = IpEndpointClient(
                connectTimeoutSec = prefs.connectTimeoutSeconds,
                readTimeoutSec = prefs.connectTimeoutSeconds,
            )
            val checker = ReachabilityChecker(prefs.connectTimeoutSeconds * 1000)

            while (isActive && prefs.monitoringEnabled) {
                runSingleCheck(prefs, httpClient, checker)
                delay(intervalMs)
            }
            if (!prefs.monitoringEnabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun runSingleCheck(
        prefs: MonitoringPrefs,
        httpClient: IpEndpointClient,
        checker: ReachabilityChecker,
    ) {
        val ipResult = httpClient.fetchAndParseIp(prefs.endpointUrl)
        ipResult.fold(
            onSuccess = { ip ->
                val ok = checker.isAnyPortOpen(ip)
                val msg = if (ok) {
                    getString(R.string.status_reachable, ip)
                } else {
                    getString(R.string.status_unreachable, ip)
                }
                prefs.recordCheck(ip, ok, msg)
                updateNotification(msg)
            },
            onFailure = { e ->
                val msg = getString(R.string.status_fetch_error, e.message ?: e.javaClass.simpleName)
                prefs.recordCheck(null, null, msg)
                updateNotification(msg)
            },
        )
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, IpCheckService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_network)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        supervisor.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "tech.romashov.whitelistcheck.STOP"
        private const val CHANNEL_ID = "ip_monitor"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val i = Intent(context, IpCheckService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                @Suppress("DEPRECATION")
                context.startService(i)
            }
        }
    }
}
