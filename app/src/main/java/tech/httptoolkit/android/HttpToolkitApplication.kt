package tech.httptoolkit.android

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.swiftzer.semver.SemVer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val VPN_START_TIME_PREF = "vpn-start-time"
private const val LAST_UPDATE_CHECK_TIME_PREF = "update-check-time"
private const val APP_CRASHED_PREF = "app-crashed"
private const val FIRST_RUN_PREF = "is-first-run"

private val isProbablyEmulator =
        Build.FINGERPRINT.startsWith("generic")
        || Build.FINGERPRINT.startsWith("unknown")
        || Build.MODEL.contains("google_sdk")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for x86")
        || Build.BOARD == "QC_Reference_Phone"
        || Build.MANUFACTURER.contains("Genymotion")
        || Build.HOST.startsWith("Build")
        || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
        || Build.PRODUCT == "google_sdk"

private val bootTime = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())

class HttpToolkitApplication : Application() {

    private var analytics: GoogleAnalytics? = null
    private var ga: Tracker? = null

    private lateinit var prefs: SharedPreferences
    private var vpnWasKilled: Boolean = false

    var vpnShouldBeRunning: Boolean
        get() {
            return prefs.getLong(VPN_START_TIME_PREF, -1) > bootTime
        }
        set(value) {
            if (value) {
                prefs.edit().putLong(VPN_START_TIME_PREF, System.currentTimeMillis()).apply()
            } else {
                prefs.edit().putLong(VPN_START_TIME_PREF, -1).apply()
            }
        }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)

        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            prefs.edit().putBoolean(APP_CRASHED_PREF, true).apply()
        }

        if (BuildConfig.SENTRY_DSN != null) {
            Sentry.init(BuildConfig.SENTRY_DSN, AndroidSentryClientFactory(this))
        }

        if (BuildConfig.GA_ID != null) {
            analytics = GoogleAnalytics.getInstance(this)
            ga = analytics!!.newTracker(BuildConfig.GA_ID)
            ga!!.setAnonymizeIp(true)
            resumeEvents() // Resume events on app startup, in case they were paused and we crashed
        }

        // Check if we've been recreated unexpectedly, with no crashes in the meantime:
        val appCrashed = prefs.getBoolean(APP_CRASHED_PREF, false)
        prefs.edit().putBoolean(APP_CRASHED_PREF, false).apply()

        vpnWasKilled = vpnShouldBeRunning && !isVpnActive() && !appCrashed && !isProbablyEmulator
        if (vpnWasKilled) {
            Sentry.capture("VPN killed in the background")
            // The UI will show an alert next time the MainActivity is created.
        }

        Log.i(TAG, "App created")
    }

    /**
     * Check whether the VPN was killed as a sleeping background process, and then
     * reset that state so that future checks (until it's next killed) return false
     */
    fun popVpnKilledState(): Boolean {
        return vpnWasKilled
            .also { this.vpnWasKilled = false }
    }

    /**
     * Grab any first run params, drop them for future usage, and return them.
     * This will return first-run params at most once (per install).
      */
    suspend fun popFirstRunParams(): String? {
        val isFirstRun = prefs.getBoolean(FIRST_RUN_PREF, true)
        prefs.edit().putBoolean(FIRST_RUN_PREF, false).apply()

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
            val wasResumed = AtomicBoolean()
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
                    Log.i(TAG, "Found last proxy config: $serialized")
                    Klaxon().converter(CertificateConverter).parse<ProxyConfig>(serialized)
                }
                else -> {
                    Log.i(TAG, "No proxy config found")
                    null
                }
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

    var uninterceptedApps: Set<String>
        get() {
            val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)
            val packagesSet = prefs.getStringSet("unintercepted-packages", null)
            val allPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                .map { pkg -> pkg.packageName }
            return (packagesSet ?: setOf())
                .filter { pkg -> allPackages.contains(pkg) } // Filter, as packages might've been uninstalled
                .toSet()
        }
        set(packageNames) {
            val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)
            prefs.edit().putStringSet("unintercepted-packages", packageNames).apply()
        }

    var interceptedPorts: Set<Int>
        get() {
            val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)
            val portsSet = prefs.getStringSet("intercepted-ports", null)
            return portsSet?.map(String::toInt)?.toSortedSet()
                ?: DEFAULT_PORTS
        }
        set(ports) {
            val prefs = getSharedPreferences("tech.httptoolkit.android", MODE_PRIVATE)
            prefs.edit().putStringSet("intercepted-ports", ports.map(Int::toString).toSet()).apply()
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

    suspend fun isUpdateRequired(): Boolean {
        return withContext(Dispatchers.IO) {
            if (wasInstalledFromStore(this@HttpToolkitApplication)) {
                // We only check for updates for side-loaded/ADB-loaded versions. This is useful
                // because otherwise anything outside the play store gets no updates.
                Log.i(TAG, "Installed from play store, no update prompting required")
                return@withContext false
            }

            val lastUpdateTime = prefs.getLong(LAST_UPDATE_CHECK_TIME_PREF,
                firstInstallTime(this@HttpToolkitApplication)
            )

            if (System.currentTimeMillis() - lastUpdateTime < 1000 * 60) {
                return@withContext false;
            }

            val httpClient = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/repos/httptoolkit/httptoolkit-android/releases/latest")
                .build()

            try {
                val response = httpClient.newCall(request).execute().use { response ->
                    if (response.code != 200) throw RuntimeException("Failed to check for updates")
                    response.body!!.string()
                }

                val release = Klaxon().parse<GithubRelease>(response)!!
                val releaseVersion =
                    tryParseSemver(release.name)
                    ?: tryParseSemver(release.tag_name)
                    ?: throw RuntimeException("Could not parse release version ${release.tag_name}")
                val releaseDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(release.published_at)!!

                val installedVersion = getInstalledVersion(this@HttpToolkitApplication)

                val updateAvailable = releaseVersion > installedVersion
                // We avoid immediately prompting for updates because a) there's a review delay
                // before new updates go live, and b) it's annoying otherwise, if there's a rapid
                // series of releases. Better to start chasing users only after a week stable.
                val updateNotTooRecent = releaseDate.before(daysAgo(7))

                Log.i(TAG,
                    if (updateAvailable && updateNotTooRecent)
                        "New version available, released > 1 week"
                    else if (updateAvailable)
                        "New version available, but still recent, released $releaseDate"
                    else
                        "App is up to date"
                )

                prefs.edit().putLong(LAST_UPDATE_CHECK_TIME_PREF, System.currentTimeMillis()).apply()
                return@withContext updateAvailable && updateNotTooRecent
            } catch (e: Exception) {
                Log.w(TAG, e)
                return@withContext false
            }
        }
    }

}

private fun wasInstalledFromStore(context: Context): Boolean {
    return context.packageManager.getInstallerPackageName(context.packageName) != null
}

private fun firstInstallTime(context: Context): Long {
    return context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
}

private data class GithubRelease(
    val tag_name: String?,
    val name: String?,
    val published_at: String
)

private fun tryParseSemver(version: String?): SemVer? = try {
    if (version == null) null
    else SemVer.parse(
        // Strip leading 'v'
        version.replace(Regex("^v"), "")
    )
} catch (e: IllegalArgumentException) {
    null
}

private fun getInstalledVersion(context: Context): SemVer {
    return SemVer.parse(
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    )
}

private fun daysAgo(days: Int): Date {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -days)
    return calendar.time
}