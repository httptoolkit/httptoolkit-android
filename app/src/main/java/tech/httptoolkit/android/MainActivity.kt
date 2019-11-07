package tech.httptoolkit.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.KeyStore


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456

const val PROXY_CERTIFICATE = """-----BEGIN CERTIFICATE-----
MIICzjCCAbagAwIBAgIQLNggwsgpTyi+8AyfhjeGujANBgkqhkiG9w0BAQsFADAa
MRgwFgYDVQQDEw9IVFRQIFRvb2xraXQgQ0EwHhcNMTkwOTI0MTkyNzA0WhcNMjAw
OTI1MTkyNzA0WjAaMRgwFgYDVQQDEw9IVFRQIFRvb2xraXQgQ0EwggEiMA0GCSqG
SIb3DQEBAQUAA4IBDwAwggEKAoIBAQCxPhKSGpISlTTQFu8fut2A0wxfABIt6uor
tC/G0EGtj0WY6+9XCgj9zdPiPPTj99yOZ0PcdDFWhCLKb3bCxiKsHsmk5r/envuR
co1Y+AIfUGLicmkYWOlMzJTYoZ5DHKnmLRk4sRhfO5uYO1D/IqRBUNIiEWw+G+3i
q0ApPQhWs3Aa6OzEfsVK54FgYdN7go2VmTqmlET7xQz1GC9HOCX/WRbQp22DmLp8
9YLqGj9ktTxCJ31wWlPtBZSKTsGmD5pzxZrq3uHzNmhU9HjxZBBT/KgjnAVg5ydw
ayr1nwm7FNZN2O6w5Ap11vKKG12uMiW66hLzEX9qOWxMBH+X4m9FAgMBAAGjEDAO
MAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAG8VVWMNu0LhlT1sJYhF
5UU+JIl+oPXNQf+xq+94t1m1gnQXdb54gVKPzT83gtWVKJkyqcL6/q6EnBjj5S0m
Gg3yA88FLq/tKtnYh9PxQvC2tsZ9mbBRj3fHxAaNy8SnUZcpKRAioDYbcf8PUOn9
vuNeLE+GM6JjP1p6MDkSWpuDLU5EywVHwx8hNdl9ECrRtCwhEtjGgY/k9hf59lyo
xj1gUGoS3wxJQo6xV619wRxPvUyJtR8P/OvbYk8l95V2u3JfvS8E5lLng58C40nu
Z8qaoUyskFJl/MlBoAgIgM9HB+6Uc9M+dvgPl91G3xWegjr0YDEf2Y8pz2X7bmCc
bYI=
-----END CERTIFICATE-----
"""

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

    fun scanCode(@Suppress("UNUSED_PARAMETER") view: View) {
        startActivity(Intent(this, ScanActivity::class.java))
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
        Log.i(TAG, when (requestCode) {
            START_VPN_REQUEST -> "start-vpn"
            INSTALL_CERT_REQUEST -> "install-cert"
            else -> requestCode.toString()
        })
        Log.i(TAG, if (resultCode == RESULT_OK) "ok" else resultCode.toString())

        if (requestCode == START_VPN_REQUEST && resultCode == RESULT_OK) {
            Log.i(TAG, "Installing cert")
            ensureCertificateTrusted()
        } else if (requestCode == INSTALL_CERT_REQUEST && resultCode == RESULT_OK) {
            Log.i(TAG, "Starting VPN")
            startService(Intent(this, ProxyVpnService::class.java).apply {
                action = START_VPN_ACTION
            })
        }
    }

    private fun ensureCertificateTrusted() {
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)

        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(
            ByteArrayInputStream(PROXY_CERTIFICATE.toByteArray(Charsets.UTF_8))
        )
        val certificateAlias = keyStore.getCertificateAlias(cert)

        if (certificateAlias == null) {
            Log.i(TAG, "Certificate not trusted, prompting to install")
            val certInstallIntent = KeyChain.createInstallIntent()
            certInstallIntent.putExtra(EXTRA_NAME, "HTTP Toolkit CA")
            certInstallIntent.putExtra(EXTRA_CERTIFICATE, cert.encoded)
            startActivityForResult(certInstallIntent, INSTALL_CERT_REQUEST)
        } else {
            Log.i(TAG, "Certificate already trusted, continuing")
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
    }

}
