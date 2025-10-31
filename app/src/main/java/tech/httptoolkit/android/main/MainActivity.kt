package tech.httptoolkit.android.main

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.text.Html
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.*
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import androidx.core.net.toUri
import tech.httptoolkit.android.*
import tech.httptoolkit.android.appselection.ApplicationListActivity
import tech.httptoolkit.android.portfilter.PortListActivity
import tech.httptoolkit.android.qrscan.QRScanActivity
import tech.httptoolkit.android.ui.HttpToolkitTheme


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456
const val ENABLE_NOTIFICATIONS_REQUEST = 101

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

private const val ACTIVATE_INTENT = "tech.httptoolkit.android.ACTIVATE"
private const val DEACTIVATE_INTENT = "tech.httptoolkit.android.DEACTIVATE"

private val PROMPTED_CERT_SETUP_SUPPORTED = Build.VERSION.SDK_INT < Build.VERSION_CODES.R;

class MainActivity : ComponentActivity(), CoroutineScope by MainScope() {

    private lateinit var app: HttpToolkitApplication

    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VPN_STARTED_BROADCAST) {
                mainState = ConnectionState.CONNECTED
                currentProxyConfig = intent.getParcelableExtra(IntentExtras.PROXY_CONFIG_EXTRA)
                updateAppCounts()
            } else if (intent.action == VPN_STOPPED_BROADCAST) {
                mainState = ConnectionState.DISCONNECTED
                currentProxyConfig = null

                if (intent.getBooleanExtra(IntentExtras.VPN_FAILED_EXTRA, false)) {
                    showActiveVpnFailureAlert()
                }
            }
        }
    }

    private var mainState: ConnectionState by mutableStateOf(if (isVpnActive()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)

    // If connected/late-stage connecting, the proxy we're connected/trying to connect to. Otherwise null.
    private var currentProxyConfig: ProxyConfig? by mutableStateOf(activeVpnConfig())

    private var totalAppCount: Int by mutableIntStateOf(0)
    private var interceptedAppCount: Int by mutableIntStateOf(0)
    private var interceptedPorts: Set<Int> by mutableStateOf(emptySet())

    val pickAppsContract = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Pick apps result: OK")
            val unselectedApps = result.data!!.getStringArrayExtra(IntentExtras.UNSELECTED_APPS_EXTRA)!!.toSet()
            if (unselectedApps != app.uninterceptedApps) {
                app.uninterceptedApps = unselectedApps
                if (isVpnActive()) startVpn()
            }
            updateAppCounts()
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
            updateAppCounts()
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

    private var localNetworkPermissionResult: CompletableDeferred<Boolean>? = null

    private val localNetworkPermissionHandler =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.i(TAG, "Local network permission ${if (isGranted) "granted" else "rejected"}")
            localNetworkPermissionResult?.complete(isGranted)
            localNetworkPermissionResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })

        app = this.application as HttpToolkitApplication

        setContent {
            HttpToolkitTheme {
                MainScreen(
                    screenState = MainScreenState(
                        connectionState = mainState,
                        proxyConfig = currentProxyConfig,
                        hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                        lastProxy = app.lastProxy,
                        totalAppCount = totalAppCount,
                        interceptedAppCount = interceptedAppCount,
                        interceptedPorts = interceptedPorts
                    ),
                    actions = MainScreenActions(
                        onScanQRCode = { scanOrPasteQRCode() },
                        onReconnect = { reconnect() },
                        onDisconnect = { disconnect() },
                        onRecoverAfterFailure = { recoverAfterFailure() },
                        onTestInterception = { testInterception() },
                        onOpenDocs = { openDocs() },
                        onChooseApps = { chooseApps() },
                        onChoosePorts = { choosePorts() }
                    )
                )
            }
        }

        // Initialize app counts
        updateAppCounts()

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

    private fun reconnect() {
        val lastProxy = app.lastProxy
        if (lastProxy != null) {
            launch { reconnect(lastProxy) }
        }
    }

    private fun updateAppCounts() {
        val allPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            .map { pkg -> pkg.packageName }
            .toSet()
        totalAppCount = allPackages.size
        interceptedAppCount = totalAppCount - app.uninterceptedApps.size
        interceptedPorts = app.interceptedPorts
    }

    @MainThread
    private fun scanOrPasteQRCode() {
        // On an emulator (no camera) or some physical setups, it's useful to be able to just
        // copy the URL instead of having to use the camera, so check that first:
        val clipboardUrl = getConnectUrlFromClipboard()
        if (clipboardUrl != null) {
            Log.i(TAG, "Connecting from clipboard URL")
            launch { connectToVpnFromUrl(clipboardUrl) }
            return
        }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            checkCameraPermission()
        } else {
            // No camera to fall back to, no clipboard URL - just explain
            MaterialAlertDialogBuilder(this)
                .setTitle("No connect URL or camera available")
                .setMessage("Copy an HTTP Toolkit QR code URL to the clipboard then try again.")
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }

    private fun getConnectUrlFromClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) return null

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return null
        return if (text.startsWith(Constants.QR_CODE_URL_PREFIX)) text else null
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
        this.mainState = ConnectionState.CONNECTING

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
        mainState = ConnectionState.DISCONNECTING

        // Any cert we downloaded is only useful for a connection we're now dropping, and the
        // next setup attempt downloads it again:
        launch {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) deleteDownloadedCerts()
            }
        }

        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = STOP_VPN_ACTION
        })
    }

    private suspend fun reconnect(lastProxy: ProxyConfig) {
        mainState = ConnectionState.CONNECTING

        ensureLocalNetworkPermission()

        try {
            // Revalidates the config, to ensure the server is available (and drop retries if not)
            val config = getProxyConfig(
                ProxyInfo(
                    listOf(lastProxy.ip),
                    lastProxy.port,
                    null,
                    getCertificateFingerprint(lastProxy.certificate as X509Certificate),
                    if (lastProxy.captureProtocol != null) listOf(lastProxy.captureProtocol.toString()) else listOf()
                )
            )
            connectToVpn(config)
        } catch (e: Exception) {
            app.lastProxy = null

            Log.e(TAG, e.toString())
            e.printStackTrace()

            mainState = ConnectionState.FAILED

            Sentry.captureException(e)
        }
    }

    private fun recoverAfterFailure() {
        currentProxyConfig = null
        mainState = ConnectionState.DISCONNECTED
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
                deleteDownloadedCertsIfTrusted()
                ensureNotificationsEnabled()
            } else if (requestCode == ENABLE_NOTIFICATIONS_REQUEST) {
                Log.i(TAG, "Notifications OK, starting VPN...")
                startVpn()
            }
        } else if (
            requestCode == INSTALL_CERT_REQUEST &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // Required for promptToManuallyInstallCert
        ) {
            // Certificate install failed. Could be manual (failed to follow instructions) or automated
            // via prompt. We redo the manual step regardless: either (on modern Android) manual is
            // required so this is just reshowing the instructions, or it was automated but that's not
            // working for some reason, in which case manual setup is a best-effort fallback.
            launch { promptToManuallyInstallCert(currentProxyConfig!!.certificate) }
        } else if (requestCode == ENABLE_NOTIFICATIONS_REQUEST) {
            // If we tried to enable notifications, and it didn't work (the user
            // ignored us) then try try again.
            requestNotificationPermission(true)
        } else if (resultCode == RESULT_CANCELED) {
            mainState = ConnectionState.DISCONNECTED
        } else {
            val requestName = when (requestCode) {
                START_VPN_REQUEST -> "start-vpn"
                INSTALL_CERT_REQUEST -> "install-cert"
                ENABLE_NOTIFICATIONS_REQUEST -> "enable-notifications"
                else -> "other"
            }
            Sentry.captureMessage("Non-OK result $resultCode for $requestName request")
            mainState = ConnectionState.FAILED
        }
    }

    private fun startVpn() {
        Log.i(TAG, "Starting VPN")

        mainState = ConnectionState.CONNECTING

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
            mainState != ConnectionState.DISCONNECTED &&
            mainState != ConnectionState.FAILED
        ) return

        mainState = ConnectionState.CONNECTING

        ensureLocalNetworkPermission()

        withContext(Dispatchers.IO) {
            try {
                val config = getProxyConfig(parseConnectUri(uri))
                connectToVpn(config)
            } catch (e: Exception) {
                Log.e(TAG, e.toString())
                e.printStackTrace()

                mainState = ConnectionState.FAILED

                Sentry.captureException(e)
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
                // Android 11+, with no trusted cert: we need to tell the user how to install it
                // manually, and download the cert to Downloads for them to select:
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
                .setCertSetupChoices()
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun promptToManuallyInstallCert(cert: Certificate) {
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Manual setup required")
                .setIcon(R.drawable.ic_exclamation_triangle)
                .setMessage(
                    Html.fromHtml(
                        """
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
                            <li>&nbsp; <b>Select "$CERT_DOWNLOAD_FILENAME" in your Downloads folder</b></li>
                        </ul>
                    """, 0)
                )
                .setPositiveButton("Open security settings") { _, _ ->
                    // Scoped to the activity explicitly, as the scope that built this dialog
                    // has long since completed:
                    this@MainActivity.launch { saveCertAndOpenSecuritySettings(cert) }
                }
                .setCertSetupChoices()
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveCertAndOpenSecuritySettings(cert: Certificate) {
        val certSaved = saveCertToDownloads(cert)
        if (isFinishing || isDestroyed) return // We might've been destroyed while saving

        if (certSaved) {
            startActivityForResult(Intent(Settings.ACTION_SECURITY_SETTINGS), INSTALL_CERT_REQUEST)
        } else {
            // The instructions are useless without the file, and worse than useless if an
            // outdated cert from a previous setup is still sitting in downloads:
            showCertDownloadFailedAlert(cert)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun showCertDownloadFailedAlert(cert: Certificate) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Certificate download failed")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
                "HTTP Toolkit could not save its certificate into your Downloads folder, so " +
                "there's no certificate there for you to install." +
                "\n\n" +
                "Check that your device has storage space available, and then try again."
            )
            .setPositiveButton("Try again") { _, _ ->
                launch { saveCertAndOpenSecuritySettings(cert) }
            }
            .setCertSetupChoices()
            .show()
    }

    /**
     * Drop our downloaded copy of the cert, once it's actually trusted. We check that explicitly
     * because this is reached by skipping the prompt too, where a cert downloaded by an earlier
     * attempt may still be there, and may still be wanted.
     */
    private fun deleteDownloadedCertsIfTrusted() {
        val proxyConfig = currentProxyConfig ?: return

        launch {
            withContext(Dispatchers.IO) {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    whereIsCertTrusted(proxyConfig) != null
                ) {
                    deleteDownloadedCerts()
                }
            }
        }
    }

    /**
     * The options shared by every cert setup prompt. Skipping continues the rest of setup as if
     * the cert had been installed, leaving HTTPS interception broken until it really is.
     */
    private fun MaterialAlertDialogBuilder.setCertSetupChoices() = this
        .setNeutralButton("Skip") { _, _ ->
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
        .setNegativeButton("Cancel") { _, _ ->
            disconnect()
        }
        .setCancelable(false)

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

    /**
     * From Android 17, reaching the local network requires an explicit runtime permission. Without
     * it TCP connections to LAN addresses time out rather than failing outright, so we ask for it
     * before we start probing for the proxy. Loopback isn't restricted, so a rejection still leaves
     * ADB-tunnel connections working, and we continue regardless.
     */
    private suspend fun ensureLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK)
                == PERMISSION_GRANTED
        ) return

        val result = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            Log.i(TAG, "Asking for ACCESS_LOCAL_NETWORK")
            localNetworkPermissionResult = result
            localNetworkPermissionHandler.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }

        if (!result.await()) {
            withContext(Dispatchers.Main) { showLocalNetworkPermissionRejectedAlert() }
        }
    }

    private fun showLocalNetworkPermissionRejectedAlert() {
        // If we can still show a rationale we're allowed to ask again, so a later connection
        // attempt will reprompt. If not, only the settings page can fix this.
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_LOCAL_NETWORK
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Local network permission required")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
                "HTTP Toolkit needs local network access to reach HTTP Toolkit running on your " +
                "computer, and to intercept traffic sent to your local network." +
                if (canAskAgain) "" else
                    "\n\n" +
                    "To allow this, enable the 'Nearby devices' permission for HTTP Toolkit in " +
                    "your device settings."
            )
            .apply {
                if (canAskAgain) {
                    setPositiveButton(android.R.string.ok) { _, _ -> }
                } else {
                    setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                        startActivity(Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        ))
                    }
                    setNegativeButton(getString(R.string.cancel)) { _, _ -> }
                }
            }
            .show()
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
        // The VPN service can report failures while we're in the background:
        if (isFinishing || isDestroyed) return

        MaterialAlertDialogBuilder(this)
            .setTitle("VPN setup failed")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
                "HTTP Toolkit could not be configured as a VPN on your device." +
                "\n\n" +
                "This usually means another VPN is active, which blocks HTTP Toolkit's VPN. To " +
                "activate HTTP Toolkit you'll need to deactivate that VPN first, including any " +
                "always-on or auto-reconnect options."
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
