package tech.romashov.whitelistcheck

import android.content.Context

class MonitoringPrefs(context: Context) {
    private val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var monitoringEnabled: Boolean
        get() = p.getBoolean(KEY_ENABLED, false)
        set(value) = p.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Автозапуск мониторинга ежедневно 00:00–01:00 UTC (будильники). */
    var scheduledUtcWindowEnabled: Boolean
        get() = p.getBoolean(KEY_SCHEDULED_UTC, true)
        set(value) = p.edit().putBoolean(KEY_SCHEDULED_UTC, value).apply()

    /** Текущая сессия запущена расписанием (не ручным «Старт»). */
    var monitoringFromSchedule: Boolean
        get() = p.getBoolean(KEY_FROM_SCHEDULE, false)
        set(value) = p.edit().putBoolean(KEY_FROM_SCHEDULE, value).apply()

    var endpointUrl: String
        get() = p.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = p.edit().putString(KEY_URL, value).apply()

    var intervalMinutes: Int
        get() = p.getInt(KEY_INTERVAL, 15).coerceIn(1, 240)
        set(value) = p.edit().putInt(KEY_INTERVAL, value.coerceIn(1, 240)).apply()

    var connectTimeoutSeconds: Int
        get() = p.getInt(KEY_TIMEOUT, 5).coerceIn(1, 60)
        set(value) = p.edit().putInt(KEY_TIMEOUT, value.coerceIn(1, 60)).apply()

    var lastTargetIp: String?
        get() = p.getString(KEY_LAST_IP, null)
        private set(value) = p.edit().putString(KEY_LAST_IP, value).apply()

    var lastReachable: Boolean?
        get() = when {
            !p.contains(KEY_LAST_OK) -> null
            else -> p.getBoolean(KEY_LAST_OK, false)
        }
        private set(value) {
            if (value == null) {
                p.edit().remove(KEY_LAST_OK).apply()
            } else {
                p.edit().putBoolean(KEY_LAST_OK, value).apply()
            }
        }

    var lastMessage: String?
        get() = p.getString(KEY_LAST_MSG, null)
        private set(value) = p.edit().putString(KEY_LAST_MSG, value).apply()

    var lastCheckTimeMillis: Long
        get() = p.getLong(KEY_LAST_TIME, 0L)
        private set(value) = p.edit().putLong(KEY_LAST_TIME, value).apply()

    fun recordCheck(targetIp: String?, reachable: Boolean?, message: String) {
        lastTargetIp = targetIp
        lastReachable = reachable
        lastMessage = message
        lastCheckTimeMillis = System.currentTimeMillis()
    }

    companion object {
        private const val PREFS = "monitoring"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCHEDULED_UTC = "scheduled_utc_window"
        private const val KEY_FROM_SCHEDULE = "monitoring_from_schedule"
        private const val KEY_URL = "url"
        private const val KEY_INTERVAL = "interval_min"
        private const val KEY_TIMEOUT = "timeout_sec"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_OK = "last_ok"
        private const val KEY_LAST_MSG = "last_msg"
        private const val KEY_LAST_TIME = "last_time"

        /** Полный URL GET «следующий IP», например …/api/v1/next у Cloudflare Worker. */
        const val DEFAULT_URL = "https://white-list-check-api.romashov.tech/api/v1/next"
    }
}
