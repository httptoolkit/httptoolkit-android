package tech.httptoolkit.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.beust.klaxon.Klaxon
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.lang.IllegalArgumentException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.cert.CertificateFactory
import java.security.KeyStore
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456
const val SCAN_REQUEST = 789

enum class MainState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

private fun getCertificateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(cert.publicKey.encoded)
    val fingerprint = md.digest()
    return Base64.encodeToString(fingerprint, Base64.NO_WRAP)
}

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val TAG = MainActivity::class.simpleName
    private var app: HttpToolkitApplication? = null

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

        setContentView(R.layout.activity_main)
        updateUi()

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })
        app = this.application as HttpToolkitApplication

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        app!!.trackScreen("Main")
    }

    override fun onPause() {
        super.onPause()
        app!!.clearScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
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
                buttonContainer.addView(secondaryButton(R.string.manual_button, { }))
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
        app!!.trackEvent("Button", "scan-code")
        startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
    }

    private fun connectToVpn(config: ProxyConfig) {
        Log.i(TAG, "Connect to VPN")

        this.currentProxyConfig = config
        this.mainState = MainState.CONNECTING
        updateUi()

        app!!.trackEvent("Button", "start-vpn")
        val vpnIntent = VpnService.prepare(this)
        Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")

        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, START_VPN_REQUEST)
        } else {
            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
        }

    }

    private fun disconnect() {
        currentProxyConfig = null
        mainState = MainState.DISCONNECTING
        updateUi()

        app!!.trackEvent("Button", "stop-vpn")
        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = STOP_VPN_ACTION
        })
    }

    private fun openDocs() {
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

        if (resultCode != RESULT_OK) return

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
            launch { connectToVpnFromUrl(Uri.parse(url)) }
        }
    }

    private suspend fun connectToVpnFromUrl(uri: Uri) {
        withContext(Dispatchers.Main) {
            mainState = MainState.CONNECTING
            updateUi()
        }

        withContext(Dispatchers.IO) {
            val dataBase64 = uri.getQueryParameter("data")

            // Data is a JSON string, encoded as base64, to solve escaping & ensure that the
            // most popular standard barcode apps treat it as a single URL (some get confused by
            // JSON that contains ip addresses otherwise)
            val data = String(Base64.decode(dataBase64, Base64.URL_SAFE), StandardCharsets.UTF_8)
            Log.v(TAG, "URL data is $data")

            val proxyInfo = Klaxon().parse<ProxyInfo>(data)
                // TODO: Wrap this all in a try, and properly handle failures
                ?: throw IllegalArgumentException("Invalid proxy JSON: $data")

            val config = getProxyConfig(proxyInfo)
            connectToVpn(config)
        }
    }

    private suspend fun getProxyConfig(proxyInfo: ProxyInfo): ProxyConfig {
        return withContext(Dispatchers.IO) {
            Log.v(TAG, "Validating proxy info $proxyInfo")

            val proxyTests = proxyInfo.addresses.map { address ->
                supervisorScope {
                    async {
                        testProxyAddress(
                            address,
                            proxyInfo.port,
                            proxyInfo.certFingerprint
                        )
                    }
                }
            }

            // Returns with the first working proxy config (cert & address),
            // or throws if all possible addresses are unreachable/invalid
            // Once the first test succeeds, we cancel any others
            val result = proxyTests.awaitFirst()
            proxyTests.forEach { test ->
                test.cancel()
            }
            return@withContext result
        }
    }

    private suspend fun testProxyAddress(
        address: String,
        port: Int,
        expectedFingerprint: String
    ): ProxyConfig {
        return withContext(Dispatchers.IO) {
            val certFactory = CertificateFactory.getInstance("X.509")

            val httpClient = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, port)))
                .connectTimeout(2000, TimeUnit.SECONDS)
                .readTimeout(2000, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://android.httptoolkit.tech/config")
                .build()

            try {
                val configString = httpClient.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw ConnectException("Proxy responded with non-200: ${response.code}")
                    }
                    response.body!!.string()
                }
                val config = Klaxon().parse<ReceivedProxyConfig>(configString)!!

                val foundCert = certFactory.generateCertificate(
                    ByteArrayInputStream(config.certificate.toByteArray(Charsets.UTF_8))
                ) as X509Certificate
                val foundCertFingerprint = getCertificateFingerprint(foundCert)

                if (foundCertFingerprint == expectedFingerprint) {
                    ProxyConfig(
                        address,
                        port,
                        foundCert
                    )
                } else {
                    throw CertificateException(
                        "Proxy returned mismatched certificate: '${
                            expectedFingerprint
                        }' != '$foundCertFingerprint' ($address)"
                    )
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error testing proxy address $address: $e")
                throw e
            }
        }
    }

    private fun ensureCertificateTrusted(proxyConfig: ProxyConfig) {
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)

        val certificateAlias = keyStore.getCertificateAlias(proxyConfig.certificate)

        if (certificateAlias == null) {
            app!!.trackEvent("Setup", "installing-cert")
            Log.i(TAG, "Certificate not trusted, prompting to install")
            val certInstallIntent = KeyChain.createInstallIntent()
            certInstallIntent.putExtra(EXTRA_NAME, "HTTP Toolkit CA")
            certInstallIntent.putExtra(EXTRA_CERTIFICATE, proxyConfig.certificate.encoded)
            startActivityForResult(certInstallIntent, INSTALL_CERT_REQUEST)
        } else {
            app!!.trackEvent("Setup", "existing-cert")
            Log.i(TAG, "Certificate already trusted, continuing")
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
    }

}
