package tech.httptoolkit.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager

const val START_VPN_REQUEST = 123

class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.simpleName

    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VPN_STARTED_BROADCAST) {
                vpnEnabled = true
                updateVpnUiState()
            } else if (intent.action == VPN_STOPPED_BROADCAST) {
                vpnEnabled = false
                updateVpnUiState()
            }
        }
    }

    private var vpnEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
    }

    private fun updateVpnUiState() {
        val toggleButton = findViewById<TextView>(R.id.toggleButton)
        toggleButton.text = if (vpnEnabled) "Stop Intercepting" else "Start Intercepting"
    }

    fun toggleVpn(@Suppress("UNUSED_PARAMETER") view: View) {
        Log.i(TAG, "Toggle VPN")
        vpnEnabled = !vpnEnabled

        if (vpnEnabled) {
            val vpnIntent = VpnService.prepare(this)
            Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")

            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, START_VPN_REQUEST)
            } else {
                onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
            }
        } else {
            startService(Intent(this, ProxyVpnService::class.java).apply {
                action = STOP_VPN_ACTION
            })
        }

        updateVpnUiState()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.i(TAG, "onActivityResult")
        Log.i(TAG, if (requestCode == START_VPN_REQUEST) "start" else requestCode.toString())
        Log.i(TAG, if (resultCode == RESULT_OK) "ok" else resultCode.toString())

        if (requestCode == START_VPN_REQUEST && resultCode == RESULT_OK) {
            startService(Intent(this, ProxyVpnService::class.java).apply {
                action = START_VPN_ACTION
            })
        }
    }

}
