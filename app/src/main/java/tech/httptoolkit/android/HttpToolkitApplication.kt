package tech.httptoolkit.android

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.beust.klaxon.Klaxon
import com.google.android.gms.analytics.GoogleAnalytics
import com.google.android.gms.analytics.HitBuilders
import com.google.android.gms.analytics.Tracker
import io.sentry.Sentry
import io.sentry.android.AndroidSentryClientFactory

class HttpToolkitApplication : Application() {

    private val TAG = HttpToolkitApplication::class.simpleName

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

        Log.i(TAG, "App created")
    }

    var lastProxy: ProxyConfig?
        get() {
            Log.i(TAG, "Loading last proxy config")
            val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)
            val serialized = prefs.getString("last-proxy-config", null)

            return when {
                serialized != null -> {
                    Klaxon().converter(CertificateConverter).parse<ProxyConfig>(serialized)
                }
                else -> null
            }
        }
        set(proxyConfig) {
            Log.i(TAG, if (proxyConfig == null) "Clearing proxy config" else "Saving proxy config")
            val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)

            if (proxyConfig != null) {
                val serialized = Klaxon().converter(CertificateConverter).toJsonString(proxyConfig)
                prefs.edit().putString("last-proxy-config", serialized).apply()
            } else {
                prefs.edit().remove("last-proxy-config")
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