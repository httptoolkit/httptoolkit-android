package tech.httptoolkit.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.*
import java.security.cert.X509Certificate


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456
const val SCAN_REQUEST = 789

enum class MainState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

private const val ACTIVATE_INTENT = "tech.httptoolkit.android.ACTIVATE"
private const val DEACTIVATE_INTENT = "tech.httptoolkit.android.DEACTIVATE"

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var app: HttpToolkitApplication

    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VPN_STARTED_BROADCAST) {
                mainState = MainState.CONNECTED
                currentProxyConfig = intent.getParcelableExtra(PROXY_CONFIG_EXTRA)
                updateUi()
            } else if (intent.action == VPN_STOPPED_BROADCAST) {
                mainState = MainState.DISCONNECTED
                currentProxyConfig = null
                updateUi()
            }
        }
    }

    private var mainState: MainState = if (isVpnActive()) MainState.CONNECTED else MainState.DISCONNECTED
    // If connected/late-stage connecting, the proxy we're connected/trying to connect to. Otherwise null.
    private var currentProxyConfig: ProxyConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })

        app = this.application as HttpToolkitApplication
        setContentView(R.layout.main_layout)
        updateUi()

        Log.i(TAG, "Main activity created")

        // Are we being opened by an intent? I.e. a barcode scan/URL elsewhere on the device
        if (intent != null) {
            onNewIntent(intent)
        } else {
            // If not, check if this is a post-install run, and if so configure automatically
            // using the install referrer
            launch {
                val firstRunParams = app.popFirstRunParams()
                if (
                    firstRunParams != null &&
                    firstRunParams.startsWith("https://android.httptoolkit.tech/connect/")
                ) {
                    launch { connectToVpnFromUrl(firstRunParams) }
                }
            }
        }

        // Async check for updates, and maybe prompt the user if necessary (if using play store)
        launch {
            supervisorScope {
                if (isStoreAvailable(this@MainActivity) && app.isUpdateRequired()) promptToUpdate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        app.trackScreen("Main")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        app.clearScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        app.trackEvent("Setup", "action-view")

        // RC intents are intents that have passed the RC permission requirement in the manifest.
        // Implicit intents with the matching actions will always use the RC activity, this check
        // protects against explicit intents targeting MainActivity. RC intents are known to be
        // trustworthy, so are allowed to silently activate/deactivate the VPN connection.
        val isRCIntent = intent.component?.className == "tech.httptoolkit.android.RemoteControlMainActivity"

        when {
            // ACTION_VIEW means that somebody had the app installed, and scanned the barcode with
            // a separate barcode app anyway (or opened the QR code URL in a browser)
            intent.action == Intent.ACTION_VIEW -> {
                if (app.lastProxy != null && isVpnConfigured()) {
                    Log.i(TAG, "Showing prompt for ACTION_VIEW intent")

                    // If we were started from an intent (e.g. another barcode scanner/link), and we
                    // had a proxy before (so no prompts required) then confirm before starting the VPN.
                    // Without this any QR code you scan could instantly MitM you.
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Enable Interception")
                        .setIcon(R.drawable.ic_exclamation_triangle)
                        .setMessage(
                            "Do you want to share all this device's HTTP traffic with HTTP Toolkit?" +
                            "\n\n" +
                            "Only accept this if you trust the source."
                        )
                        .setPositiveButton("Enable") { _, _ ->
                            Log.i(TAG, "Prompt confirmed")
                            launch { connectToVpnFromUrl(intent.data!!) }
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            Log.i(TAG, "Prompt cancelled")
                        }
                        .show()
                } else {
                    Log.i(TAG, "Launching from ACTION_VIEW intent")
                    launch { connectToVpnFromUrl(intent.data!!) }
                }
            }

            // RC setup API, used by ADB to enable/disable without prompts.
            // Permission required, checked for via activity-alias in the manifest
            isRCIntent && intent.action == ACTIVATE_INTENT -> {
                launch { connectToVpnFromUrl(intent.data!!) }
            }
            isRCIntent && intent.action == DEACTIVATE_INTENT -> {
                disconnect()
            }

            else -> Log.w(TAG, "Unknown intent. Action ${
                intent.action
            }, data: ${
                intent.data
            }, ${
                if (isRCIntent) "sent as RC intent" else "non-RC"
            }")
        }
    }

    private fun updateUi() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val detailText = findViewById<TextView>(R.id.detailText)

        val buttonContainer = findViewById<LinearLayout>(R.id.buttonLayoutContainer)
        buttonContainer.removeAllViews()

        when (mainState) {
            MainState.DISCONNECTED -> {
                statusText.setText(R.string.disconnected_status)

                detailText.visibility = View.VISIBLE
                detailText.setText(R.string.disconnected_details)

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.scan_button, ::scanCode))

                val lastProxy = app.lastProxy
                if (lastProxy != null) {
                    buttonContainer.addView(secondaryButton(R.string.reconnect_button) {
                        launch { reconnect(lastProxy) }
                    })
                }
            }
            MainState.CONNECTING -> {
                statusText.setText(R.string.connecting_status)

                detailText.visibility = View.GONE
                buttonContainer.visibility = View.GONE
            }
            MainState.CONNECTED -> {
                statusText.setText(R.string.connected_status)

                detailText.visibility = View.VISIBLE
                detailText.text = getString(
                    R.string.connected_details,
                    currentProxyConfig!!.ip,
                    currentProxyConfig!!.port
                )

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.disconnect_button, ::disconnect))
            }
            MainState.DISCONNECTING -> {
                statusText.setText(R.string.disconnecting_status)

                detailText.visibility = View.GONE
                buttonContainer.visibility = View.GONE
            }
            MainState.FAILED -> {
                statusText.setText(R.string.failed_status)

                detailText.visibility = View.VISIBLE
                detailText.setText(R.string.failed_details)

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.try_again_button, ::resetAfterFailure))
            }
        }

        if (buttonContainer.visibility == View.VISIBLE) {
            buttonContainer.addView(secondaryButton(R.string.docs_button, ::openDocs))
        }
    }

    private fun primaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
        val button = layoutInflater.inflate(R.layout.primary_button, null) as Button
        button.setText(contentId)
        button.setOnClickListener { clickHandler() }
        return button
    }

    private fun secondaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
        val button = layoutInflater.inflate(R.layout.secondary_button, null) as Button
        button.setText(contentId)
        button.setOnClickListener { clickHandler() }
        return button
    }

    private fun scanCode() {
        app.trackEvent("Button", "scan-code")
        startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
    }

    private suspend fun connectToVpn(config: ProxyConfig) {
        Log.i(TAG, "Connect to VPN")

        this.currentProxyConfig = config
        this.mainState = MainState.CONNECTING

        withContext(Dispatchers.Main) { updateUi() }

        app.trackEvent("Button", "start-vpn")
        val vpnIntent = VpnService.prepare(this)
        Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")
        val vpnNotConfigured = vpnIntent != null

        if (whereIsCertTrusted(config) == null) {
            // The cert isn't trusted, and the VPN may need setup, so there'll be a series of prompts
            // here. Explain them beforehand, so users understand what's going on.
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Enable interception")
                    .setIcon(R.drawable.ic_info_circle)
                    .setMessage(
                        "To intercept traffic from this device, you need to " +
                        (if (vpnNotConfigured) "activate HTTP Toolkit's VPN and " else "") +
                        "trust your HTTP Toolkit's certificate authority. " +
                        "\n\n" +
                        "Please accept the following prompts to allow this." +
                        if (!isDeviceSecured(applicationContext))
                            "\n\n" +
                            "Due to Android security requirements, trusting the certificate will " +
                            "require you to set a PIN, password or pattern for this device."
                        else " To trust the certificate, your device PIN will be required."
                    )
                    .setPositiveButton("Ok") { _, _ ->
                        if (vpnNotConfigured) {
                            startActivityForResult(vpnIntent, START_VPN_REQUEST)
                        } else {
                            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
                        }
                    }
                    .show()
            }
        } else if (vpnNotConfigured) {
            // In this case the VPN needs setup, but the cert is trusted already, so it's
            // a single confirmation. Pretty clear, no need to explain. This happens if the
            // VPN/app was removed from the device in the past, or when using injected system certs.
            startActivityForResult(vpnIntent, START_VPN_REQUEST)
        } else {
            // VPN is trusted & cert setup already, lets get to it.
            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
        }

    }

    private fun disconnect() {
        currentProxyConfig = null
        mainState = MainState.DISCONNECTING
        updateUi()

        app.trackEvent("Button", "stop-vpn")
        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = STOP_VPN_ACTION
        })
    }

    private suspend fun reconnect(lastProxy: ProxyConfig) {
        app.trackEvent("Button", "reconnect")

        withContext(Dispatchers.Main) {
            mainState = MainState.CONNECTING
            updateUi()
        }

        try {
            // Revalidates the config, to ensure the server is available (and drop retries if not)
            val config = getProxyConfig(
                ProxyInfo(
                    listOf(lastProxy.ip),
                    lastProxy.port,
                    getCertificateFingerprint(lastProxy.certificate as X509Certificate)
                )
            )
            connectToVpn(config)
        } catch (e: Exception) {
            app.lastProxy = null

            Log.e(TAG, e.toString())
            e.printStackTrace()
            Sentry.capture(e)
            withContext(Dispatchers.Main) {
                app.trackEvent("Setup", "reconnect-failed")
                mainState = MainState.FAILED
                updateUi()
            }
        }
    }

    private fun resetAfterFailure() {
        app.trackEvent("Button", "try-again")
        currentProxyConfig = null
        mainState = MainState.DISCONNECTED
        updateUi()
    }

    private fun openDocs() {
        app.trackEvent("Button", "open-docs")
        val browserIntent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://httptoolkit.tech/docs/guides/android")
        )
        startActivity(browserIntent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.i(TAG, "onActivityResult")
        Log.i(TAG, when (requestCode) {
            START_VPN_REQUEST -> "start-vpn"
            INSTALL_CERT_REQUEST -> "install-cert"
            SCAN_REQUEST -> "scan-request"
            else -> requestCode.toString()
        })
        Log.i(TAG, if (resultCode == RESULT_OK) "ok" else resultCode.toString())

        if (resultCode == RESULT_OK) {
            if (requestCode == START_VPN_REQUEST && currentProxyConfig != null) {
                Log.i(TAG, "Installing cert")
                ensureCertificateTrusted(currentProxyConfig!!)
            } else if (requestCode == INSTALL_CERT_REQUEST) {
                Log.i(TAG, "Starting VPN")
                startService(Intent(this, ProxyVpnService::class.java).apply {
                    action = START_VPN_ACTION
                    putExtra(PROXY_CONFIG_EXTRA, currentProxyConfig)
                })
            } else if (requestCode == SCAN_REQUEST && data != null) {
                val url = data.getStringExtra(SCANNED_URL_EXTRA)
                launch { connectToVpnFromUrl(url) }
            }
        } else {
            Sentry.capture("Non-OK result $resultCode for requestCode $requestCode")
            mainState = MainState.FAILED
            updateUi()
        }
    }

    private suspend fun connectToVpnFromUrl(url: String) {
        connectToVpnFromUrl(Uri.parse(url))
    }

    private suspend fun connectToVpnFromUrl(uri: Uri) {
        Log.i(TAG, "Connecting to VPN from URL: $uri")
        if (
            mainState != MainState.DISCONNECTED &&
            mainState != MainState.FAILED
        ) return

        withContext(Dispatchers.Main) {
            mainState = MainState.CONNECTING
            updateUi()
        }

        withContext(Dispatchers.IO) {
            try {
                val config = getProxyConfig(parseConnectUri(uri))
                connectToVpn(config)
            } catch (e: Exception) {
                Log.e(TAG, e.toString())
                e.printStackTrace()
                Sentry.capture(e)
                withContext(Dispatchers.Main) {
                    app.trackEvent("Setup", "connect-failed")
                    mainState = MainState.FAILED
                    updateUi()
                }
            }
        }
    }

    private fun isVpnConfigured(): Boolean {
        return VpnService.prepare(this) == null
    }

    private fun ensureCertificateTrusted(proxyConfig: ProxyConfig) {
        if (whereIsCertTrusted(proxyConfig) == null) {
            app.trackEvent("Setup", "installing-cert")
            Log.i(TAG, "Certificate not trusted, prompting to install")

            // Install the required cert into the user CA store. Notably, if the cert is already
            // installed as a system cert but disabled, this will get triggered, and will enable
            // the cert, rather than adding a user cert.
            val certInstallIntent = KeyChain.createInstallIntent()
            certInstallIntent.putExtra(EXTRA_NAME, "HTTP Toolkit CA")
            certInstallIntent.putExtra(EXTRA_CERTIFICATE, proxyConfig.certificate.encoded)
            startActivityForResult(certInstallIntent, INSTALL_CERT_REQUEST)
        } else {
            app.trackEvent("Setup", "existing-cert")
            Log.i(TAG, "Certificate already trusted, continuing")
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
    }

    private suspend fun promptToUpdate() {
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Updates available")
                .setIcon(R.drawable.ic_info_circle)
                .setMessage("An updated version of HTTP Toolkit is available")
                .setNegativeButton("Ignore") { _, _ -> }
                .setPositiveButton("Update now") { _, _ ->
                    // Open the app in the market. That a release is available on github doesn't
                    // *strictly* mean that it's available on the Android market right now, but
                    // it is imminent, and installing from play means it'll update fully later.
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("market://details?id=tech.httptoolkit.android.v1")
                        }
                    )
                }
                .show()
        }
    }
}

private fun isStoreAvailable(context: Context): Boolean = try {
    context.packageManager.getPackageInfo(GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}