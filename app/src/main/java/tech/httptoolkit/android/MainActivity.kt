package tech.httptoolkit.android

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.text.Html
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.*
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import androidx.core.view.isVisible
import androidx.core.net.toUri


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456
const val ENABLE_NOTIFICATIONS_REQUEST = 101

enum class MainState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

private const val ACTIVATE_INTENT = "tech.httptoolkit.android.ACTIVATE"
private const val DEACTIVATE_INTENT = "tech.httptoolkit.android.DEACTIVATE"

private val PROMPTED_CERT_SETUP_SUPPORTED = Build.VERSION.SDK_INT < Build.VERSION_CODES.R;

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var app: HttpToolkitApplication

    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VPN_STARTED_BROADCAST) {
                mainState = MainState.CONNECTED
                currentProxyConfig = intent.getParcelableExtra(IntentExtras.PROXY_CONFIG_EXTRA)
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
    private var currentProxyConfig: ProxyConfig? = activeVpnConfig()

    // Used to track extremely fast VPN setup failures, indicating setup issues (rather than
    // manual user cancellation). Doesn't matter that it's not properly persistent.
    private var lastPauseTime = -1L

    val pickAppsContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Pick apps result: OK")
            val unselectedApps = result.data!!.getStringArrayExtra(IntentExtras.UNSELECTED_APPS_EXTRA)!!.toSet()
            if (unselectedApps != app.uninterceptedApps) {
                app.uninterceptedApps = unselectedApps
                if (isVpnActive()) startVpn()
            }
        } else {
            Log.i(TAG, "Pick apps result: ${result.resultCode}")
        }
    }

    val pickPortsContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Pick ports result: OK")
            val selectedPorts = result.data!!.getIntArrayExtra(IntentExtras.SELECTED_PORTS_EXTRA)!!.toSet()
            if (selectedPorts != app.interceptedPorts) {
                app.interceptedPorts = selectedPorts
                if (isVpnActive()) startVpn()
            }
        } else {
            Log.i(TAG, "Pick ports result: ${result.resultCode}")
        }
    }

    private val barcodeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val url = result.data!!.getStringExtra(IntentExtras.SCANNED_URL_EXTRA)!!
                launch { connectToVpnFromUrl(url) }
            }
        }

    private val cameraPermissionsFromSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            checkCameraPermission()
        }

    private val cameraPermissionHandler =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "Camera permissions granted")
                scanQRCode()
            } else {
                Log.i(TAG, "Camera permissions rejected")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Camera permission required")
                        .setMessage("To scan QR codes, you need to allow camera access.")
                        .setPositiveButton(getString(R.string.proceed)) { _, _ -> checkCameraPermission() }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
                        .show()
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Camera permission required")
                        .setMessage("To scan QR codes, you need to allow camera access in your device settings.")
                        .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = Uri.fromParts("package", packageName, null)
                            intent.data = uri
                            cameraPermissionsFromSettings.launch(intent)
                        }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
                        .show()
                }
            }
        }

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

        val batteryOptimizationsDisabled =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(packageName)
            } else {
                false // We can't check, so assume not
            }

        if (app.popVpnKilledState() && !batteryOptimizationsDisabled) {
            // The app was killed last run, probably by battery optimizations: show a warning
            showVpnKilledAlert()
        } else {
            // Async check for updates, and maybe prompt the user if necessary (if using play store)
            launch {
                supervisorScope {
                    if (isStoreAvailable(this@MainActivity) && app.isUpdateRequired()) promptToUpdate()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        this.lastPauseTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

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

            intent.action == "android.intent.action.MAIN" -> {
                // The app is being opened - nothing to do here
            }

            else -> Log.w(TAG,
                "Ignoring unknown intent. Action ${
                    intent.action
                }, data: ${
                    intent.data
                }${
                    if (isRCIntent) " (RC)" else ""
                }"
            )
        }
    }

    @MainThread
    private fun updateUi() {
        val statusText = findViewById<TextView>(R.id.statusText)

        val buttonContainer = findViewById<LinearLayout>(R.id.buttonLayoutContainer)
        buttonContainer.removeAllViews()

        val detailContainer = findViewById<LinearLayout>(R.id.statusDetailContainer)
        detailContainer.removeAllViews()

        when (mainState) {
            MainState.DISCONNECTED -> {
                statusText.setText(R.string.disconnected_status)
                buttonContainer.visibility = View.VISIBLE

                val hasCamera = this.packageManager
                    .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

                if (hasCamera) {
                    detailContainer.addView(detailText(R.string.disconnected_details))
                    val scanQrButton = primaryButton(R.string.scan_button, ::checkCameraPermission)
                    buttonContainer.addView(scanQrButton)
                } else {
                    detailContainer.addView(detailText(R.string.disconnected_no_camera_details))
                }

                val lastProxy = app.lastProxy
                if (lastProxy != null) {
                    buttonContainer.addView(secondaryButton(R.string.reconnect_button) {
                        launch { reconnect(lastProxy) }
                    })
                }
            }

            MainState.CONNECTING -> {
                statusText.setText(R.string.connecting_status)
                buttonContainer.visibility = View.GONE
            }

            MainState.CONNECTED -> {
                val proxyConfig = this.currentProxyConfig!!
                val totalAppCount = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                    .map { app -> app.packageName }
                    .toSet()
                    .size
                val interceptedAppsCount = totalAppCount - app.uninterceptedApps.size

                statusText.setText(R.string.connected_status)

                detailContainer.addView(
                    ConnectionStatusView(
                        this,
                        proxyConfig,
                        totalAppCount,
                        interceptedAppsCount,
                        ::chooseApps,
                        app.interceptedPorts,
                        ::choosePorts
                    )
                )

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.disconnect_button, ::disconnect))
                buttonContainer.addView(secondaryButton(R.string.test_button, ::testInterception))
            }

            MainState.DISCONNECTING -> {
                statusText.setText(R.string.disconnecting_status)
                buttonContainer.visibility = View.GONE
            }

            MainState.FAILED -> {
                statusText.setText(R.string.failed_status)

                detailContainer.addView(detailText(R.string.failed_details))

                buttonContainer.visibility = View.VISIBLE
                buttonContainer.addView(primaryButton(R.string.try_again_button, ::resetAfterFailure))
            }
        }

        if (buttonContainer.isVisible) {
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

    private fun detailText(@StringRes resId: Int): TextView {
        val text = TextView(ContextThemeWrapper(this, R.style.DetailText))
        text.text = getString(resId)
        return text
    }

    private fun checkCameraPermission() {
        val canUseCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (canUseCamera == PERMISSION_GRANTED) {
            scanQRCode()
        } else {
            cameraPermissionHandler.launch(Manifest.permission.CAMERA)
        }
    }

    private fun scanQRCode() {
        barcodeLauncher.launch(Intent(this, QRScanActivity::class.java))
    }

    private suspend fun connectToVpn(config: ProxyConfig) {
        Log.i(TAG, "Connect to VPN")

        this.currentProxyConfig = config
        this.mainState = MainState.CONNECTING

        withContext(Dispatchers.Main) { updateUi() }

        val vpnIntent = VpnService.prepare(this)
        Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")
        val vpnNotConfigured = vpnIntent != null

        if (vpnNotConfigured) {
            // Show the 'Enable the VPN' prompt
            startActivityForResult(vpnIntent, START_VPN_REQUEST)
        } else {
            // VPN is trusted already, continue
            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
        }

    }

    private fun disconnect() {
        currentProxyConfig = null
        mainState = MainState.DISCONNECTING
        updateUi()

        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = STOP_VPN_ACTION
        })
    }

    private suspend fun reconnect(lastProxy: ProxyConfig) {
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
                    null,
                    getCertificateFingerprint(lastProxy.certificate as X509Certificate)
                )
            )
            connectToVpn(config)
        } catch (e: Exception) {
            app.lastProxy = null

            Log.e(TAG, e.toString())
            e.printStackTrace()

            withContext(Dispatchers.Main) {
                mainState = MainState.FAILED
                updateUi()
            }

            // We report errors only that aren't simple connection failures
            if (e !is SocketTimeoutException && e !is ConnectException) {
                Sentry.captureException(e)
            }
        }
    }

    private fun resetAfterFailure() {
        currentProxyConfig = null
        mainState = MainState.DISCONNECTED
        updateUi()
    }

    private fun openDocs() {
        launchBrowser("httptoolkit.com/docs/guides/android")
    }

    private fun chooseApps() {
        pickAppsContract.launch(
            Intent(this, ApplicationListActivity::class.java).apply {
                putExtra(IntentExtras.UNSELECTED_APPS_EXTRA, app.uninterceptedApps.toTypedArray())
            }
        )
    }

    private fun choosePorts() {
        pickPortsContract.launch(
            Intent(this, PortListActivity::class.java).apply {
                putExtra(IntentExtras.SELECTED_PORTS_EXTRA, app.interceptedPorts.toIntArray())
            }
        )
    }

    private fun testInterception() {
        val certIsSystemTrusted = whereIsCertTrusted(
            this.currentProxyConfig!! // Safe!! because you can only run tests while connected
        ) == "system"

        // If we have a system cert, in theory we could use any browser. In practice though, some
        // (i.e. Firefox) ignore system certs to use their own settings. It's best to try and ensure
        // for testing, we always use a supported browser. This will prioritize the default, if it
        // is supported, so only matters if the default browser is not on our known-good list.
        val testBrowser = getTestBrowserPackage(this)

        val canUseHttps = testBrowser != null || certIsSystemTrusted

        launchBrowser("amiusing.httptoolkit.tech", canUseHttps, testBrowser)
    }

    private fun launchBrowser(uri: String, canUseHttps: Boolean = true, browserPackage: String? = null) {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    ((if (canUseHttps) "https" else "http") + "://" + uri).toUri()
                ).apply {
                    if (browserPackage != null) setPackage(browserPackage)
                }
            )
        } catch (e: ActivityNotFoundException) {
            if (browserPackage != null) {
                // If we tried a specific package, and it failed, try again with the simplest
                // plain HTTP catch-all VIEW intent, and hope something somewhere can handle it.
                launchBrowser(uri, false)
            } else {
                showNoBrowserAlert(uri)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val resultOk = resultCode == RESULT_OK ||
            (requestCode == INSTALL_CERT_REQUEST && whereIsCertTrusted(currentProxyConfig!!) != null) ||
            (requestCode == ENABLE_NOTIFICATIONS_REQUEST && areNotificationsEnabled())

        Log.i(TAG,
            "onActivityResult: " + (
                when (requestCode) {
                    START_VPN_REQUEST -> "start-vpn"
                    INSTALL_CERT_REQUEST -> "install-cert"
                    ENABLE_NOTIFICATIONS_REQUEST -> "enable-notifications"
                    else -> requestCode.toString()
                }
            ) + " - result: " + (
                if (resultOk) "ok" else resultCode.toString()
            )
        )

        if (resultOk) {
            if (requestCode == START_VPN_REQUEST && currentProxyConfig != null) {
                Log.i(TAG, "Installing cert...")
                ensureCertificateTrusted(currentProxyConfig!!)
            } else if (requestCode == INSTALL_CERT_REQUEST) {
                Log.i(TAG, "Cert installed, checking notification perms...")
                ensureNotificationsEnabled()
            } else if (requestCode == ENABLE_NOTIFICATIONS_REQUEST) {
                Log.i(TAG, "Notifications OK, starting VPN...")
                startVpn()
            }
        } else if (
            requestCode == START_VPN_REQUEST &&
            System.currentTimeMillis() - lastPauseTime < 200 && // On Pixel 4a it takes < 50ms
            resultCode == RESULT_CANCELED
        ) {
            // If another always-on VPN is active, VPN start requests fail instantly as cancelled.
            // We can't check that the VPN is always-on, but given an instant failure that's
            // the likely cause, so we warn about it:
            showActiveVpnFailureAlert()

            // Then go back to the disconnected state:
            mainState = MainState.DISCONNECTED
            updateUi()
        } else if (
            requestCode == INSTALL_CERT_REQUEST &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // Required for promptToManuallyInstallCert
        ) {
            // Certificate install failed. Could be manual (failed to follow instructions) or automated
            // via prompt. We redo the manual step regardless: either (on modern Android) manual is
            // required so this is just reshowing the instructions, or it was automated but that's not
            // working for some reason, in which case manual setup is a best-effort fallback.
            launch {
                promptToManuallyInstallCert(
                    currentProxyConfig!!.certificate,
                    repeatPrompt = true
                )
            }
        } else if (requestCode == ENABLE_NOTIFICATIONS_REQUEST) {
            // If we tried to enable notifications, and it didn't work (the user
            // ignored us) then try try again.
            requestNotificationPermission(true)
        } else {
            Sentry.captureMessage("Non-OK result $resultCode for requestCode $requestCode")
            mainState = MainState.FAILED
            updateUi()
        }
    }

    private fun startVpn() {
        Log.i(TAG, "Starting VPN")

        launch {
            withContext(Dispatchers.Main) {
                mainState = MainState.CONNECTING
                updateUi()
            }
        }

        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = START_VPN_ACTION
            putExtra(IntentExtras.PROXY_CONFIG_EXTRA, currentProxyConfig)
            putExtra(IntentExtras.UNINTERCEPTED_APPS_EXTRA, app.uninterceptedApps.toTypedArray())
            putExtra(IntentExtras.INTERCEPTED_PORTS_EXTRA, app.interceptedPorts.toIntArray())
        })
    }

    private suspend fun connectToVpnFromUrl(url: String) {
        connectToVpnFromUrl(url.toUri())
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

                withContext(Dispatchers.Main) {
                    mainState = MainState.FAILED
                    updateUi()
                }

                // We report errors only that aren't simple connection failures
                if (e !is SocketTimeoutException && e !is ConnectException) {
                    Sentry.captureException(e)
                }
            }
        }
    }

    private fun isVpnConfigured(): Boolean {
        return VpnService.prepare(this) == null
    }

    private fun ensureCertificateTrusted(proxyConfig: ProxyConfig) {
        val existingTrust = whereIsCertTrusted(proxyConfig)
        if (existingTrust == null) {
            Log.i(TAG, "Certificate not trusted, prompting to install")

            if (PROMPTED_CERT_SETUP_SUPPORTED) {
                // Up until Android 11, we can prompt the user to install the CA cert into the user
                // CA store. Notably, if the cert is already installed as a system cert but
                // disabled, this will get triggered, and will enable the cert, rather than adding
                // a normal user cert.
                launch { promptToAutoInstallCert(proxyConfig.certificate) }
            } else {
                // Android 11+, with no trusted cert: we need to download the cert to Downloads and
                // then tell the user how to install it manually:
                launch { promptToManuallyInstallCert(proxyConfig.certificate) }
            }
        } else {
            Log.i(TAG, "Certificate already trusted, continuing")
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
    }

    private suspend fun promptToAutoInstallCert(certificate: Certificate) {
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Enable HTTPS interception")
                .setIcon(R.drawable.ic_info_circle)
                .setMessage(
                    "To intercept HTTPS traffic from this device, you need to " +
                    "trust your HTTP Toolkit's certificate authority. " +
                    "\n\n" +
                    "Please accept the following prompts to allow this." +
                    if (!isDeviceSecured(applicationContext))
                        "\n\n" +
                        "Due to Android security requirements, trusting the certificate will " +
                        "require you to set a PIN, password or pattern for this device."
                    else " To trust the certificate, your device PIN will be required."
                )
                .setPositiveButton("Install") { _, _ ->
                    val certInstallIntent = KeyChain.createInstallIntent()
                    certInstallIntent.putExtra(EXTRA_NAME, "HTTP Toolkit CA")
                    certInstallIntent.putExtra(EXTRA_CERTIFICATE, certificate.encoded)
                    startActivityForResult(certInstallIntent, INSTALL_CERT_REQUEST)
                }
                .setNeutralButton("Skip") { _, _ ->
                    onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    disconnect()
                }
                .setCancelable(false)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun promptToManuallyInstallCert(cert: Certificate, repeatPrompt: Boolean = false) {
        if (!repeatPrompt) {
            // Get ready to save the cert to downloads:
            val downloadsUri =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val contentDetails = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "HTTP Toolkit Certificate.crt")
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val certUri = contentResolver.insert(downloadsUri, contentDetails)
                ?: throw RuntimeException("Could not get download cert URI")

            // Write cert contents to a file:
            withContext(Dispatchers.IO) {
                contentResolver.openFileDescriptor(certUri, "w", null).use { f ->
                    ParcelFileDescriptor.AutoCloseOutputStream(f).write(cert.encoded)
                }
            }

            // All done, mark it as such:
            contentDetails.clear()
            contentDetails.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(certUri, contentDetails, null, null)
        }

        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Manual setup required")
                .setIcon(R.drawable.ic_exclamation_triangle)
                .setMessage(
                    Html.fromHtml(
                        """
                        <p>
                            ${
                                if (PROMPTED_CERT_SETUP_SUPPORTED)
                                    "Automatic certificate installation failed, so it must be done manually."
                                else
                                    "Android ${Build.VERSION.RELEASE} doesn't allow automatic certificate setup."
                            }
                        </p>
                        <p>
                            To allow HTTP Toolkit to intercept HTTPS traffic:
                        </p>
                        <ul>
                            ${
                                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) // Android 12+
                                    """
                                    <li>&nbsp; Open "<b>${
                                        // Slightly different UI for Android 12 and 13:
                                        if (Build.VERSION.SDK_INT < 33) "Advanced Settings" else "More security settings"
                                    }</b>" in your security settings</li>
                                    <li>&nbsp; Open "<b>Encryption & Credentials</b>"</li>
                                    """
                                else
                                    """
                                    <li>&nbsp; Open "<b>Encryption & Credentials</b>" in your security settings</li>
                                    """
                            }
                            <li>&nbsp; Select "<b>Install a certificate</b>", then "<b>CA Certificate</b>"</li>
                            <li>&nbsp; <b>Select the HTTP Toolkit certificate in your Downloads folder</b></li>
                        </ul>
                    """, 0)
                )
                .setPositiveButton("Open security settings") { _, _ ->
                    startActivityForResult(Intent(Settings.ACTION_SECURITY_SETTINGS), INSTALL_CERT_REQUEST)
                }
                .setNeutralButton("Skip") { _, _ ->
                    onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    disconnect()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun ensureNotificationsEnabled() {
        if (areNotificationsEnabled()) {
            onActivityResult(ENABLE_NOTIFICATIONS_REQUEST, RESULT_OK, null)
        } else {
            // This should only be called on the first attempt, generally, so we assume we
            // haven't been rejected yet:
            requestNotificationPermission(false)
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        // In Android 13+ notification permissions are blocked (even for foreground services) until
        // we specifically request them.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
        ) {
            return false
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val appNotificationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationManager.areNotificationsEnabled()
        } else {
            true
        }

        if (!appNotificationsEnabled) return false

        // For Android < 26 you can only enable/disable notifications globally:
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        // For Android 26+ you can disable individual channels: here we check our VPN notification
        // channel is not disabled (if it's already been created).
        val channel = notificationManager.getNotificationChannel(VPN_NOTIFICATION_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestNotificationPermission(previouslyRejected: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shouldExplain = ActivityCompat.shouldShowRequestPermissionRationale(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS
            )

            if (shouldExplain) {
                // ShouldExplain means that we've asked before, but been rejected, but we are
                // still allowed to ask again. Be more insistent, and do so:
                showNotificationPermissionRequiredPrompt() { ->
                    Log.i(TAG, "Asking for POST_NOTIFICATIONS after prompt")
                    notificationPermissionHandler.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                return
            } else if (!previouslyRejected) {
                // This means we're asking for the first time - no detailed rationale and no
                // fallbacks required, just ask for permission:
                Log.i(TAG, "Asking for POST_NOTIFICATIONS directly")
                notificationPermissionHandler.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            // Otherwise, continue to the non-Tiramisu settings approach:
        }

        // Pre-Tiramisu, we can't use POST_NOTIFICATIONS. Alternatively, if Tiramisu but we've
        // been completely rejected already, we can't show a normal prompt. Either way, we need
        // to send the user to the settings page to fix this manually.

        // But if we have to send you to settings, we always want to show a prompt first:
        showNotificationPermissionRequiredPrompt { ->
            Log.i(TAG, "Sending to settings to fix notification permissions")
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivityForResult(intent, ENABLE_NOTIFICATIONS_REQUEST)
        }
    }

    private fun showNotificationPermissionRequiredPrompt(nextStep: () -> Unit) {
        Log.i(TAG, "Showing notifications-required prompt")
        launch {
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Notification permission is required")
                    .setIcon(R.drawable.ic_exclamation_triangle)
                    .setMessage(
                        "Please allow notifications to use HTTP Toolkit. This is used " +
                        "exclusively for VPN connection status indicators."
                    )
                    .setPositiveButton("Ok") { _, _ -> }
                    .setOnDismissListener { _ ->
                        // Dismiss is called on both click-away and 'Ok'
                        nextStep()
                    }
                    .show()
            }
        }
    }

    private val notificationPermissionHandler =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted && areNotificationsEnabled()) { // Note permission might be accepted but channels disabled
                Log.i(TAG, "Notifications permission prompt accepted")
                onActivityResult(ENABLE_NOTIFICATIONS_REQUEST, RESULT_OK, null)
            } else {
                Log.w(TAG, "Notifications permission prompt rejected")
                requestNotificationPermission(true)
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
                            data = "market://details?id=tech.httptoolkit.android.v1".toUri()
                        }
                    )
                }
                .show()
        }
    }

    private fun showVpnKilledAlert() {
        MaterialAlertDialogBuilder(this)
            .setTitle("HTTP Toolkit was killed")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
                "HTTP Toolkit interception was shut down automatically by Android. " +
                "This is usually caused by overly strict power management of background processes. " +
                "\n\n" +
                "To fix this, disable battery optimization for HTTP Toolkit in your settings."
            )
            .setNegativeButton("Ignore") { _, _ -> }
            .setPositiveButton("Go to settings") { _, _ ->
                val batterySettingIntents = listOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    } else null,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    } else null,
                    Intent().apply {
                        this.component = ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity"
                        )
                    },
                    Intent().apply {
                        this.component = ComponentName(
                            "com.samsung.android.sm",
                            "com.samsung.android.sm.ui.battery.BatteryActivity"
                        )
                    },
                    Intent(Settings.ACTION_SETTINGS)
                )

                // Try the intents in order until one of them works
                for (intent in batterySettingIntents) {
                    if (intent != null && tryStartActivity(intent)) break
                }
            }
            .show()
    }

    private fun showNoBrowserAlert(uri: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("No browser available")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
                "HTTP Toolkit could not open a browser on this device. " +
                "This usually means you don't have any browser installed. To visit " +
                uri +
                " please install a browser app."
            )
            .setNeutralButton("OK") { _, _ -> }
            .show()
    }

    private fun showActiveVpnFailureAlert() {
        MaterialAlertDialogBuilder(this)
            .setTitle("VPN setup failed")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
                "HTTP Toolkit could not be configured as a VPN on your device." +
                "\n\n" +
                "This usually means you have an always-on VPN configured, which blocks " +
                "installation of other VPNs. To activate HTTP Toolkit you'll need to " +
                "deactivate that VPN first."
            )
            .setNegativeButton("Cancel") { _, _ -> }
            .setPositiveButton("Open VPN Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } else {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
            .show()
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

private fun isPackageAvailable(context: Context, packageName: String) = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

private fun getDefaultBrowserPackage(context: Context): String? {
    val browserIntent = Intent("android.intent.action.VIEW", "http://example.com".toUri())
    val resolveInfo = context.packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolveInfo?.activityInfo?.packageName
}

private fun getTestBrowserPackage(context: Context): String? {
    // A list of browsers that trust the user store by default, and so
    // will work OOTB even if only the user cert is trusted.
    val supportedBrowsers = listOf(
        "com.android.chrome", // Modern Android
        "com.android.browser", // <= Android 2.3
        "com.google.android.browser", // > 2.3, < 4.0.2
        "com.brave.browser", // Brave
        "com.microsoft.emmx", // Edge
        "com.sec.android.app.sbrowser" // Samsung browser
        // FF/Opera/UC Browser & others don't trust user CAs by default, so we avoid them for testing
    )

    // If the default browser is supported, just use that, easy
    val defaultBrowser = getDefaultBrowserPackage(context)
    Log.i("tech.httptoolkit", "Default browser is $defaultBrowser")
    if (supportedBrowsers.contains(defaultBrowser)) {
        return defaultBrowser
    }

    // If not, use the first browser in the list above that's installed, or return null
    return supportedBrowsers.firstOrNull { packageName ->
        isPackageAvailable(context, packageName)
    }
}

private fun isStoreAvailable(context: Context): Boolean =
    isPackageAvailable(context, GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE)
