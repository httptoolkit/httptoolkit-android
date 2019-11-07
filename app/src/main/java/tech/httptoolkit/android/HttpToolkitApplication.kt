package tech.httptoolkit.android

import android.app.Application
import io.sentry.Sentry
import io.sentry.android.AndroidSentryClientFactory

@Suppress("unused")
class HttpToolkitApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.SENTRY_DSN.isNotEmpty()) {
            Sentry.init(BuildConfig.SENTRY_DSN, AndroidSentryClientFactory(this))
        }
    }

}