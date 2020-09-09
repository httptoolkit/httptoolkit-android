package tech.httptoolkit.android

import android.net.VpnService
import android.content.Intent
import android.app.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import tech.httptoolkit.android.vpn.socket.IProtectSocket
import tech.httptoolkit.android.vpn.socket.SocketProtector
import io.sentry.Sentry
import java.io.*

private const val ALL_ROUTES = "0.0.0.0"
private const val VPN_IP_ADDRESS = "169.254.61.43" // Random link-local IP, this will be the tunnel's IP

private const val NOTIFICATION_ID = 45456
private const val NOTIFICATION_CHANNEL_ID = "vpn-notifications"

const val START_VPN_ACTION = "tech.httptoolkit.android.START_VPN_ACTION"
const val STOP_VPN_ACTION = "tech.httptoolkit.android.STOP_VPN_ACTION"

const val VPN_STARTED_BROADCAST = "tech.httptoolkit.android.VPN_STARTED_BROADCAST"
const val VPN_STOPPED_BROADCAST = "tech.httptoolkit.android.VPN_STOPPED_BROADCAST"

const val PROXY_CONFIG_EXTRA = "tech.httptoolkit.android.PROXY_CONFIG"

private var currentService: ProxyVpnService? = null
fun isVpnActive(): Boolean {
    return if (currentService == null)
        false
    else
        currentService?.isActive() ?: false
}

fun activeVpnConfig(): ProxyConfig? {
    return currentService?.proxyConfig
}

class ProxyVpnService : VpnService(), IProtectSocket {

    private lateinit var app: HttpToolkitApplication

    private var localBroadcastManager: LocalBroadcastManager? = null

    var proxyConfig: ProxyConfig? = null
        private set

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnRunnable: ProxyVpnRunnable? = null

    override fun onCreate() {
        super.onCreate()
        currentService = this
    }

    override fun onDestroy() {
        super.onDestroy()
        currentService = null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        currentService = this
        Log.i(TAG, "onStartCommand called")
        Log.i(TAG, intent.action ?: "no action")

        if (localBroadcastManager == null) {
            localBroadcastManager = LocalBroadcastManager.getInstance(this)
        }
        app = this.application as HttpToolkitApplication

        if (intent.action == START_VPN_ACTION) {
            val proxyConfig = intent.getParcelableExtra<ProxyConfig>(PROXY_CONFIG_EXTRA)

            val vpnStarted = startVpn(proxyConfig!!)

            if (vpnStarted) {
                // If the system briefly kills us for some reason (memory, the user, whatever) whilst
                // running the VPN, it should redeliver the VPN setup intent ASAP.
                return Service.START_REDELIVER_INTENT
            } else {
                // We failed to start somehow - cleanup
                stopVpn()
            }
        } else if (intent.action == STOP_VPN_ACTION) {
            stopVpn()
        }

        // Shouldn't matter (we should've stopped already), but in general: if we're not running a
        // VPN, then the service doesn't need to be sticky.
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        app.trackEvent("VPN", "vpn-revoked")
        Log.i(TAG, "onRevoke called")
        stopVpn()
    }

    private fun showServiceNotification() {
        val pendingActivityIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }

        val pendingServiceIntent: PendingIntent =
            Intent(this, ProxyVpnService::class.java).let { notificationIntent ->
                notificationIntent.action = STOP_VPN_ACTION
                PendingIntent.getService(this, 1, notificationIntent, 0)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notificationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentIntent(pendingActivityIntent)
            .setContentTitle(getString(R.string.vpn_active_notification_title))
            .setContentText(getString(R.string.vpn_active_notification_content))
            .setSmallIcon(R.drawable.ic_transparent_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_transparent_icon))
            .addAction(0, getString(R.string.vpn_active_notification_action), pendingServiceIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

    }

