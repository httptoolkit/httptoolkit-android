package tech.httptoolkit.android

import android.net.VpnService
import android.content.Intent
import android.app.*
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.*

private const val ALL_ROUTES = "0.0.0.0"
private const val PROXY_ROUTE = "10.0.0.110"

private const val NOTIFICATION_ID = 45456
private const val NOTIFICATION_CHANNEL_ID = "vpn-notifications"

const val START_VPN_ACTION = "tech.httptoolkit.android.START_VPN_ACTION"
const val STOP_VPN_ACTION = "tech.httptoolkit.android.STOP_VPN_ACTION"

const val VPN_STARTED_BROADCAST = "tech.httptoolkit.android.VPN_STARTED_BROADCAST"
const val VPN_STOPPED_BROADCAST = "tech.httptoolkit.android.VPN_STOPPED_BROADCAST"

class ProxyVpnService : VpnService() {

    private val TAG = ProxyVpnService::class.simpleName

    private var localBroadcastManager: LocalBroadcastManager? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called")
        Log.i(TAG, if (intent.action != null) intent.action else "no action")

        if (localBroadcastManager == null) {
            localBroadcastManager = LocalBroadcastManager.getInstance(this)
        }

        if (intent.action == START_VPN_ACTION) {
            startVpn()
        } else if (intent.action == STOP_VPN_ACTION) {
            stopVpn()
        }

        return Service.START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.i(TAG, "onRevoke called")
        stopVpn()
    }

    private fun showServiceNotification() {
        val pendingActivityIntent: PendingIntent =
            Intent(this, ProxyVpnService::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }

        val pendingServiceIntent: PendingIntent =
            Intent(this, ProxyVpnService::class.java).let { notificationIntent ->
                notificationIntent.action = STOP_VPN_ACTION
                PendingIntent.getService(this, 1, notificationIntent, 0)
            }


        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "VPN Status",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(notificationChannel)

        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentIntent(pendingActivityIntent)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_active_notification_content))
            .setSmallIcon(R.drawable.ic_transparent_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_transparent_icon))
            .addAction(R.drawable.ic_transparent_icon, getString(R.string.vpn_active_notification_action), pendingServiceIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

    }

    private fun startVpn() {
        if (vpnInterface == null) {
            val builder = Builder()
            builder.addAddress(PROXY_ROUTE, 32)
            builder.addRoute(ALL_ROUTES, 0)

            vpnInterface = builder
                .setSession(getString(R.string.app_name))
                .establish()

            showServiceNotification()
            localBroadcastManager!!.sendBroadcast(Intent(VPN_STARTED_BROADCAST))
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) { }

        stopForeground(true)
        localBroadcastManager!!.sendBroadcast(Intent(VPN_STOPPED_BROADCAST))
    }

}
