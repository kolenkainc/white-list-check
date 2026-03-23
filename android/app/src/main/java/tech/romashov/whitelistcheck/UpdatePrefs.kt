package tech.romashov.whitelistcheck

import android.content.Context

class UpdatePrefs(context: Context) {
    private val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var lastGithubUpdateCheckMillis: Long
        get() = p.getLong(KEY_LAST_CHECK, 0L)
        private set(value) = p.edit().putLong(KEY_LAST_CHECK, value).apply()

    fun markUpdateCheckDone() {
        lastGithubUpdateCheckMillis = System.currentTimeMillis()
    }

    fun shouldRunAutoCheck(intervalMs: Long = AUTO_CHECK_INTERVAL_MS): Boolean {
        if (lastGithubUpdateCheckMillis <= 0L) return true
        return System.currentTimeMillis() - lastGithubUpdateCheckMillis >= intervalMs
    }

    companion object {
        private const val PREFS = "app_updates"
        private const val KEY_LAST_CHECK = "last_github_check_ms"

        const val AUTO_CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