    private fun startVpn(proxyConfig: ProxyConfig): Boolean {
        this.proxyConfig = proxyConfig
        val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

        val allPackageNames = packages.map { pkg -> pkg.packageName }
        val whiteListedPackages = ApplicationListActivity.getWhiteListAppSharedPreferences(this@ProxyVpnService).all.keys
        val isGenymotion = allPackageNames.any {
            // This check could be stricter (com.genymotion.genyd), but right now it doesn't seem to
            // have any false positives, and it's very flexible to changes in genymotion itself.
            name -> name.startsWith("com.genymotion")
        }

        if (this.vpnInterface != null) return false // The VPN is already running, somehow? Do nothing

        app.pauseEvents() // Try not to send events while the VPN is active, it's unnecessary noise
        app.trackEvent("VPN", "vpn-started")
        val vpnInterface = Builder()
            .addAddress(VPN_IP_ADDRESS, 32)
            .addRoute(ALL_ROUTES, 0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Where possible, we want to explicitly set the proxy in addition to
                    // manually redirecting traffic. This is useful because it captures HTTP sent
                    // to non-default ports. We still need to do both though, as not all clients
                    // will use the proxy settings.
                    setHttpProxy(ProxyInfo.buildDirectProxy(proxyConfig.ip, proxyConfig.port))
                }
            }

            .setMtu(MAX_PACKET_LEN) // Limit the packet size to the buffer used by ProxyVpnRunnable
            .setBlocking(true) // We use a blocking loop to read in ProxyVpnRunnable

            .apply {
                // We exclude ourselves from interception, so we can still make network requests
                // separately, primarily because otherwise pinging with isReachable is recursive.
                val httpToolkitPackage = packageName

                when {
                    whiteListedPackages.isNotEmpty() -> {
                        // If we've explicitly picked some packages, intercept only those:
                        whiteListedPackages.forEach {name ->
                            if (name != httpToolkitPackage) addAllowedApplication(name)
                        }
                    }
                    isGenymotion -> {
                        // For some reason, with Genymotion the whole device crashes if we intercept
                        // blindly, but intercepting every single application explicitly is fine:
                        allPackageNames.forEach { name ->
                            if (name != httpToolkitPackage) addAllowedApplication(name)
                        }
                    }
                    else -> {
                        // If we want everything, it's better to disallow just this app, rather than
                        // adding everything except this app, because that ensures new apps installed
                        // whilst interception is active get intercepted too:
                        addDisallowedApplication(httpToolkitPackage)
                    }
                }
            }
            .setSession(getString(R.string.app_name))
            .establish()

        // establish() returns null if we no longer have permissions to establish the VPN somehow
        // In that case, we give up. The UI
        if (vpnInterface == null) {
            return false
        } else {
            this.vpnInterface = vpnInterface
        }

        app.lastProxy = proxyConfig
        showServiceNotification()
        localBroadcastManager!!.sendBroadcast(
            Intent(VPN_STARTED_BROADCAST).apply {
                putExtra(PROXY_CONFIG_EXTRA, proxyConfig)
            }
        )

        SocketProtector.getInstance().setProtector(this)

        vpnRunnable = ProxyVpnRunnable(
            vpnInterface,
            proxyConfig.ip,
            proxyConfig.port,
            intArrayOf(
                80, // HTTP
                443, // HTTPS
                8000, 8001, 8080, 8888, 9000 // Common local dev ports
            )
        )
        Thread(vpnRunnable, "Vpn thread").start()

        app.vpnShouldBeRunning = true
        return true
    }

    private fun stopVpn() {
        Log.i(TAG, "VPN stopping..")

        if (vpnRunnable != null) {
            app.trackEvent("VPN", "vpn-stopped")
            app.resumeEvents()

            vpnRunnable!!.stop()
            vpnRunnable = null
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Sentry.capture(e)
        }

        stopForeground(true)
        localBroadcastManager!!.sendBroadcast(Intent(VPN_STOPPED_BROADCAST))
        stopSelf()

        currentService = null
        this.proxyConfig = null
        app.vpnShouldBeRunning = false
    }

    fun isActive(): Boolean {
        return this.vpnInterface != null
    }

}
