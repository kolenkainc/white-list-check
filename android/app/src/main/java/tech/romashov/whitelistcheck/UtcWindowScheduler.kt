package tech.romashov.whitelistcheck

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Ежедневное окно 00:00–01:00 UTC: будильники на старт и конец мониторинга.
 */
object UtcWindowScheduler {

    const val ACTION_SCHEDULED_START = "tech.romashov.whitelistcheck.SCHEDULED_START"
    const val ACTION_SCHEDULED_STOP = "tech.romashov.whitelistcheck.SCHEDULED_STOP"

    private const val RC_START = 7101
    private const val RC_STOP = 7102

    /** Текущий момент внутри [сегодня 00:00 UTC, сегодня 01:00 UTC). */
    fun isInsideUtcWindow(now: Instant = Instant.now()): Boolean {
        val z = now.atZone(ZoneOffset.UTC)
        val dayStart = z.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        val dayEnd = dayStart.plus(1, ChronoUnit.HOURS)
        return !now.isBefore(dayStart) && now.isBefore(dayEnd)
    }

    /** Следующая полночь UTC (начало окна): если [now] уже после или равна сегодняшней полуночи — завтра 00:00 UTC. */
    fun nextUtcMidnightAfter(now: Instant): Instant {
        val z = now.atZone(ZoneOffset.UTC)
        var m = z.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        if (!now.isBefore(m)) {
            m = z.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
        return m
    }

    /** Конец сегодняшнего окна (01:00 UTC), если ещё не наступил; иначе null. */
    private fun upcomingStopInCurrentWindow(now: Instant): Instant? {
        val z = now.atZone(ZoneOffset.UTC)
        val dayStart = z.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
        val dayEnd = dayStart.plus(1, ChronoUnit.HOURS)
        return if (now.isBefore(dayEnd) && !now.isBefore(dayStart)) dayEnd else null
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, RC_START, ACTION_SCHEDULED_START))
        am.cancel(pending(context, RC_STOP, ACTION_SCHEDULED_STOP))
    }

    /** Android 12+ (особенно 13+): точные будильники только с разрешением / ручным доступом. */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * Ставит будильники на ближайший старт (00:00 UTC) и стоп (01:00 UTC того же дня).
     * Вызывать после загрузки, смены настройки и из приёмника.
     */
    fun scheduleNextWindow(context: Context) {
        val prefs = MonitoringPrefs(context)
        cancelAll(context)
        if (!prefs.scheduledUtcWindowEnabled) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = Instant.now()

        upcomingStopInCurrentWindow(now)?.let { stopAt ->
            if (stopAt.isAfter(now)) {
                setExactOrWindowAlarm(am, context, stopAt.toEpochMilli(), RC_STOP, ACTION_SCHEDULED_STOP)
            }
        }

        val startAt = nextUtcMidnightAfter(now)
        if (startAt.isAfter(now)) {
            setExactOrWindowAlarm(am, context, startAt.toEpochMilli(), RC_START, ACTION_SCHEDULED_START)
        }
    }

    /**
     * [AlarmManager.setAlarmClock] — точное время и иконка «будильника» в статус-баре.
     * Без [android.Manifest.permission.SCHEDULE_EXACT_ALARM] (частый случай на Android 13+) — [AlarmManager.setWindow].
     */
    @SuppressLint("ScheduleExactAlarm")
    private fun setExactOrWindowAlarm(
        am: AlarmManager,
        context: Context,
        triggerMillis: Long,
        requestCode: Int,
        action: String,
    ) {
        val operation = pending(context, requestCode, action)
        val useExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (useExact) {
            try {
                val show = PendingIntent.getActivity(
                    context,
                    requestCode + 100,
                    Intent(context, MainActivity::class.java),
                    pendingFlags(),
                )
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerMillis, show),
                    operation,
                )
                return
            } catch (_: SecurityException) {
                // редкие OEM / политики
            }
        }
        val windowMs = 10 * 60_000L
        am.setWindow(AlarmManager.RTC_WAKEUP, triggerMillis, windowMs, operation)
    }

    private fun pending(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, ScheduledMonitorReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            pendingFlags(),
        )
    }

    private fun pendingFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f = f or PendingIntent.FLAG_IMMUTABLE
        }
        return f
    }
}
