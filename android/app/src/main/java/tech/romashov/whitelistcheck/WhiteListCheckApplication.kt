package tech.romashov.whitelistcheck

import android.app.Application
import io.sentry.android.core.SentryAndroid

class WhiteListCheckApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.release = BuildConfig.SENTRY_RELEASE
            options.environment = if (BuildConfig.DEBUG) "debug" else "production"
            options.isDebug = BuildConfig.DEBUG
        }
    }
}
