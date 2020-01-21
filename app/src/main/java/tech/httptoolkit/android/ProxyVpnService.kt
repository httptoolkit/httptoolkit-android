package tech.httptoolkit.android

import android.net.VpnService
import android.content.Intent
import android.app.*
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat.stopForeground
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lipisoft.toyshark.socket.IProtectSocket
import com.lipisoft.toyshark.socket.SocketProtector
import io.sentry.Sentry
import io.sentry.android.AndroidSentryClientFactory
import java.io.*
import java.net.DatagramSocket
import java.net.Socket

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

class ProxyVpnService : VpnService(), IProtectSocket {

    private val TAG = ProxyVpnService::class.simpleName
    private var app: HttpToolkitApplication? = null

    private var localBroadcastManager: LocalBroadcastManager? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnRunnable: ProxyVpnRunnable? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        currentService = this
        Log.i(TAG, "onStartCommand called")
        Log.i(TAG, if (intent.action != null) intent.action else "no action")

        if (localBroadcastManager == null) {
            localBroadcastManager = LocalBroadcastManager.getInstance(this)
        }
        app = this.application as HttpToolkitApplication

        if (intent.action == START_VPN_ACTION) {
            val proxyConfig = intent.getParcelableExtra<ProxyConfig>(PROXY_CONFIG_EXTRA)
            startVpn(proxyConfig)
        } else if (intent.action == STOP_VPN_ACTION) {
            stopVpn()
        }

        return Service.START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        app!!.trackEvent("VPN", "vpn-revoked")
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_active_notification_content))
            .setSmallIcon(R.drawable.ic_transparent_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_transparent_icon))
            .addAction(R.drawable.ic_transparent_icon, getString(R.string.vpn_active_notification_action), pendingServiceIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

    }

    private fun startVpn(proxyConfig: ProxyConfig) {
        if (vpnInterface == null) {
            app!!.pauseEvents() // Try not to send events while the VPN is active, it's unnecessary noise
            app!!.trackEvent("VPN", "vpn-started")
            vpnInterface = Builder()
                .addAddress(VPN_IP_ADDRESS, 32)
                .addRoute(ALL_ROUTES, 0)
                .setSession(getString(R.string.app_name))
                .establish()

            (this.application as HttpToolkitApplication).lastProxy = proxyConfig
            showServiceNotification()
            localBroadcastManager!!.sendBroadcast(
                Intent(VPN_STARTED_BROADCAST).apply {
                    putExtra(PROXY_CONFIG_EXTRA, proxyConfig)
                }
            )

            SocketProtector.getInstance().setProtector(this)

            vpnRunnable = ProxyVpnRunnable(
                vpnInterface!!,
                proxyConfig.ip,
                proxyConfig.port,
                intArrayOf(80, 443)
            )
            Thread(vpnRunnable, "Vpn thread").start()
        }
    }

    private fun stopVpn() {
        if (vpnRunnable != null) {
            app!!.trackEvent("VPN", "vpn-stopped")
            app!!.resumeEvents()

            vpnRunnable!!.stop()
            vpnRunnable = null
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) { }

        stopForeground(true)
        localBroadcastManager!!.sendBroadcast(Intent(VPN_STOPPED_BROADCAST))
    }

    fun isActive(): Boolean {
        return this.vpnInterface != null
    }

}
