package tech.httptoolkit.android

import android.app.Application
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.beust.klaxon.Klaxon
import com.google.android.gms.analytics.GoogleAnalytics
import com.google.android.gms.analytics.HitBuilders
import com.google.android.gms.analytics.Tracker
import io.sentry.Sentry
import io.sentry.android.AndroidSentryClientFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

    /**
     * Grab any first run params, drop them for future usage, and return them.
     * This will return first-run params at most once (per install).
      */
    suspend fun popFirstRunParams(): String? {
        val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)

        val isFirstRun = prefs.getBoolean("is-first-run", true)
        prefs.edit().putBoolean("is-first-run", false).apply()

        val installTime = packageManager.getPackageInfo(packageName, 0).firstInstallTime
        val now = System.currentTimeMillis()
        val timeSinceInstall = now - installTime

        // 15 minutes after install, initial run params expire entirely
        if (!isFirstRun || timeSinceInstall > 1000 * 60 * 15) {
            Log.i(TAG, "No first-run params. 1st run $isFirstRun, $timeSinceInstall since install")
            return null
        }

        // Get & return the actual referrer and return it
        Log.i(TAG, "Getting first run referrer...")
        return suspendCoroutine { cont ->
            var wasResumed = AtomicBoolean()
            val resume = { value: String? ->
                if (wasResumed.getAndSet(true)) {
                    cont.resume(value)
                }
            }
            val referrerClient = InstallReferrerClient.newBuilder(this).build()
            referrerClient.startConnection(object : InstallReferrerStateListener {

                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerResponse.OK -> {
                            val referrer = referrerClient.installReferrer.installReferrer
                            Log.i(TAG, "Returning first run referrer: $referrer")
                            resume(referrer)
                        }
                        else -> {
                            Log.w(TAG, "Couldn't get install referrer, skipping: $responseCode")
                            resume(null)
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Log.w(TAG, "Couldn't get install referrer due to disconnection")
                    resume(null)
                }
            })
        }

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
                prefs.edit().remove("last-proxy-config").apply()
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