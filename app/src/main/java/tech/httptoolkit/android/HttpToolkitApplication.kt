package tech.httptoolkit.android

import android.app.Application
import com.google.android.gms.analytics.GoogleAnalytics
import com.google.android.gms.analytics.HitBuilders
import com.google.android.gms.analytics.Tracker
import io.sentry.Sentry
import io.sentry.android.AndroidSentryClientFactory

@Suppress("unused")
class HttpToolkitApplication : Application() {

    private var analytics: GoogleAnalytics? = null
    private var ga: Tracker? = null

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.SENTRY_DSN != null) {
            Sentry.init(BuildConfig.SENTRY_DSN, AndroidSentryClientFactory(this))
        }

        if (BuildConfig.GA_ID != null) {
            analytics = GoogleAnalytics.getInstance(this)
            ga = analytics!!.newTracker(BuildConfig.GA_ID)
            resumeEvents() // Resume events on app startup, in case they were paused and we crashed
        }
    }

    fun trackScreen(name: String) {
        ga?.setScreenName(name)
        ga?.send(HitBuilders.EventBuilder().build())
    }

    fun clearScreen() {
        ga?.setScreenName(null)
    }

    fun trackEvent(category: String, action: String) {
        ga?.send(
            HitBuilders.EventBuilder()
            .setCategory(category)
            .setAction(action)
            .build()
        )
    }

    /**
     * Unclear if the below two actually work - analytics on devices with google play is
     * managed by the device itself, not the app. Worth a try though.
     */

    fun pauseEvents() {
        analytics?.setLocalDispatchPeriod(0) // Don't dispatch events for now
    }

    fun resumeEvents() {
        analytics?.setLocalDispatchPeriod(120) // Set dispatching back to Android default
    }

}